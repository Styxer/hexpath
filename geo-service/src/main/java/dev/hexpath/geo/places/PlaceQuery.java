package dev.hexpath.geo.places;

/** A validated search. qNorm is already normalised and non-empty, or null; lat/lon are both set or both null. */
public record PlaceQuery(long releaseId, String qNorm, Double lat, Double lon, int radiusM, String category,
                         int limit, SearchCursor after) {
}
