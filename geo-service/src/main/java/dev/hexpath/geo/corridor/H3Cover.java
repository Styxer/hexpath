package dev.hexpath.geo.corridor;

import java.util.Set;
import org.locationtech.jts.geom.LineString;

public final class H3Cover {
    private H3Cover() {
    }

    public static Set<Long> cover(LineString route, double bufferM) {
        return Set.of();
    }

    public static long cellOf(double lat, double lon) {
        return 0;
    }
}
