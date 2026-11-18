package dev.hexpath.geo.dataset;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatasetController {

    static final String COUNTS_SQL = """
            SELECT c.key, c.value::bigint
              FROM dataset_release r, jsonb_each_text(r.stats -> 'counts') c
             WHERE r.id = ?
            """;

    private final JdbcTemplate jdbc;

    public DatasetController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DatasetResponse(long release, Instant sourceTimestamp, Instant publishedAt, Long dataAgeSeconds,
                                  List<Double> coverage, Map<String, Long> counts) {
    }

    @GetMapping("/v1/dataset")
    public DatasetResponse dataset(DatasetSnapshot s) {
        Map<String, Long> counts = new TreeMap<>();
        jdbc.query(COUNTS_SQL, rs -> {
            counts.put(rs.getString(1), rs.getLong(2));
        }, s.releaseId());
        var c = s.coverage();
        Long age = s.sourceTimestamp() == null ? null : Duration.between(s.sourceTimestamp(), Instant.now()).toSeconds();
        return new DatasetResponse(s.releaseId(), s.sourceTimestamp(), s.publishedAt(), age,
                List.of(c.getMinX(), c.getMinY(), c.getMaxX(), c.getMaxY()), counts);
    }
}
