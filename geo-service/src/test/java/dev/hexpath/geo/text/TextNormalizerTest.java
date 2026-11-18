package dev.hexpath.geo.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Same golden file as the Python pipeline's test: the two normalisers must never drift. */
class TextNormalizerTest {

    static Stream<Arguments> golden() throws IOException {
        return Files.readAllLines(Path.of("../shared/normalization-golden.tsv"), StandardCharsets.UTF_8).stream()
                .map(line -> line.split("\t", -1))
                .map(cols -> Arguments.of(cols[0], cols[1]));
    }

    @ParameterizedTest
    @MethodSource("golden")
    void normalizerMatchesGolden(String raw, String expected) {
        assertThat(TextNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("golden")
    void nullIsEmpty(String ignored, String alsoIgnored) {
        assertThat(TextNormalizer.normalize(null)).isEmpty();
    }
}
