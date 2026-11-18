package dev.hexpath.geo.corridor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.hexpath.geo.support.AbstractApiIT;
import dev.hexpath.geo.support.Http;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Expected ids come from the mini fixture. Distances to the route along lon 34.7740 (lat 32.0700 to 32.0850):
 * n1 9 m, n6 47 m, n2 94 m, n3 283 m, n7 377 m, n5 554 m (past the end), n8 665 m (before the start), n4 1037 m.
 */
class CorridorSearchIT extends AbstractApiIT {

    static final String ROUTE = "{\"type\":\"LineString\",\"coordinates\":[[34.7740,32.0700],[34.7740,32.0850]]}";

    private Http.Response along(String route, double buffer, String strategy) {
        return http.postJson("/v1/search/along-route?debug=true&strategy=" + strategy,
                String.format(Locale.ROOT, "{\"route\":%s,\"buffer_m\":%s}", route, buffer));
    }

    private static List<String> ids(Http.Response r) {
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.results[*].id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgis", "h3"})
    void buffer100(String s) {
        assertThat(ids(along(ROUTE, 100, s))).containsExactly("n1", "n6", "n2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgis", "h3"})
    void buffer300(String s) {
        assertThat(ids(along(ROUTE, 300, s))).containsExactly("n1", "n6", "n2", "n3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgis", "h3"})
    void buffer1000(String s) {
        assertThat(ids(along(ROUTE, 1000, s))).containsExactlyInAnyOrder("n1", "n6", "n2", "n3", "n7", "n5", "n8");
    }

    @Test
    void strategiesAgreeOnRandomRoutes() {
        Random rnd = new Random(7);
        for (int i = 0; i < 50; i++) {
            StringBuilder c = new StringBuilder();
            int n = 2 + rnd.nextInt(4);
            for (int v = 0; v < n; v++) {
                c.append(v == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%.6f,%.6f]",
                        34.766 + rnd.nextDouble() * 0.028, 32.061 + rnd.nextDouble() * 0.028));
            }
            String route = "{\"type\":\"LineString\",\"coordinates\":[" + c + "]}";
            for (double b : new double[] {100, 300, 1000}) {
                assertThat(ids(along(route, b, "h3"))).as("route %s buffer %s", route, b)
                        .isEqualTo(ids(along(route, b, "postgis")));
            }
        }
    }

    @Test
    void h3CandidatesAtLeastHits() {
        var r = along(ROUTE, 300, "h3");
        int candidates = JsonPath.read(r.body(), "$.debug.candidates");
        assertThat(candidates).isGreaterThanOrEqualTo(ids(r).size());
    }

    @Test
    void alongIsMonotonicForPlacesOnRoute() {
        var body = along(ROUTE, 300, "postgis").body();
        double n1 = ((Number) JsonPath.<List<Object>>read(body, "$.results[?(@.id=='n1')].along_m").getFirst()).doubleValue();
        double n2 = ((Number) JsonPath.<List<Object>>read(body, "$.results[?(@.id=='n2')].along_m").getFirst()).doubleValue();
        double n3 = ((Number) JsonPath.<List<Object>>read(body, "$.results[?(@.id=='n3')].along_m").getFirst()).doubleValue();
        assertThat(n1).isLessThan(n2);
        assertThat(n2).isLessThan(n3);
        assertThat(n1).isBetween(500.0, 600.0);   // 32.0700 -> 32.0750 is about 554 m
    }

    @Test
    void defaultStrategyAndNoDebugByDefault() {
        var r = http.postJson("/v1/search/along-route", "{\"route\":" + ROUTE + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(r.body(), "$.strategy")).isEqualTo("postgis");
        assertThat(r.body()).doesNotContain("\"debug\"");
    }

    @Test
    void categoryFilter() {
        var r = http.postJson("/v1/search/along-route",
                "{\"route\":" + ROUTE + ",\"buffer_m\":1000,\"category\":\"cafe\"}");
        assertThat(ids(r)).containsExactlyInAnyOrder("n1", "n6", "n5");
    }

    @Test
    void swappedCoordinatesIs422() {
        var r = http.postJson("/v1/search/along-route",
                "{\"route\":{\"type\":\"LineString\",\"coordinates\":[[32.0700,34.7740],[32.0850,34.7740]]}}");
        assertProblem(r, 422, "out-of-coverage");
    }

    @Test
    void singleVertexIs400() {
        assertProblem(http.postJson("/v1/search/along-route",
                "{\"route\":{\"type\":\"LineString\",\"coordinates\":[[34.7740,32.0700]]}}"), 400, "invalid-parameter");
    }

    @Test
    void identicalVerticesIs400() {
        assertProblem(http.postJson("/v1/search/along-route",
                "{\"route\":{\"type\":\"LineString\",\"coordinates\":[[34.774,32.07],[34.774,32.07]]}}"), 400, "invalid-parameter");
    }

    @Test
    void nanCoordinateIs400() {
        assertProblem(http.postJson("/v1/search/along-route",
                "{\"route\":{\"type\":\"LineString\",\"coordinates\":[[NaN,32.07],[34.774,32.08]]}}"), 400, "invalid-parameter");
    }

    @Test
    void missingRouteIs400() {
        assertProblem(http.postJson("/v1/search/along-route", "{}"), 400, "invalid-parameter");
    }

    @Test
    void routeOver50kmIs422() {
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            c.append(i == 0 ? "" : ",").append(i % 2 == 0 ? "[34.770,32.065]" : "[34.790,32.085]");
        }
        assertProblem(http.postJson("/v1/search/along-route",
                "{\"route\":{\"type\":\"LineString\",\"coordinates\":[" + c + "]}}"), 422, "route-too-long");
    }

    @Test
    void bufferAbove2000Is400() {
        assertProblem(along(ROUTE, 2001, "postgis"), 400, "invalid-parameter");
    }

    @Test
    void unknownStrategyIs400() {
        assertProblem(along(ROUTE, 300, "quadtree"), 400, "invalid-parameter");
    }

    @Test
    void serverTimingHeaderPresent() {
        assertThat(along(ROUTE, 300, "h3").header("Server-Timing")).startsWith("corridor;dur=");
    }
}
