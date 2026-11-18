package dev.hexpath.geo.dataset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hexpath.geo.HexpathApplication;
import dev.hexpath.geo.support.PostgisContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

class SchemaVersionCheckIT extends PostgisContainerSupport {

    @Test
    void startupFailsOnSchemaMismatch() throws Exception {
        exec("owner", "UPDATE schema_version SET version = 3");
        try {
            // Command-line arguments outrank application.yml; builder .properties() would not.
            assertThatThrownBy(() -> new SpringApplicationBuilder(HexpathApplication.class)
                    .run("--spring.datasource.url=" + jdbcUrl(),
                            "--spring.datasource.username=geo_reader",
                            "--spring.datasource.password=reader-dev",
                            "--server.port=0")
                    .close())
                    .hasStackTraceContaining("expected 4");
        } finally {
            exec("owner", "UPDATE schema_version SET version = 4");
        }
    }
}
