package dev.hexpath.geo.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error leaves as an RFC 9457 problem with a urn:hexpath:problem:* type. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> api(ApiProblemException e, HttpServletRequest req) {
        return problem(e.type(), e.getMessage(), req);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> badInput(Exception e, HttpServletRequest req) {
        String detail = e instanceof MethodArgumentTypeMismatchException m
                ? "parameter '" + m.getName() + "' has an invalid value"
                : "request body is not valid JSON for this endpoint";
        return problem(ProblemType.INVALID_PARAMETER, detail, req);
    }

    /** Spring MVC's own errors (unknown path, missing parameter, wrong method, ...). */
    private ResponseEntity<ProblemDetail> framework(ErrorResponse er, HttpServletRequest req) {
        HttpStatusCode status = er.getStatusCode();
        if (status.value() == 404) {
            return problem(ProblemType.NOT_FOUND, "no such resource: " + req.getRequestURI(), req);
        }
        if (status.value() == 400) {
            return problem(ProblemType.INVALID_PARAMETER, er.getBody().getDetail(), req);
        }
        ProblemDetail body = er.getBody();
        body.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(status).headers(er.getHeaders()).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
        if (e instanceof ErrorResponse er) {   // ErrorResponse is an interface, so it cannot be a handler type
            return framework(er, req);
        }
        log.error("unhandled error on {}", req.getRequestURI(), e);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(500),
                "unexpected error; quote the X-Request-Id header when reporting it");
        p.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(500).body(p);
    }

    static ResponseEntity<ProblemDetail> problem(ProblemType type, String detail, HttpServletRequest req) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(type.status()), detail);
        p.setType(type.uri());
        p.setTitle(type.title());
        p.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(type.status()).body(p);
    }
}
