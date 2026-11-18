package dev.hexpath.geo.api;

import dev.hexpath.geo.dataset.DatasetSnapshot;
import dev.hexpath.geo.dataset.SnapshotHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter on every request. It
 * <ol>
 *   <li>assigns X-Request-Id (echoing a safe caller value, otherwise generating one),</li>
 *   <li>pins the current dataset snapshot for the whole request and announces it as X-Dataset-Release,</li>
 *   <li>sets both headers before anything else runs, so error responses carry them too,</li>
 *   <li>rejects API calls while no release is loaded, and bodies over 256 KB.</li>
 * </ol>
 */
@Component("hexpathRequestContextFilter")  // "requestContextFilter" is taken by Spring MVC
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String SNAPSHOT_ATTRIBUTE = "hexpath.snapshot";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String DATASET_RELEASE = "X-Dataset-Release";
    static final long MAX_BODY_BYTES = 256 * 1024;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final SnapshotHolder holder;

    public RequestContextFilter(SnapshotHolder holder) {
        this.holder = holder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(REQUEST_ID);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        Optional<DatasetSnapshot> snapshot = holder.current();
        res.setHeader(REQUEST_ID, requestId);
        MDC.put("requestId", requestId);
        snapshot.ifPresent(s -> {
            req.setAttribute(SNAPSHOT_ATTRIBUTE, s);
            res.setHeader(DATASET_RELEASE, Long.toString(s.releaseId()));
            MDC.put("datasetRelease", Long.toString(s.releaseId()));
        });
        try {
            boolean api = req.getRequestURI().startsWith("/v1/");
            if (api && snapshot.isEmpty()) {
                writeProblem(res, req, ProblemType.SERVICE_NOT_READY, "the service has not loaded a dataset release yet; retry shortly");
            } else if (req.getContentLengthLong() > MAX_BODY_BYTES) {
                writeProblem(res, req, ProblemType.PAYLOAD_TOO_LARGE, "request body exceeds " + MAX_BODY_BYTES + " bytes");
            } else {
                chain.doFilter(req, res);
            }
        } finally {
            MDC.remove("requestId");
            MDC.remove("datasetRelease");
        }
    }

    /** Written by hand: filters run before Spring MVC's message converters. */
    static void writeProblem(HttpServletResponse res, HttpServletRequest req, ProblemType type, String detail)
            throws IOException {
        res.setStatus(type.status());
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"type\":\"" + type.uri() + "\",\"title\":" + json(type.title()) + ",\"status\":"
                + type.status() + ",\"detail\":" + json(detail) + ",\"instance\":" + json(req.getRequestURI()) + "}");
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
