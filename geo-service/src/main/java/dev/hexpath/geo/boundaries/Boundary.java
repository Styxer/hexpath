package dev.hexpath.geo.boundaries;

/** origin is "osm" or "synthetic"; synthetic regulatory zones are fictional and always labelled so. */
public record Boundary(String id, String name, BoundaryType type, String origin) {
}
