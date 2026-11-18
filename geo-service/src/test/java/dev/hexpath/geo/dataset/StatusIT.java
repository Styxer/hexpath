package dev.hexpath.geo.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.hexpath.geo.support.AbstractApiIT;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusIT extends AbstractApiIT {

    @Test
    void datasetShowsSeedRelease() {
        var r = http.get("/v1/dataset");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(r.body(), "$.release")).longValue()).isEqualTo(1L);
        assertThat((String) JsonPath.read(r.body(), "$.source_timestamp")).startsWith("2026-11-01T00:00");
        assertThat((List<Double>) JsonPath.read(r.body(), "$.coverage")).containsExactly(34.765, 32.06, 34.795, 32.09);
        assertThat((Integer) JsonPath.read(r.body(), "$.counts.place")).isEqualTo(8);
        assertThat(((Number) JsonPath.read(r.body(), "$.data_age_seconds")).longValue()).isPositive();
    }

    @Test
    void statusCountsRequestsAndCarriesNote() {
        for (int i = 0; i < 3; i++) {
            http.get("/v1/places/search?lat=32.0779&lon=34.7740");
        }
        var r = http.get("/v1/status");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(r.body(), "$.endpoints['/v1/places/search'].requests")).longValue())
                .isGreaterThanOrEqualTo(3);
        assertThat(((Number) JsonPath.read(r.body(), "$.endpoints['/v1/places/search'].p95_ms")).doubleValue()).isPositive();
        assertThat((String) JsonPath.read(r.body(), "$.note")).contains("measured externally");
    }
}
