package dev.hexpath.geo.corridor;

import dev.hexpath.geo.places.Place;

/** distanceM: metres from the route line; alongM: metres from the route start to the closest point on it. */
public record CorridorHit(Place place, double distanceM, double alongM) {
}
