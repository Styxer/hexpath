package dev.hexpath.geo.api;

import java.net.URI;

public enum ProblemType {
    INVALID_PARAMETER(400, "invalid-parameter", "Invalid parameter"),
    OUT_OF_COVERAGE(422, "out-of-coverage", "Outside the coverage area"),
    ROUTE_TOO_LONG(422, "route-too-long", "Route too long"),
    PAYLOAD_TOO_LARGE(413, "payload-too-large", "Request body too large"),
    NOT_FOUND(404, "not-found", "Not found"),
    SERVICE_NOT_READY(503, "service-not-ready", "No dataset release loaded yet");

    private final int status;
    private final String slug;
    private final String title;

    ProblemType(int status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public int status() { return status; }
    public String slug() { return slug; }
    public String title() { return title; }
    public URI uri() { return URI.create("urn:hexpath:problem:" + slug); }
}
