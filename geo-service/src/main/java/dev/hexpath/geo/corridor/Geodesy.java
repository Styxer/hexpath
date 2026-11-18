package dev.hexpath.geo.corridor;

/** Haversine on the mean Earth radius: under 0.5% error at city scale, same constant as the pipeline. */
final class Geodesy {
    static final double EARTH_RADIUS_M = 6_371_008.8;

    private Geodesy() {
    }

    static double haversineM(double lon1, double lat1, double lon2, double lat2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dLat = p2 - p1;
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}
