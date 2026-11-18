package dev.hexpath.geo.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hexpath.geo.dataset.DatasetSnapshot;
import dev.hexpath.geo.dataset.SnapshotHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestContextFilterTest {

    static final DatasetSnapshot SNAP = new DatasetSnapshot(7, new Envelope(34.765, 34.795, 32.06, 32.09),
            Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"), 3);

    private static SnapshotHolder holder(DatasetSnapshot s) {
        var h = new SnapshotHolder(new SimpleMeterRegistry());
        if (s != null) {
            h.swap(s);
        }
        return h;
    }

    @Test
    void notReadyIs503ForApi() throws Exception {
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestContextFilter(holder(null)).doFilter(new MockHttpServletRequest("GET", "/v1/dataset"), res, chain);
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentType()).startsWith("application/problem+json");
        assertThat(res.getContentAsString()).contains("urn:hexpath:problem:service-not-ready");
        assertThat(chain.getRequest()).isNull();                // the controller never runs
        assertThat(res.getHeader("X-Request-Id")).isNotBlank();
    }

    @Test
    void notReadyStillServesActuator() throws Exception {
        var chain = new MockFilterChain();
        new RequestContextFilter(holder(null)).doFilter(
                new MockHttpServletRequest("GET", "/actuator/health/readiness"), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void pinsSnapshotAndSetsReleaseHeader() throws Exception {
        var res = new MockHttpServletResponse();
        var seen = new AtomicReference<Object>();
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse resp) {
                seen.set(req.getAttribute(RequestContextFilter.SNAPSHOT_ATTRIBUTE));
            }
        };
        new RequestContextFilter(holder(SNAP)).doFilter(new MockHttpServletRequest("GET", "/v1/dataset"), res, chain);
        assertThat(seen.get()).isEqualTo(SNAP);
        assertThat(res.getHeader("X-Dataset-Release")).isEqualTo("7");
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        var req = new MockHttpServletRequest("POST", "/v1/search/along-route");
        req.setContent(new byte[300 * 1024]);
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new RequestContextFilter(holder(SNAP)).doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString()).contains("urn:hexpath:problem:payload-too-large");
        assertThat(chain.getRequest()).isNull();
    }
}
