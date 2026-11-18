package dev.hexpath.geo.places;

public record SearchCursor(long releaseId, double score, double distanceM, String id) {
    public String encode() {
        return "";
    }

    public static SearchCursor decode(String token, long expectedRelease) {
        return null;
    }
}
