package dev.hexpath.geo.places;

import dev.hexpath.geo.api.ApiProblemException;
import dev.hexpath.geo.api.ProblemType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Keyset position: the sort key of the last row returned (score DESC, distance ASC, id ASC),
 * tied to the release it came from. Doubles are written with Double.toString, which round-trips exactly.
 */
public record SearchCursor(long releaseId, double score, double distanceM, String id) {

    private static final String VERSION = "v1";

    public String encode() {
        String raw = String.join("|", VERSION, Long.toString(releaseId), Double.toString(score),
                Double.toString(distanceM), id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static SearchCursor decode(String token, long expectedRelease) {
        SearchCursor c;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] p = raw.split("\\|", 5);
            if (p.length != 5 || !VERSION.equals(p[0]) || p[4].isEmpty()) {
                throw new IllegalArgumentException(raw);
            }
            c = new SearchCursor(Long.parseLong(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), p[4]);
        } catch (IllegalArgumentException e) {   // includes NumberFormatException and bad base64
            throw new ApiProblemException(ProblemType.INVALID_PARAMETER, "malformed cursor");
        }
        if (c.releaseId != expectedRelease) {
            throw new ApiProblemException(ProblemType.INVALID_PARAMETER, "cursor belongs to release " + c.releaseId
                    + ", current is " + expectedRelease + "; restart the search");
        }
        return c;
    }
}
