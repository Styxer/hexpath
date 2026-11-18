package dev.hexpath.geo.corridor;

import dev.hexpath.geo.api.ApiProblemException;
import dev.hexpath.geo.api.ProblemType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;

/** Validates a GeoJSON LineString route ([lon, lat] positions) and turns it into a JTS geometry. */
public final class RouteInput {

    public static final int MAX_VERTICES = 1000;
    public static final double MAX_LENGTH_M = 50_000;
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    public record Route(String type, List<List<Double>> coordinates) {
    }

    private RouteInput() {
    }

    public static LineString parse(Route route) {
        if (route == null || !"LineString".equals(route.type()) || route.coordinates() == null) {
            throw invalid("route must be a GeoJSON LineString: {\"type\":\"LineString\",\"coordinates\":[[lon,lat],...]}");
        }
        List<List<Double>> pos = route.coordinates();
        if (pos.size() > MAX_VERTICES) {
            throw invalid("a route may have at most " + MAX_VERTICES + " positions, got " + pos.size());
        }
        Coordinate[] coords = new Coordinate[pos.size()];
        Set<Coordinate> distinct = new HashSet<>();
        for (int i = 0; i < coords.length; i++) {
            List<Double> p = pos.get(i);
            if (p == null || p.size() < 2 || p.get(0) == null || p.get(1) == null
                    || !Double.isFinite(p.get(0)) || !Double.isFinite(p.get(1))) {
                throw invalid("position " + i + " must be [lon, lat] with finite numbers");
            }
            coords[i] = new Coordinate(p.get(0), p.get(1));
            distinct.add(coords[i]);
        }
        if (distinct.size() < 2) {
            throw invalid("a route needs at least 2 distinct positions");
        }
        double length = 0;
        for (int i = 0; i + 1 < coords.length; i++) {
            length += Geodesy.haversineM(coords[i].x, coords[i].y, coords[i + 1].x, coords[i + 1].y);
        }
        if (length > MAX_LENGTH_M) {
            throw new ApiProblemException(ProblemType.ROUTE_TOO_LONG,
                    String.format("route is %.1f km; the limit is %.0f km", length / 1000, MAX_LENGTH_M / 1000));
        }
        return GF.createLineString(coords);
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(ProblemType.INVALID_PARAMETER, detail);
    }
}
