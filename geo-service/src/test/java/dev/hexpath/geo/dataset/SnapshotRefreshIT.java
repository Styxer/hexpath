package dev.hexpath.geo.dataset;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.hexpath.geo.support.Http;
import dev.hexpath.geo.support.PostgisContainerSupport;
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
}
