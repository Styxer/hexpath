package dev.hexpath.geo.dataset;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service's own view of its health against the SLOs. It cannot report its own availability
 * (a service that is down answers nothing), so that SLI is measured externally; see docs/slo.md.
 */
@RestController
public class StatusController {

    static final String NOTE = "Service's own view. Availability is measured externally; see docs/slo.md.";
    private final MeterRegistry registry;

    public StatusController(MeterRegistry registry) {
        this.registry = registry;
    }

    public record EndpointStatus(double p95Ms, long requests, double errorRatioSinceStart) {
    }

    public record StatusResponse(long release, Long dataAgeSeconds, Map<String, EndpointStatus> endpoints, String note) {
    }

    @GetMapping("/v1/status")
    public StatusResponse status(DatasetSnapshot s) {
        Map<String, long[]> counts = new TreeMap<>();     // uri -> {requests, server errors}
        Map<String, Double> p95 = new TreeMap<>();
        for (Timer t : registry.find("http.server.requests").timers()) {
            String uri = t.getId().getTag("uri");
            if (uri == null || !uri.startsWith("/v1/")) {
                continue;
            }
            long[] c = counts.computeIfAbsent(uri, k -> new long[2]);
            c[0] += t.count();
            if ("SERVER_ERROR".equals(t.getId().getTag("outcome"))) {
                c[1] += t.count();
            }
            for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
                if (v.percentile() == 0.95) {
                    // Timers are split by status and outcome; the slowest p95 is the conservative figure.
                    p95.merge(uri, v.value(TimeUnit.MILLISECONDS), Math::max);
                }
            }
        }
        Map<String, EndpointStatus> endpoints = new TreeMap<>();
        counts.forEach((uri, c) -> endpoints.put(uri,
                new EndpointStatus(p95.getOrDefault(uri, 0.0), c[0], c[0] == 0 ? 0 : (double) c[1] / c[0])));
        Long age = s.sourceTimestamp() == null ? null : Duration.between(s.sourceTimestamp(), Instant.now()).toSeconds();
        return new StatusResponse(s.releaseId(), age, endpoints, NOTE);
    }
}
