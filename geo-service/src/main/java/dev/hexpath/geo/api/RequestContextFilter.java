package dev.hexpath.geo.api;

import dev.hexpath.geo.dataset.SnapshotHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestContextFilter extends OncePerRequestFilter {
    public static final String SNAPSHOT_ATTRIBUTE = "hexpath.snapshot";

    public RequestContextFilter(SnapshotHolder holder) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(req, res);
    }
}
