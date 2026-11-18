package dev.hexpath.geo.dataset;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.hexpath.geo.support.Http;
import dev.hexpath.geo.support.PostgisContainerSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SnapshotRefreshIT extends PostgisContainerSupport {

    @Autowired SnapshotHolder holder;
    @Autowired Environment env;

    @AfterAll
    static void restore() throws SQLException {
        resetToSeed();
    }

    private Optional<Long> release() {
        return holder.current().map(DatasetSnapshot::releaseId);
    }

    @Test @Order(1)
    void becomesReadyWithSeedRelease() {
        await().atMost(10, SECONDS).until(() -> release().equals(Optional.of(1L)));
        var http = new Http(env.getRequiredProperty("local.server.port", Integer.class));
        assertThat(http.get("/actuator/health/readiness").status()).isEqualTo(200);
        assertThat(holder.current().orElseThrow().coverage().getMinX()).isEqualTo(34.765);
    }

    @Test @Order(2)
    void swapsWhenPipelinePublishes() throws SQLException {
        long id = publishSyntheticRelease("it-notify", true);
        await().atMost(3, SECONDS).until(() -> release().equals(Optional.of(id)));   // NOTIFY path
    }

    @Test @Order(3)
    void pollFallbackSwapsWithoutNotify() throws SQLException {
        long id = publishSyntheticRelease("it-poll", false);
        await().atMost(15, SECONDS).until(() -> release().equals(Optional.of(id)));  // 10 s poll path
    }

    /** Does what ReleaseStore.stage + publish do, as geo_writer, optionally without the NOTIFY. */
    private static long publishSyntheticRelease(String hash, boolean notify) throws SQLException {
        try (Connection c = connect("writer")) {
            c.setAutoCommit(false);
            long id;
            try (var st = c.prepareStatement("""
                    INSERT INTO dataset_release (input_hash, source, status, stats)
                    VALUES (?, 'it', 'STAGED', '{"coverage": [34.765, 32.06, 34.795, 32.09], "counts": {}}')
                    RETURNING id""")) {
                st.setString(1, hash);
                var rs = st.executeQuery();
                rs.next();
                id = rs.getLong(1);
            }
            try (var st = c.createStatement()) {
                st.execute("SELECT hexpath_create_partitions(" + id + ")");
                st.execute("UPDATE dataset_release SET status = 'RETIRED', retired_at = now() WHERE status = 'ACTIVE'");
                st.execute("UPDATE dataset_release SET status = 'ACTIVE', published_at = now() WHERE id = " + id);
                st.execute("INSERT INTO dataset_event (release_id, type, payload) VALUES (" + id
                        + ", 'RELEASE_ACTIVATED', '{}')");
                if (notify) {
                    st.execute("SELECT pg_notify('dataset_events', '" + id + "')");
                }
            }
            c.commit();
            return id;
        }
    }
}
