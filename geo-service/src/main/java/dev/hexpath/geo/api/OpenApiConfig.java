package dev.hexpath.geo.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.hexpath.geo.dataset.DatasetSnapshot;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published contract (api/openapi.yaml) is generated from the code. This adds what annotations
 * would repeat on every endpoint: the problem responses and the two headers every response carries.
 */
@Configuration
public class OpenApiConfig {

    static {
        // Controllers receive the pinned snapshot as a parameter; it is not part of the HTTP API.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(DatasetSnapshot.class);
    }

    static final String PROBLEM = "Problem";

    @Bean
    OpenAPI hexpathOpenApi() {
        return new OpenAPI()
                .info(new Info().title("hexpath geo API").version("v1")
                        .description("Places, boundaries and route-aware search over the active dataset release. "
                                + "Coordinates in responses are GeoJSON [lon, lat]; query parameters are lat and lon.")
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("/")));
    }

    /**
     * The spec generator models schemas with its own (Jackson 2) mapper. Give it the same
     * snake_case naming the API actually uses, or the contract would document nameHe for name_he.
     */
    @Bean
    ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    @Bean
    OpenApiCustomizer problemsAndHeaders() {
        return api -> {
            Schema<?> problem = new ObjectSchema()
                    .addProperty("type", new StringSchema().example("urn:hexpath:problem:invalid-parameter"))
                    .addProperty("title", new StringSchema())
                    .addProperty("status", new IntegerSchema())
                    .addProperty("detail", new StringSchema())
                    .addProperty("instance", new StringSchema());
            if (api.getComponents() == null) {
                api.setComponents(new Components());
            }
            api.getComponents().addSchemas(PROBLEM, problem);
            Map<String, Header> headers = Map.of(
                    "X-Dataset-Release", new Header().description("Id of the dataset release that served the response")
                            .schema(new StringSchema()),
                    "X-Request-Id", new Header().description("Request id (echoed when the caller sends a safe one)")
                            .schema(new StringSchema()));
            api.getPaths().forEach((path, item) -> {
                for (Map.Entry<PathItem.HttpMethod, Operation> e : item.readOperationsMap().entrySet()) {
                    var responses = e.getValue().getResponses();
                    addProblem(responses, "400", "Invalid parameter (urn:hexpath:problem:invalid-parameter)");
                    addProblem(responses, "404", "Not found (urn:hexpath:problem:not-found)");
                    addProblem(responses, "422", "Outside coverage or route too long");
                    addProblem(responses, "503", "No dataset release loaded yet (urn:hexpath:problem:service-not-ready)");
                    if (e.getKey() == PathItem.HttpMethod.POST) {
                        addProblem(responses, "413", "Request body over 256 KB (urn:hexpath:problem:payload-too-large)");
                    }
                    responses.values().forEach(r -> headers.forEach(r::addHeaderObject));
                }
            });
        };
    }

    private static void addProblem(io.swagger.v3.oas.models.responses.ApiResponses responses, String code, String text) {
        responses.putIfAbsent(code, new ApiResponse().description(text).content(new Content()
                .addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)))));
    }
}
