package dev.hexpath.geo.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CoverageGuardTest {

    @Test
    void insideIsAccepted() {
        assertThatCode(() -> CoverageGuard.requireInside(RequestContextFilterTest.SNAP, 32.0779, 34.7740))
                .doesNotThrowAnyException();
    }

    @Test
    void swappedCoordinatesAreOutOfCoverage() {
        assertThatThrownBy(() -> CoverageGuard.requireInside(RequestContextFilterTest.SNAP, 34.7740, 32.0779))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.type()).isEqualTo(ProblemType.OUT_OF_COVERAGE);
                    org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("check coordinate order");
                });
    }

    @Test
    void nonFiniteOrOutOfRangeIsInvalid() {
        for (double[] p : new double[][] {{Double.NaN, 34.77}, {32.07, Double.POSITIVE_INFINITY}, {91, 34.77}, {32.07, -181}}) {
            assertThatThrownBy(() -> CoverageGuard.requireInside(RequestContextFilterTest.SNAP, p[0], p[1]))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.type()).isEqualTo(ProblemType.INVALID_PARAMETER));
        }
    }
}
