package dev.hexpath.geo.corridor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * The H3 strategy is only correct if its cell cover never misses a place inside the buffer.
 * Each try builds a random route in the coverage area, a random buffer of 10-2000 m and a random
 * point inside that buffer, then checks the point's cell is in the cover.
 */
class H3CoverPropertyTest {

    static final GeometryFactory GF = new GeometryFactory();
    static final double M_PER_DEG_LAT = 111_320;

    @Property(tries = 1000)
    void coverIsSupersetOfExactBuffer(@ForAll long seed) {
        Random rnd = new Random(seed);
        int n = 2 + rnd.nextInt(5);
        Coordinate[] pts = new Coordinate[n];
        for (int i = 0; i < n; i++) {
            pts[i] = new Coordinate(34.765 + rnd.nextDouble() * 0.03, 32.06 + rnd.nextDouble() * 0.03);
        }
        LineString route = GF.createLineString(pts);
        double buffer = 10 + rnd.nextDouble() * 1990;

        int seg = rnd.nextInt(n - 1);
        double t = rnd.nextDouble();
        double qLon = pts[seg].x + t * (pts[seg + 1].x - pts[seg].x);
        double qLat = pts[seg].y + t * (pts[seg + 1].y - pts[seg].y);
        double r = buffer * rnd.nextDouble() * 0.99;        // stay strictly inside the buffer
        double bearing = rnd.nextDouble() * 2 * Math.PI;
        double pLat = qLat + r * Math.cos(bearing) / M_PER_DEG_LAT;
        double pLon = qLon + r * Math.sin(bearing) / (M_PER_DEG_LAT * Math.cos(Math.toRadians(qLat)));

        assertThat(H3Cover.cover(route, buffer)).contains(H3Cover.cellOf(pLat, pLon));
    }
}
