package dev.hexpath.geo.corridor;

import dev.hexpath.geo.places.Place;
import java.util.List;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Exact answer in one indexed query: ST_DWithin on geography, served by the geography GiST index. */
@Component
public class PostgisCorridorSearch implements CorridorSearch {

    static final String SQL = """
            SELECT p.id, p.name, p.name_he, p.name_en, p.category, ST_Y(p.geom) AS lat, ST_X(p.geom) AS lon,
                   ST_Distance(p.geom::geography, ST_GeomFromText(:route, 4326)::geography) AS distance_m,
                   ST_LineLocatePoint(ST_GeomFromText(:route, 4326), p.geom)
                     * ST_Length(ST_GeomFromText(:route, 4326)::geography) AS along_m,
                   count(*) OVER () AS candidates
              FROM place p
             WHERE p.release_id = :release
               AND ST_DWithin(p.geom::geography, ST_GeomFromText(:route, 4326)::geography, :buffer)
               AND (CAST(:category AS text) IS NULL OR p.category = :category)
             ORDER BY distance_m, p.id
             LIMIT :limit
            """;

    static final RowMapper<CorridorHit> HIT = (rs, i) -> new CorridorHit(
            new Place(rs.getString("id"), rs.getString("name"), rs.getString("name_he"), rs.getString("name_en"),
                    rs.getString("category"), rs.getDouble("lat"), rs.getDouble("lon")),
            rs.getDouble("distance_m"), rs.getDouble("along_m"));

    private final NamedParameterJdbcTemplate jdbc;

    public PostgisCorridorSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "postgis";
    }

    static MapSqlParameterSource params(long releaseId, LineString route, double bufferM, String category, int limit) {
        return new MapSqlParameterSource()
                .addValue("release", releaseId)
                .addValue("route", new WKTWriter().write(route))
                .addValue("buffer", bufferM)
                .addValue("category", category)
                .addValue("limit", limit);
    }

    @Override
    public CorridorResult search(long releaseId, LineString route, double bufferM, String category, int limit) {
        int[] candidates = {0};
        List<CorridorHit> hits = jdbc.query(SQL, params(releaseId, route, bufferM, category, limit), (rs, i) -> {
            candidates[0] = rs.getInt("candidates");
            return HIT.mapRow(rs, i);
        });
        return new CorridorResult(hits, candidates[0]);
    }
}
