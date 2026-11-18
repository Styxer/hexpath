package dev.hexpath.geo.dataset;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Holds the current snapshot. Swaps are atomic; readers never see a half-built snapshot. */
@Component
public class SnapshotHolder {

    private final AtomicReference<DatasetSnapshot> ref = new AtomicReference<>();
    private final Counter swaps;

    public SnapshotHolder(MeterRegistry registry) {
        this.swaps = Counter.builder("hexpath.snapshot.swaps")
                .description("Times the service switched to a new active release").register(registry);
        Gauge.builder("hexpath.dataset.age.seconds", this, SnapshotHolder::dataAgeSeconds)
                .description("Age of the source data of the active release").register(registry);
    }

    public Optional<DatasetSnapshot> current() {
        return Optional.ofNullable(ref.get());
    }

    public void swap(DatasetSnapshot next) {
        ref.set(next);
        swaps.increment();
    }

    double dataAgeSeconds() {
        DatasetSnapshot s = ref.get();
        if (s == null || s.sourceTimestamp() == null) {
            return Double.NaN;
        }
        return Duration.between(s.sourceTimestamp(), Instant.now()).toSeconds();
    }
}
