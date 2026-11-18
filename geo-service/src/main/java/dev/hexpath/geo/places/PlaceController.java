package dev.hexpath.geo.places;

import dev.hexpath.geo.api.ApiProblemException;
import dev.hexpath.geo.api.CoverageGuard;
import dev.hexpath.geo.api.ProblemType;
import dev.hexpath.geo.dataset.DatasetSnapshot;
import dev.hexpath.geo.text.TextNormalizer;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceController {

    static final int MAX_LIMIT = 100;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_RADIUS_M = 5000;
    static final int DEFAULT_RADIUS_M = 1000;

    private final PlaceRepository places;

    public PlaceController(PlaceRepository places) {
        this.places = places;
    }

    public record SearchResponse(List<PlaceJson> results, String nextCursor) {
    }

    @GetMapping("/v1/places/search")
    public SearchResponse search(DatasetSnapshot snapshot,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(required = false) Double lat,
                                 @RequestParam(required = false) Double lon,
                                 @RequestParam(name = "radius_m", required = false) Integer radiusM,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) String cursor) {
        String qNorm = null;
        if (q != null) {
            qNorm = TextNormalizer.normalize(q);
            if (qNorm.isEmpty()) {
                throw invalid("q is empty after normalisation (street-type words and punctuation are ignored)");
            }
        }
        if ((lat == null) != (lon == null)) {
            throw invalid("lat and lon must be given together");
        }
        if (qNorm == null && lat == null) {
            throw invalid("provide q, or lat and lon, or both");
        }
        if (lat != null) {
            CoverageGuard.requireInside(snapshot, lat, lon);
        }
        int r = radiusM == null ? DEFAULT_RADIUS_M : radiusM;
        if (r < 1 || r > MAX_RADIUS_M) {
            throw invalid("radius_m must be between 1 and " + MAX_RADIUS_M);
        }
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_LIMIT);
        }
        SearchCursor after = cursor == null ? null : SearchCursor.decode(cursor, snapshot.releaseId());

        List<PlaceHit> hits = places.search(new PlaceQuery(snapshot.releaseId(), qNorm, lat, lon, r,
                category == null || category.isBlank() ? null : category, n, after));
        String next = null;
        if (hits.size() > n) {
            hits = hits.subList(0, n);
            PlaceHit last = hits.getLast();
            next = new SearchCursor(snapshot.releaseId(), orZero(last.score()), orZero(last.distanceM()),
                    last.place().id()).encode();
        }
        return new SearchResponse(hits.stream().map(h -> PlaceJson.of(h.place(), h.score(), h.distanceM())).toList(), next);
    }

    @GetMapping("/v1/places/{id}")
    public PlaceJson place(DatasetSnapshot snapshot, @PathVariable String id) {
        return places.find(snapshot.releaseId(), id)
                .map(p -> PlaceJson.of(p, null, null))
                .orElseThrow(() -> new ApiProblemException(ProblemType.NOT_FOUND,
                        "no place " + id + " in release " + snapshot.releaseId()));
    }

    private static double orZero(Double d) {
        return d == null ? 0 : d;
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(ProblemType.INVALID_PARAMETER, detail);
    }
}
