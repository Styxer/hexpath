package dev.hexpath.geo.corridor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hexpath.geo.api.ApiProblemException;
import dev.hexpath.geo.api.ProblemType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteInputTest {

    static RouteInput.Route line(double... lonLat) {
        List<List<Double>> c = new ArrayList<>();
        for (int i = 0; i < lonLat.length; i += 2) {
            c.add(List.of(lonLat[i], lonLat[i + 1]));
        }
        return new RouteInput.Route("LineString", c);
    }

    static void assertType(Runnable r, ProblemType type) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiProblemException.class,
                e -> assertThat(e.type()).isEqualTo(type));
    }

    @Test
    void parsesLonLatOrder() {
        var g = RouteInput.parse(line(34.7740, 32.0700, 34.7740, 32.0850));
        assertThat(g.getNumPoints()).isEqualTo(2);
        assertThat(g.getCoordinateN(0).x).isEqualTo(34.7740);   // x = lon
    }

    @Test
    void singleVertexIs400() {
        assertType(() -> RouteInput.parse(line(34.774, 32.07)), ProblemType.INVALID_PARAMETER);
    }

    @Test
    void identicalVerticesIs400() {
        assertType(() -> RouteInput.parse(line(34.774, 32.07, 34.774, 32.07, 34.774, 32.07)), ProblemType.INVALID_PARAMETER);
    }

    @Test
    void notALineStringIs400() {
        assertType(() -> RouteInput.parse(new RouteInput.Route("Point", List.of(List.of(34.774, 32.07)))),
                ProblemType.INVALID_PARAMETER);
        assertType(() -> RouteInput.parse(null), ProblemType.INVALID_PARAMETER);
    }

    @Test
    void shortPositionIs400() {
        assertType(() -> RouteInput.parse(new RouteInput.Route("LineString",
                List.of(List.of(34.774, 32.07), List.of(34.775)))), ProblemType.INVALID_PARAMETER);
    }

    @Test
    void over1000VerticesIs400() {
        double[] c = new double[1001 * 2];
        for (int i = 0; i < 1001; i++) {
            c[2 * i] = 34.770 + (i % 2) * 0.0001;
            c[2 * i + 1] = 32.070 + i * 0.00001;
        }
        assertType(() -> RouteInput.parse(line(c)), ProblemType.INVALID_PARAMETER);
    }

    @Test
    void over50kmIsRouteTooLong() {    // 20 legs of about 2.9 km
        double[] c = new double[21 * 2];
        for (int i = 0; i < 21; i++) {
            c[2 * i] = i % 2 == 0 ? 34.770 : 34.790;
            c[2 * i + 1] = i % 2 == 0 ? 32.065 : 32.085;
        }
        assertType(() -> RouteInput.parse(line(c)), ProblemType.ROUTE_TOO_LONG);
    }
}
