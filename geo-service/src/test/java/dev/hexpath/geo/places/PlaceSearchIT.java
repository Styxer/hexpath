package dev.hexpath.geo.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.hexpath.geo.support.AbstractApiIT;
import dev.hexpath.geo.support.Http;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaceSearchIT extends AbstractApiIT {

    private Http.Response search(String query) {
        return http.get("/v1/places/search?" + query);
    }

    private static List<String> ids(Http.Response r) {
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.results[*].id");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void typoFindsPlace() {
        assertThat(ids(search("q=dizengof&lat=32.0779&lon=34.7740"))).contains("n1", "n6");
    }

    @Test
    void hebrewWithNiqqudFinds() {
        assertThat(ids(search("q=" + enc("דִּיזִינְגּוֹף")))).contains("n6");
    }

    @Test
    void radiusOnlyOrdersByDistance() {   // from (32.0779, 34.7740): n2 94 m, n6 216 m, n1 322 m
        var r = search("lat=32.0779&lon=34.7740&radius_m=250");
        assertThat(ids(r)).containsExactly("n2", "n6");
        double d = ((Number) JsonPath.read(r.body(), "$.results[0].distance_m")).doubleValue();
        assertThat(d).isBetween(90.0, 98.0);
        assertThat((List<?>) JsonPath.read(r.body(), "$.results[0].location.coordinates")).hasSize(2);
    }

    @Test
    void categoryFilters() {
        assertThat(ids(search("lat=32.0779&lon=34.7740&radius_m=5000&category=cafe")))
                .containsExactlyInAnyOrder("n1", "n6", "n5");
    }

    @Test
    void stopwordOnlyQueryIs400() {
        assertProblem(search("q=" + enc("רחוב")), 400, "invalid-parameter");
        assertProblem(search("q=" + enc("   ")), 400, "invalid-parameter");
    }

    @Test
    void neitherQueryNorLocationIs400() {
        assertProblem(search("category=cafe"), 400, "invalid-parameter");
        assertProblem(search("lat=32.0779"), 400, "invalid-parameter");
    }

    @Test
    void pagingIsStableAndComplete() {
        List<String> unpaged = ids(search("lat=32.0779&lon=34.7740&radius_m=5000&limit=100"));
        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            var r = search("lat=32.0779&lon=34.7740&radius_m=5000&limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            walked.addAll(ids(r));
            cursor = JsonPath.read(r.body(), "$.next_cursor");
            if (cursor == null) {
                break;
            }
        }
        assertThat(walked).hasSize(new HashSet<>(walked).size()).isEqualTo(unpaged);
        assertThat(unpaged).hasSize(8);
    }

    @Test
    void cursorFromOtherReleaseIs400() {
        var r = search("lat=32.0779&lon=34.7740&cursor=" + new SearchCursor(99, 0, 1, "n1").encode());
        assertProblem(r, 400, "invalid-parameter");
        assertThat(r.body()).contains("belongs to release 99");
    }

    @Test
    void tamperedCursorIs400() {
        assertProblem(search("lat=32.0779&lon=34.7740&cursor=%25%25%25"), 400, "invalid-parameter");
    }

    @Test
    void unknownIdIs404() {
        assertProblem(http.get("/v1/places/n999"), 404, "not-found");
    }

    @Test
    void detailReturnsPlace() {
        var r = http.get("/v1/places/n6");
        assertThat(r.status()).isEqualTo(200);
        assertThat((String) JsonPath.read(r.body(), "$.name_he")).isEqualTo("דיזנגוף קפה");
    }

    @Test
    void limitAbove100Is400() {
        assertProblem(search("lat=32.0779&lon=34.7740&limit=101"), 400, "invalid-parameter");
    }

    @Test
    void radiusAbove5000Is400() {
        assertProblem(search("lat=32.0779&lon=34.7740&radius_m=5001"), 400, "invalid-parameter");
    }

    @Test
    void nonNumericLatIs400() {
        assertProblem(search("lat=north&lon=34.7740"), 400, "invalid-parameter");
    }

    @Test
    void pointOutsideCoverageIs422() {
        assertProblem(search("lat=34.7740&lon=32.0779"), 422, "out-of-coverage");
    }
}
