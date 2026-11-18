package dev.hexpath.geo.places;

/** A search result: score is null without a text query, distanceM is null without a location. */
public record PlaceHit(Place place, Double score, Double distanceM) {
}
