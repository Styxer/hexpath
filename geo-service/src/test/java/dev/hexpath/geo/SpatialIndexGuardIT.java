package dev.hexpath.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.hexpath.geo.boundaries.BoundaryRepository;
import dev.hexpath.geo.corridor.H3CorridorSearch;
import dev.hexpath.geo.corridor.PostgisCorridorSearch;
import dev.hexpath.geo.places.PlaceRepository;
import dev.hexpath.geo.support.PostgisContainerSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Every spatial query the service runs must be able to use the index written for it. Index usage
 * is a cost decision, so the check runs on a realistically sized release (20,000 places, 5,000
 * boundaries, analysed), with sequential scans disabled, and inspects EXPLAIN (FORMAT JSON).
 * Partition index names are generated, so indexes are identified by their definition.
 */
class SpatialIndexGuardIT extends PostgisContainerSupport {

    static final String GEOGRAPHY_GIST = "USING gist (((geom)::geography))";
    static final String ROUTE = "LINESTRING(34.774 32.07,34.774 32.085)";
    static long release;

    @BeforeAll
    static void loadRealisticRelease() throws SQLException {
        exec("owner", "INSERT INTO dataset_release (input_hash, source, status) VALUES ('index-guard', 'test', 'STAGED')");
        try (Connection c = connect("owner"); var st = c.createStatement();
             var rs = st.executeQuery("SELECT id FROM dataset_release WHERE input_hash = 'index-guard'")) {
            rs.next();
            release = rs.getLong(1);
        }
        exec("owner", "SELECT hexpath_create_partitions(" + release + ");"
                + "INSERT INTO place (release_id, id, name, name_norm, category, geom, h3_r9)"
                + " SELECT " + release + ", 'g' || i, 'Place ' || i, 'place ' || i, 'cafe',"
                + "  ST_SetSRID(ST_MakePoint(34.765 + random() * 0.03, 32.06 + random() * 0.03), 4326), i"
                + " FROM generate_series(1, 20000) i;"
                + "INSERT INTO boundary (release_id, id, name, type, origin, geom)"
                + " SELECT " + release + ", 'b' || i, 'B ' || i, 'ADMIN', 'synthetic',"
                + "  ST_Multi(ST_MakeEnvelope(x, y, x + 0.0005, y + 0.0005, 4326))"
                + " FROM (SELECT i, 34.765 + random() * 0.03 AS x, 32.06 + random() * 0.03 AS y"
                + "       FROM generate_series(1, 5000) i) g;"
                + "ANALYZE place; ANALYZE boundary;");
    }

    @AfterAll
    static void dropRealisticRelease() throws SQLException {
        exec("owner", "SELECT hexpath_drop_partitions(" + release + "); DELETE FROM dataset_release WHERE id = " + release);
    }

    /** Index definitions used by the plan of sql, with sequential scans disabled. */
    static List<String> indexesUsed(String sql, MapSqlParameterSource params) throws SQLException {
        try (Connection c = connect("reader")) {
            try (var st = c.createStatement()) {
                st.execute("SET enable_seqscan = off");
                st.execute("SET pg_trgm.word_similarity_threshold = 0.5");
            }
            var jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(c, true));
            String plan = jdbc.queryForObject("EXPLAIN (FORMAT JSON) " + sql, params, String.class);
            List<String> names = JsonPath.read(plan, "$..['Index Name']");
            return names.stream().map(n -> jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = :n",
                    new MapSqlParameterSource("n", n), String.class)).toList();
        }
    }

    static MapSqlParameterSource base() {
        return new MapSqlParameterSource().addValue("release", release).addValue("limit", 20)
                .addValue("lat", 32.0779).addValue("lon", 34.7740).addValue("radius", 1000)
                .addValue("q", "dizengof").addValue("category", null).addValue("type", null)
                .addValue("route", ROUTE).addValue("buffer", 300.0);
    }

    @Test
    void placeRadiusSearchUsesGeographyGist() throws SQLException {
        assertThat(indexesUsed(PlaceRepository.searchSql(false, true, false, false), base()))
                .anySatisfy(d -> assertThat(d).contains("place_r").contains(GEOGRAPHY_GIST));
    }

    @Test
    void placeTextSearchUsesTrigramGin() throws SQLException {
        assertThat(indexesUsed(PlaceRepository.searchSql(true, false, false, false), base()))
                .anySatisfy(d -> assertThat(d).contains("place_r").contains("gin_trgm_ops"));
    }

    @Test
    void boundaryContainingUsesGeometryGist() throws SQLException {
        assertThat(indexesUsed(BoundaryRepository.CONTAINING_SQL, base()))
                .anySatisfy(d -> assertThat(d).contains("boundary_r").contains("USING gist (geom)"));
    }

    @Test
    void postgisCorridorUsesGeographyGist() throws SQLException {
        assertThat(indexesUsed(PostgisCorridorSearch.SQL, base()))
                .anySatisfy(d -> assertThat(d).contains("place_r").contains(GEOGRAPHY_GIST));
    }

    @Test
    void h3CorridorUsesCellBtree() throws SQLException {
        var params = base().addValue("cells", H3CorridorSearch.cellsLiteral(List.of(5L, 6L, 7L)));
        assertThat(indexesUsed(H3CorridorSearch.SQL, params))
                .anySatisfy(d -> assertThat(d).contains("place_r").contains("USING btree (h3_r9)"));
    }

    /** Negative control: a distance filter the index cannot serve must fail the same check. */
    @Test
    void guardDetectsANonSargableQuery() throws SQLException {
        String bad = "SELECT p.id FROM place p WHERE p.release_id = :release AND "
                + "ST_Distance(p.geom::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) <= :radius";
        assertThat(indexesUsed(bad, base())).noneSatisfy(d -> assertThat(d).contains(GEOGRAPHY_GIST));
    }
}
