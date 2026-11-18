package dev.hexpath.geo.support;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.hexpath.geo.dataset.DatasetSnapshot;
import dev.hexpath.geo.dataset.SnapshotHolder;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Base for API tests: a running server on a random port, serving the seed release (id 1). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractApiIT extends PostgisContainerSupport {

    @Autowired protected SnapshotHolder holder;
    @Autowired Environment env;
    protected Http http;

    @BeforeEach
    void serveSeedRelease() {
        await().atMost(15, SECONDS).until(() -> holder.current().map(DatasetSnapshot::releaseId).equals(Optional.of(1L)));
        http = new Http(env.getRequiredProperty("local.server.port", Integer.class));
    }

    protected static void assertProblem(Http.Response r, int status, String slug) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.header("Content-Type")).startsWith("application/problem+json");
        assertThat(r.body()).contains("\"type\":\"urn:hexpath:problem:" + slug + "\"");
    }
}
