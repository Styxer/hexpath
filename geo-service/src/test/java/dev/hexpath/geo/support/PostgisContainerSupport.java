package dev.hexpath.geo.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * One PostGIS container per test JVM, prepared exactly like production: db/roles.sql, then
 * db/migrations as geo_owner, then the pipeline-generated shared/seed/seed.sql as geo_writer.
 * The service under test connects as geo_reader.
 */
public abstract class PostgisContainerSupport {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @SuppressWarnings("resource")
    static final GenericContainer<?> PG = new GenericContainer<>("postgis/postgis:17-3.5")
            .withEnv("POSTGRES_DB", "hexpath")
            .withEnv("POSTGRES_PASSWORD", "postgres-dev")
            .withEnv("GEO_OWNER_PASSWORD", "owner-dev")
            .withEnv("GEO_WRITER_PASSWORD", "writer-dev")
            .withEnv("GEO_READER_PASSWORD", "reader-dev")
            .withCopyFileToContainer(MountableFile.forHostPath(REPO.resolve("db/roles.sql")), "/roles.sql")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    static {
        PG.start();
        try {
            var roles = PG.execInContainer("psql", "-q", "-v", "ON_ERROR_STOP=1", "-U", "postgres",
                    "-d", "hexpath", "-f", "/roles.sql");
            if (roles.getExitCode() != 0) {
                throw new IllegalStateException("roles.sql failed: " + roles.getStderr());
            }
            try (Stream<Path> files = Files.list(REPO.resolve("db/migrations"))) {
                for (Path f : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList()) {
                    exec("owner", Files.readString(f));
                }
            }
            exec("writer", Files.readString(REPO.resolve("shared/seed/seed.sql")));
        } catch (Exception e) {
            throw new IllegalStateException("could not prepare the PostGIS test database", e);
        }
    }

    public static String jdbcUrl() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/hexpath";
    }

    public static Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), "geo_" + role, role + "-dev");
    }

    public static void exec(String role, String sql) throws SQLException {
        try (Connection c = connect(role); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Return the database to the seed state: only release 1, ACTIVE, announced with an event. */
    public static void resetToSeed() throws SQLException {
        exec("owner", """
                UPDATE dataset_release SET status = 'RETIRED' WHERE status = 'ACTIVE';
                DO $$ DECLARE r bigint; BEGIN
                  FOR r IN SELECT id FROM dataset_release WHERE id <> 1 LOOP
                    PERFORM hexpath_drop_partitions(r);
                  END LOOP;
                END $$;
                DELETE FROM dataset_release WHERE id <> 1;
                UPDATE dataset_release SET status = 'ACTIVE', retired_at = NULL WHERE id = 1;
                INSERT INTO dataset_event (release_id, type, payload)
                  VALUES (1, 'RELEASE_ACTIVATED', '{"release_id": 1, "reason": "test reset"}');
                SELECT pg_notify('dataset_events', '1');
                """);
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgisContainerSupport::jdbcUrl);
        registry.add("spring.datasource.username", () -> "geo_reader");
        registry.add("spring.datasource.password", () -> "reader-dev");
    }

    /** Does what ReleaseStore.stage + publish do, as geo_writer, optionally without the NOTIFY. */
    public static long publishSyntheticRelease(String hash, boolean notify) throws SQLException {
        try (Connection c = connect("writer")) {
            c.setAutoCommit(false);
            long id;
            try (var st = c.prepareStatement("""
                    INSERT INTO dataset_release (input_hash, source, status, stats)
                    VALUES (?, 'it', 'STAGED', '{"coverage": [34.765, 32.06, 34.795, 32.09], "counts": {}}')
                    RETURNING id""")) {
                st.setString(1, hash);
                var rs = st.executeQuery();
                rs.next();
                id = rs.getLong(1);
            }
            try (var st = c.createStatement()) {
                st.execute("SELECT hexpath_create_partitions(" + id + ")");
                st.execute("UPDATE dataset_release SET status = 'RETIRED', retired_at = now() WHERE status = 'ACTIVE'");
                st.execute("UPDATE dataset_release SET status = 'ACTIVE', published_at = now() WHERE id = " + id);
                st.execute("INSERT INTO dataset_event (release_id, type, payload) VALUES (" + id
                        + ", 'RELEASE_ACTIVATED', '{}')");
                if (notify) {
                    st.execute("SELECT pg_notify('dataset_events', '" + id + "')");
                }
            }
            c.commit();
            return id;
        }
    }
}
