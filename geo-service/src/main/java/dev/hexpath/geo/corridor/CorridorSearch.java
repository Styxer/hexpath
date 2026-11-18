package dev.hexpath.geo.corridor;

import org.locationtech.jts.geom.LineString;

/** Places within bufferM metres of a route. Implementations must return identical results. */
public interface CorridorSearch {
    String name();

    CorridorResult search(long releaseId, LineString route, double bufferM, String categoryOrNull, int limit);
}
