package dev.hexpath.geo.corridor;

import java.util.List;
import org.locationtech.jts.geom.LineString;

public final class RouteInput {
    public record Route(String type, List<List<Double>> coordinates) {
    }

    private RouteInput() {
    }

    public static LineString parse(Route route) {
        return null;
    }
}
