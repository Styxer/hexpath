package dev.hexpath.geo.corridor;

import com.uber.h3core.H3Core;
import com.uber.h3core.LengthUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

/**
 * Resolution-9 H3 cells guaranteed to contain every point within bufferM of the route.
 *
 * <ol>
 *   <li>Densify the route so consecutive samples are at most half an average r9 edge apart (~87 m).</li>
 *   <li>Take the cell of every sample.</li>
 *   <li>Expand each cell by k rings, with k large enough that the union is a superset of the buffer.</li>
 * </ol>
 * The superset property is checked by H3CoverPropertyTest over 1,000 random routes; the exact
 * distance filter in SQL then removes the extra candidates.
 */
public final class H3Cover {

    public static final int RESOLUTION = 9;
    private static final H3Core H3;
    static final double EDGE_AVG_M;
    static final double SPACING_M;

    static {
        try {
            H3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load the H3 native library", e);
        }
        EDGE_AVG_M = H3.getHexagonEdgeLengthAvg(RESOLUTION, LengthUnit.m);
        SPACING_M = EDGE_AVG_M / 2;
    }

    private H3Cover() {
    }

    /**
     * A sample is within SPACING_M of the route's closest point, so a place within bufferM of the
     * route is within bufferM + SPACING_M of some sample. Each ring adds at least the hexagon's
     * inradius (sqrt(3)/2 * edge); 0.9 allows for r9 cells smaller than average; +1 covers the
     * offset of a point from its own cell's centre.
     */
    static int ringSize(double bufferM) {
        return (int) Math.ceil((bufferM + SPACING_M) / (Math.sqrt(3) / 2 * EDGE_AVG_M * 0.9)) + 1;
    }

    public static long cellOf(double lat, double lon) {
        return H3.latLngToCell(lat, lon, RESOLUTION);
    }

    public static Set<Long> cover(LineString route, double bufferM) {
        Set<Long> sampled = new LinkedHashSet<>();
        Coordinate[] c = route.getCoordinates();
        sampled.add(cellOf(c[0].y, c[0].x));
        for (int i = 0; i + 1 < c.length; i++) {
            double d = Geodesy.haversineM(c[i].x, c[i].y, c[i + 1].x, c[i + 1].y);
            int steps = Math.max(1, (int) Math.ceil(d / SPACING_M));
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                sampled.add(cellOf(c[i].y + t * (c[i + 1].y - c[i].y), c[i].x + t * (c[i + 1].x - c[i].x)));
            }
        }
        int k = ringSize(bufferM);
        Set<Long> cover = new HashSet<>();
        for (long cell : sampled) {
            cover.addAll(H3.gridDisk(cell, k));
        }
        return cover;
    }
}
