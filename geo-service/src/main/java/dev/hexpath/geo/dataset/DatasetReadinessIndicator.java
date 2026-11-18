package dev.hexpath.geo.dataset;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness stays DOWN until a release snapshot is loaded: no traffic before there is data. */
@Component("datasetHealthIndicator")
public class DatasetReadinessIndicator implements HealthIndicator {

    private final SnapshotHolder holder;

    public DatasetReadinessIndicator(SnapshotHolder holder) {
        this.holder = holder;
    }

    @Override
    public Health health() {
        return holder.current()
                .map(s -> Health.up().withDetail("release", s.releaseId()).build())
                .orElseGet(() -> Health.down().withDetail("reason", "no active release loaded yet").build());
    }
}
