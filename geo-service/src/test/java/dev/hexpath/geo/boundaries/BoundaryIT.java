package dev.hexpath.geo.boundaries;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.hexpath.geo.support.AbstractApiIT;
import dev.hexpath.geo.support.Http;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundaryIT extends AbstractApiIT {

    private static List<String> ids(Http.Response r) {
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.results[*].id");
    }

    @Test
    void pointInZoneA() {
        assertThat(ids(http.get("/v1/boundaries/containing?lat=32.0779&lon=34.7740"))).containsExactly("osm-r100", "zone-a");
    }

    @Test
    void pointOutsideZoneA() {
        assertThat(ids(http.get("/v1/boundaries/containing?lat=32.0650&lon=34.7800"))).containsExactly("osm-r100");
    }

    @Test
    void regulatoryFilter() {
        assertThat(ids(http.get("/v1/boundaries/containing?lat=32.0779&lon=34.7740&type=REGULATORY"))).containsExactly("zone-a");
    }

    @Test
    void zoneIsLabelledSynthetic() {
        var body = http.get("/v1/boundaries/containing?lat=32.0779&lon=34.7740").body();
        assertThat((List<String>) JsonPath.read(body, "$.results[?(@.id=='zone-a')].origin")).containsExactly("synthetic");
    }

    @Test
    void unknownTypeIs400() {
        assertProblem(http.get("/v1/boundaries/containing?lat=32.0779&lon=34.7740&type=COUNTY"), 400, "invalid-parameter");
    }

    @Test
    void missingLonIs400() {
        assertProblem(http.get("/v1/boundaries/containing?lat=32.0779"), 400, "invalid-parameter");
    }

    @Test
    void outsideCoverageIs422() {
        assertProblem(http.get("/v1/boundaries/containing?lat=31.0&lon=34.7740"), 422, "out-of-coverage");
    }
}
