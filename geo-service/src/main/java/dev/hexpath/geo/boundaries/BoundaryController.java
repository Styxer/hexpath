package dev.hexpath.geo.boundaries;

import dev.hexpath.geo.api.ApiProblemException;
import dev.hexpath.geo.api.CoverageGuard;
import dev.hexpath.geo.api.ProblemType;
import dev.hexpath.geo.dataset.DatasetSnapshot;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BoundaryController {

    private final BoundaryRepository boundaries;

    public BoundaryController(BoundaryRepository boundaries) {
        this.boundaries = boundaries;
    }

    public record ContainingResponse(List<Boundary> results) {
    }

    @GetMapping("/v1/boundaries/containing")
    public ContainingResponse containing(DatasetSnapshot snapshot, @RequestParam double lat, @RequestParam double lon,
                                         @RequestParam(required = false) String type) {
        CoverageGuard.requireInside(snapshot, lat, lon);
        BoundaryType t = null;
        if (type != null) {
            try {
                t = BoundaryType.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new ApiProblemException(ProblemType.INVALID_PARAMETER,
                        "type must be one of " + Arrays.toString(BoundaryType.values()));
            }
        }
        return new ContainingResponse(boundaries.containing(snapshot.releaseId(), lat, lon, t));
    }
}
