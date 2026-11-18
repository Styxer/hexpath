package dev.hexpath.geo.dataset;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.locationtech.jts.geom.Envelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads the ACTIVE release into an immutable snapshot. */
@Component
public class SnapshotLoader {

    static final String ACTIVE_SQL = """
            SELECT r.id,
                   (r.stats -> 'coverage' ->> 0)::float8 AS west,
                   (r.stats -> 'coverage' ->> 1)::float8 AS south,
                   (r.stats -> 'coverage' ->> 2)::float8 AS east,
                   (r.stats -> 'coverage' ->> 3)::float8 AS north,
                   r.source_timestamp, r.published_at,
                   (SELECT coalesce(max(e.id), 0) FROM dataset_event e) AS last_event_id
              FROM dataset_release r
             WHERE r.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final Timer timer;

    public SnapshotLoader(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.timer = Timer.builder("hexpath.snapshot.load").description("Time to load a release snapshot")
                .register(registry);
    }

    public Optional<DatasetSnapshot> loadActive() {
        return timer.record(() -> {
            List<DatasetSnapshot> rows = jdbc.query(ACTIVE_SQL, (rs, i) -> new DatasetSnapshot(
                    rs.getLong("id"),
                    new Envelope(rs.getDouble("west"), rs.getDouble("east"), rs.getDouble("south"), rs.getDouble("north")),
                    instant(rs.getTimestamp("source_timestamp")),
                    instant(rs.getTimestamp("published_at")),
                    rs.getLong("last_event_id")));
            return rows.stream().findFirst();
        });
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
