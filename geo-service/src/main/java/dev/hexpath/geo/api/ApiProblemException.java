package dev.hexpath.geo.api;

/** Thrown anywhere in request handling; rendered as an RFC 9457 problem response. */
public class ApiProblemException extends RuntimeException {
    private final ProblemType type;

    public ApiProblemException(ProblemType type, String detail) {
        super(detail);
        this.type = type;
    }

    public ProblemType type() {
        return type;
    }
}
