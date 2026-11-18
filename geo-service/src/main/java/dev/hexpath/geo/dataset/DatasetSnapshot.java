package dev.hexpath.geo.dataset;

import java.time.Instant;
import org.locationtech.jts.geom.Envelope;

/**
 * Immutable view of the ACTIVE release. A request reads it once and uses its releaseId for
 * every query, so one request never mixes data from two releases.
 */
public record DatasetSnapshot(long releaseId, Envelope coverage, Instant sourceTimestamp,
                              Instant publishedAt, long lastEventId) {
}
