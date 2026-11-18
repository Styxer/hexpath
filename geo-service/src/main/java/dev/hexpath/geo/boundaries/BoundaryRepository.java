package dev.hexpath.geo.boundaries;

import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BoundaryRepository {

    static final String CONTAINING_SQL = """
            SELECT b.id, b.name, b.type, b.origin
              FROM boundary b
             WHERE b.release_id = :release
               AND ST_Contains(b.geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))
               AND (CAST(:type AS text) IS NULL OR b.type = :type)
             ORDER BY b.type, b.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BoundaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Boundary> containing(long releaseId, double lat, double lon, BoundaryType typeOrNull) {
        var params = new MapSqlParameterSource()
                .addValue("release", releaseId).addValue("lat", lat).addValue("lon", lon)
                .addValue("type", typeOrNull == null ? null : typeOrNull.name());
        return jdbc.query(CONTAINING_SQL, params, (rs, i) -> new Boundary(rs.getString("id"), rs.getString("name"),
                BoundaryType.valueOf(rs.getString("type")), rs.getString("origin")));
    }
}
