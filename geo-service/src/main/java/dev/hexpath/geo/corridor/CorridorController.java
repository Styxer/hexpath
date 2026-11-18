package dev.hexpath.geo.corridor;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.hexpath.geo.api.ApiProblemException;
import dev.hexpath.geo.api.CoverageGuard;
import dev.hexpath.geo.api.ProblemType;
import dev.hexpath.geo.dataset.DatasetSnapshot;
import dev.hexpath.geo.places.PlaceJson.GeoPoint;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CorridorController {

    static final double DEFAULT_BUFFER_M = 300;
    static final double MAX_BUFFER_M = 2000;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final Map<String, CorridorSearch> strategies;
    private final String defaultStrategy;

    public CorridorController(List<CorridorSearch> strategies,
                              @Value("${hexpath.corridor.default-strategy:postgis}") String defaultStrategy) {
        this.strategies = strategies.stream().collect(Collectors.toMap(CorridorSearch::name, Function.identity()));
        this.defaultStrategy = defaultStrategy;
    }

    public record AlongRouteRequest(RouteInput.Route route, Double bufferM, String category, Integer limit) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorridorJson(String id, String name, String nameHe, String nameEn, String category, GeoPoint location,
                               double distanceM, double alongM) {
    }

    public record Debug(int candidates) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlongRouteResponse(String strategy, List<CorridorJson> results, Debug debug) {
    }

    @PostMapping("/v1/search/along-route")
    public AlongRouteResponse along(DatasetSnapshot snapshot, @RequestBody AlongRouteRequest body,
                                    @RequestParam(required = false) String strategy,
                                    @RequestParam(defaultValue = "false") boolean debug,
                                    HttpServletResponse response) {
        LineString route = RouteInput.parse(body.route());
        for (Coordinate c : route.getCoordinates()) {
            CoverageGuard.requireInside(snapshot, c.y, c.x);
        }
        double buffer = body.bufferM() == null ? DEFAULT_BUFFER_M : body.bufferM();
        if (!(buffer > 0 && buffer <= MAX_BUFFER_M)) {
            throw invalid("buffer_m must be greater than 0 and at most " + MAX_BUFFER_M);
        }
        int limit = body.limit() == null ? DEFAULT_LIMIT : body.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_LIMIT);
        }
        CorridorSearch search = strategies.get(strategy == null ? defaultStrategy : strategy);
        if (search == null) {
            throw invalid("strategy must be one of " + strategies.keySet());
        }
        String category = body.category() == null || body.category().isBlank() ? null : body.category();

        long start = System.nanoTime();
        CorridorResult result = search.search(snapshot.releaseId(), route, buffer, category, limit);
        double ms = (System.nanoTime() - start) / 1e6;
        response.setHeader("Server-Timing", String.format(Locale.ROOT, "corridor;dur=%.1f", ms));

        List<CorridorJson> results = result.hits().stream().map(h -> new CorridorJson(h.place().id(), h.place().name(),
                h.place().nameHe(), h.place().nameEn(), h.place().category(),
                GeoPoint.of(h.place().lat(), h.place().lon()), h.distanceM(), h.alongM())).toList();
        return new AlongRouteResponse(search.name(), results, debug ? new Debug(result.candidates()) : null);
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(ProblemType.INVALID_PARAMETER, detail);
    }
}
