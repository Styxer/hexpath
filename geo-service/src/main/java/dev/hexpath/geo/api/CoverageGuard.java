package dev.hexpath.geo.api;

import dev.hexpath.geo.dataset.DatasetSnapshot;
import org.locationtech.jts.geom.Envelope;

/** Rejects points the active release cannot answer for, and says why. */
public final class CoverageGuard {

    private CoverageGuard() {
    }

    public static void requireInside(DatasetSnapshot s, double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new ApiProblemException(ProblemType.INVALID_PARAMETER,
                    "lat must be in [-90, 90] and lon in [-180, 180], got (" + lat + ", " + lon + ")");
        }
        Envelope c = s.coverage();
        if (!c.contains(lon, lat)) {
            throw new ApiProblemException(ProblemType.OUT_OF_COVERAGE, String.format(
                    "point (%s, %s) is outside coverage [%s,%s,%s,%s]; check coordinate order",
                    lat, lon, c.getMinX(), c.getMinY(), c.getMaxX(), c.getMaxY()));
        }
    }
}
