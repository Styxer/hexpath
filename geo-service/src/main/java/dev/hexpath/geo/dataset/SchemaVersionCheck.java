package dev.hexpath.geo.dataset;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Refuse to start against a database whose schema this build was not written for. */
@Component
public class SchemaVersionCheck implements ApplicationRunner {

    public static final int EXPECTED_SCHEMA_VERSION = 3;
    private final JdbcTemplate jdbc;

    public SchemaVersionCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer found = jdbc.queryForObject("SELECT version FROM schema_version", Integer.class);
        if (found == null || found != EXPECTED_SCHEMA_VERSION) {
            throw new IllegalStateException("schema version " + found + ", expected " + EXPECTED_SCHEMA_VERSION
                    + " — run: python -m geopipeline migrate");
        }
    }
}
