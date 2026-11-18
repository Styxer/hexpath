package dev.hexpath.geo.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Minimal HTTP client for tests: the real wire behaviour, with no framework test client in between. */
public final class Http {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;

    public Http(int port) {
        this.base = "http://localhost:" + port;
    }

    public record Response(int status, Map<String, java.util.List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    public Response get(String path, String... headerPairs) {
        var b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        for (int i = 0; i < headerPairs.length; i += 2) {
            b.header(headerPairs[i], headerPairs[i + 1]);
        }
        return send(b.build());
    }

    public Response postJson(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private Response send(HttpRequest request) {
        try {
            var r = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.headers().map(), r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
