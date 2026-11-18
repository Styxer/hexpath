package dev.hexpath.geo.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hexpath.geo.api.ApiProblemException;
import org.junit.jupiter.api.Test;

class SearchCursorTest {

    @Test
    void roundTripsExactly() {
        var c = new SearchCursor(4, 0.7142857313156128, 94.31234567891234, "n2");
        assertThat(SearchCursor.decode(c.encode(), 4)).isEqualTo(c);
    }

    @Test
    void otherReleaseIsRejectedWithAdvice() {
        String token = new SearchCursor(99, 0, 1, "n1").encode();
        assertThatThrownBy(() -> SearchCursor.decode(token, 1))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("belongs to release 99");
    }

    @Test
    void garbageIsMalformed() {
        for (String bad : new String[] {"%%%", "", "dGhpcyBpcyBub3QgYSBjdXJzb3I", "djF8MXx4fHl8eg"}) {
            assertThatThrownBy(() -> SearchCursor.decode(bad, 1))
                    .isInstanceOf(ApiProblemException.class).hasMessageContaining("malformed cursor");
        }
    }
}
