"""Text normalisation shared (by golden file) with the Java service.

The rules, in order, must stay identical to dev.hexpath.geo.text.TextNormalizer;
shared/normalization-golden.tsv is the contract both test suites run.
"""
import re
import unicodedata

_NIQQUD = re.compile("[֑-ׇ]")          # Hebrew points and cantillation marks
_QUOTES = re.compile("[׳״'\"]")         # geresh, gershayim, ASCII quotes
_PUNCT = re.compile(r"[^\w\s]")                   # anything that is not a letter, digit or space
_SPACE = re.compile(r"\s+")
STOPWORDS = frozenset(
    "רחוב רח שדרות שד st street blvd boulevard ave avenue rd road".split()
)


def normalize(text: str | None) -> str:
    if not text:
        return ""
    s = unicodedata.normalize("NFKC", text)
    s = _NIQQUD.sub("", s)
    s = _QUOTES.sub("", s)
    s = s.lower()
    s = _PUNCT.sub(" ", s)
    words = [w for w in _SPACE.split(s) if w and w not in STOPWORDS]
    return " ".join(words)
