package dev.hexpath.geo.contract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hexpath.geo.support.AbstractApiIT;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;

/**
 * api/openapi.yaml is the published contract. The build fails on any breaking change, and on any
 * change at all until the committed file is updated, so every API change is deliberate and reviewed.
 */
class OpenApiContractIT extends AbstractApiIT {

    static final Path CONTRACT = Path.of("../api/openapi.yaml");

    @Test
    void generatedSpecMatchesCommittedContract() throws Exception {
        var r = http.get("/v3/api-docs.yaml");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        String generated = r.body();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi-generated.yaml"), generated, StandardCharsets.UTF_8);

        assertThat(CONTRACT).as("committed contract api/openapi.yaml (copy target/openapi-generated.yaml after review)")
                .exists();
        ChangedOpenApi diff = OpenApiCompare.fromContents(Files.readString(CONTRACT), generated);
        assertThat(diff.isIncompatible()).as("breaking change against api/openapi.yaml:%n%s", describe(diff)).isFalse();
        assertThat(diff.isUnchanged()).as("API changed; review and update api/openapi.yaml deliberately:%n%s",
                describe(diff)).isTrue();
    }

    static String describe(ChangedOpenApi d) {
        return Stream.of(
                d.getNewEndpoints().stream().map(e -> "+ " + e.getMethod() + " " + e.getPathUrl()),
                d.getMissingEndpoints().stream().map(e -> "- " + e.getMethod() + " " + e.getPathUrl()),
                d.getChangedOperations().stream().map(o -> "~ " + o.getHttpMethod() + " " + o.getPathUrl()))
                .flatMap(s -> s).collect(Collectors.joining("\n"));
    }
}
