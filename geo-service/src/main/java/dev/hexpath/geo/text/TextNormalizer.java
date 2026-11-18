package dev.hexpath.geo.text;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Same rules, in the same order, as geopipeline/normalize.py. The shared golden file
 * shared/normalization-golden.tsv is run by both test suites so the two cannot drift.
 */
public final class TextNormalizer {

    private static final Pattern NIQQUD = Pattern.compile("[\\u0591-\\u05C7]");
    private static final Pattern QUOTES = Pattern.compile("[\\u05F3\\u05F4'\"]");
    private static final Pattern PUNCT = Pattern.compile("[^\\w\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    static final Set<String> STOPWORDS = Set.of(
            "רחוב", "רח", "שדרות", "שד", "st", "street", "blvd", "boulevard", "ave", "avenue", "rd", "road");

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        s = NIQQUD.matcher(s).replaceAll("");
        s = QUOTES.matcher(s).replaceAll("");
        s = s.toLowerCase(Locale.ROOT);
        s = PUNCT.matcher(s).replaceAll(" ");
        return Arrays.stream(SPACE.split(s))
                .filter(w -> !w.isEmpty() && !STOPWORDS.contains(w))
                .collect(Collectors.joining(" "));
    }
}
