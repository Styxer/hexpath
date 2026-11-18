package dev.hexpath.geo.places;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Place queries. The SQL is assembled from fixed fragments (never from user text), so each
 * combination is still a plain, explainable statement whose predicates match the indexes:
 * trigram GIN for text (word similarity, operator <%), geography GiST for distance.
 */
@Repository
public class PlaceRepository {

    static final String COLUMNS = """
            SELECT p.id, p.name, p.name_he, p.name_en, p.category, ST_Y(p.geom) AS lat, ST_X(p.geom) AS lon""";
    static final String SCORE = """
            GREATEST(word_similarity(:q, coalesce(p.name_norm, '')),
                     word_similarity(:q, coalesce(p.name_he_norm, '')),
                     word_similarity(:q, coalesce(p.name_en_norm, '')))::float8""";
    static final String DISTANCE = "ST_Distance(p.geom::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography)";
    static final String TEXT_MATCH = "(:q <% p.name_norm OR :q <% p.name_he_norm OR :q <% p.name_en_norm)";
    static final String NEAR = "ST_DWithin(p.geom::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius)";
    static final String FIND_SQL = COLUMNS + " FROM place p WHERE p.release_id = :release AND p.id = :id";

    private static final RowMapper<Place> PLACE = (rs, i) -> new Place(rs.getString("id"), rs.getString("name"),
            rs.getString("name_he"), rs.getString("name_en"), rs.getString("category"), rs.getDouble("lat"),
            rs.getDouble("lon"));

    private final NamedParameterJdbcTemplate jdbc;

    public PlaceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The statement for one combination of filters; exposed so the index guard test explains exactly this. */
    static String searchSql(boolean text, boolean near, boolean category, boolean after) {
        String score = text ? SCORE : "0::float8";
        String distance = near ? DISTANCE : "0::float8";
        StringBuilder where = new StringBuilder("p.release_id = :release");
        if (text) {
            where.append(" AND ").append(TEXT_MATCH);
        }
        if (near) {
            where.append(" AND ").append(NEAR);
        }
        if (category) {
            where.append(" AND p.category = :category");
        }
        String keyset = after
                ? " WHERE score < :a_score OR (score = :a_score AND (distance_m > :a_dist OR (distance_m = :a_dist AND id > :a_id)))"
                : "";
        return "SELECT * FROM (" + COLUMNS + ", " + score + " AS score, " + distance + " AS distance_m FROM place p WHERE "
                + where + ") hits" + keyset + " ORDER BY score DESC, distance_m ASC, id ASC LIMIT :limit";
    }

    /** Returns up to limit + 1 hits, so the caller can tell whether another page exists. */
    public List<PlaceHit> search(PlaceQuery q) {
        boolean text = q.qNorm() != null;
        boolean near = q.lat() != null;
        var params = new MapSqlParameterSource()
                .addValue("release", q.releaseId())
                .addValue("q", q.qNorm())
                .addValue("lat", q.lat())
                .addValue("lon", q.lon())
                .addValue("radius", q.radiusM())
                .addValue("category", q.category())
                .addValue("limit", q.limit() + 1);
        if (q.after() != null) {
            params.addValue("a_score", q.after().score())
                    .addValue("a_dist", q.after().distanceM())
                    .addValue("a_id", q.after().id());
        }
        String sql = searchSql(text, near, q.category() != null, q.after() != null);
        return jdbc.query(sql, params, (rs, i) -> new PlaceHit(PLACE.mapRow(rs, i),
                text ? rs.getDouble("score") : null,
                near ? rs.getDouble("distance_m") : null));
    }

    public Optional<Place> find(long releaseId, String id) {
        return jdbc.query(FIND_SQL, new MapSqlParameterSource().addValue("release", releaseId).addValue("id", id), PLACE)
                .stream().findFirst();
    }
}
