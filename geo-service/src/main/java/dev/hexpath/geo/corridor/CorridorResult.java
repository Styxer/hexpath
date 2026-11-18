package dev.hexpath.geo.corridor;

import java.util.List;

/** candidates: rows considered before the exact distance filter (equals hits for PostGIS). */
public record CorridorResult(List<CorridorHit> hits, int candidates) {
}
