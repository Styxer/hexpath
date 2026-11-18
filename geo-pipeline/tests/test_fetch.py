import gzip
import json

import httpx
import pytest

from geopipeline.osm.fetch import FetchError, fetch_overpass

BBOX = (34.765, 32.060, 34.795, 32.090)
BODY = json.dumps({"osm3s": {"timestamp_osm_base": "2026-11-01T00:00:00Z"}, "elements": []}).encode()


def transport(statuses):
    calls = []

    def handler(request):
        calls.append(str(request.url))
        status = statuses[len(calls) - 1] if len(calls) <= len(statuses) else statuses[-1]
        return httpx.Response(status, content=BODY if status == 200 else b"busy")
    return httpx.MockTransport(handler), calls


def test_falls_back_to_next_mirror(tmp_path):
    t, calls = transport([502, 502, 200])
    out = fetch_overpass(BBOX, tmp_path / "x.json.gz", endpoints=("https://a/api", "https://b/api"),
                         client=httpx.Client(transport=t), pause_s=0)
    assert json.loads(gzip.decompress(out.read_bytes()))["elements"] == []
    assert calls == ["https://a/api", "https://a/api", "https://b/api"]   # two tries per mirror


def test_all_mirrors_failing_is_a_clear_error(tmp_path):
    t, _ = transport([502])
    with pytest.raises(FetchError) as e:
        fetch_overpass(BBOX, tmp_path / "x.json.gz", endpoints=("https://a/api", "https://b/api"),
                       client=httpx.Client(transport=t), pause_s=0)
    assert "https://a/api" in str(e.value) and "502" in str(e.value)
    assert not (tmp_path / "x.json.gz").exists()


def test_query_uses_south_west_north_east_order():
    from geopipeline.osm.fetch import build_query
    assert '"amenity"](32.06,34.765,32.09,34.795)' in build_query(BBOX)
