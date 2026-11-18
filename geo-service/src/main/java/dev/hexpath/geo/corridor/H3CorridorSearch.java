package dev.hexpath.geo.corridor;

import java.util.List;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.LineString;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Candidate lookup by H3 cell (btree on place.h3_r9), then the same exact distance filter as the
 * PostGIS strategy. This is the shape that works when the candidate store is keyed by cell and has
 * no geometry index (a key-value store or search engine); ADR 0003 records how it compares here.
 */
@Component
public class H3CorridorSearch implements CorridorSearch {

    public static final String SQL = """
            WITH cand AS (
                SELECT p.id, p.name, p.name_he, p.name_en, p.category, p.geom
                  FROM place p
                 WHERE p.release_id = :release
                   AND p.h3_r9 = ANY(CAST(:cells AS bigint[]))
                   AND (CAST(:category AS text) IS NULL OR p.category = :category)
            ), measured AS (
                SELECT c.*, ST_Distance(c.geom::geography, ST_GeomFromText(:route, 4326)::geography) AS distance_m,
                       count(*) OVER () AS candidates
                  FROM cand c
            )
            SELECT m.id, m.name, m.name_he, m.name_en, m.category, ST_Y(m.geom) AS lat, ST_X(m.geom) AS lon,
                   m.distance_m,
                   ST_LineLocatePoint(ST_GeomFromText(:route, 4326), m.geom)
                     * ST_Length(ST_GeomFromText(:route, 4326)::geography) AS along_m,
                   m.candidates
              FROM measured m
             WHERE m.distance_m <= :buffer
             ORDER BY m.distance_m, m.id
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public H3CorridorSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "h3";
    }

    public static String cellsLiteral(java.util.Collection<Long> cells) {
        return cells.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    @Override
    public CorridorResult search(long releaseId, LineString route, double bufferM, String category, int limit) {
        var params = PostgisCorridorSearch.params(releaseId, route, bufferM, category, limit)
                .addValue("cells", cellsLiteral(H3Cover.cover(route, bufferM)));
        int[] candidates = {0};
        List<CorridorHit> hits = jdbc.query(SQL, params, (rs, i) -> {
            candidates[0] = rs.getInt("candidates");
            return PostgisCorridorSearch.HIT.mapRow(rs, i);
        });
        return new CorridorResult(hits, candidates[0]);
    }
}
