package dev.hexpath.geo.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hexpath.geo.support.AbstractApiIT;
import org.junit.jupiter.api.Test;

class ProblemResponsesIT extends AbstractApiIT {

    @Test
    void everyResponseHasHeaders() {   // including a 404
        var r = http.get("/v1/nope");
        assertProblem(r, 404, "not-found");
        assertThat(r.header("X-Dataset-Release")).isEqualTo("1");
        assertThat(r.header("X-Request-Id")).isNotBlank();
        assertThat(r.body()).contains("\"instance\":\"/v1/nope\"");
    }

    @Test
    void echoesValidRequestIdAndReplacesInvalid() {
        assertThat(http.get("/v1/nope", "X-Request-Id", "abc-123").header("X-Request-Id")).isEqualTo("abc-123");
        String replaced = http.get("/v1/nope", "X-Request-Id", "<script>").header("X-Request-Id");
        assertThat(replaced).isNotEqualTo("<script>").matches("[A-Za-z0-9-]{1,64}");
    }

    @Test
    void actuatorIsOutsideTheApiButStillTagged() {
        var r = http.get("/actuator/health/liveness");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("X-Request-Id")).isNotBlank();
    }
}
