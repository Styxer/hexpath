package dev.hexpath.geo.dataset;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import dev.hexpath.geo.support.PostgisContainerSupport;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A transient failure while loading a snapshot must not stop the service from following releases. */
@SpringBootTest
class ListenerResilienceIT extends PostgisContainerSupport {

    @MockitoSpyBean SnapshotLoader loader;
    @Autowired SnapshotHolder holder;

    @AfterAll
    static void restore() throws SQLException {
        resetToSeed();
    }

    @Test
    void survivesALoaderFailure() throws SQLException {
        await().atMost(10, SECONDS).until(() -> holder.current().map(DatasetSnapshot::releaseId).equals(Optional.of(1L)));
        doThrow(new CannotGetJdbcConnectionException("pool exhausted"))
                .doCallRealMethod().when(loader).loadActive();
        long id = publishSyntheticRelease("it-resilience", true);
        await().atMost(20, SECONDS).until(() -> holder.current().map(DatasetSnapshot::releaseId).equals(Optional.of(id)));
    }
}
