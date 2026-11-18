import pytest
from openapi_core.contrib.requests import RequestsOpenAPIRequest, RequestsOpenAPIResponse

DIZENGOFF = {"lat": 32.0779, "lon": 34.7740}
ROUTE = {"type": "LineString", "coordinates": [[34.7740, 32.0700], [34.7740, 32.0850]]}


def check(spec, resp):
    spec.validate_response(RequestsOpenAPIRequest(resp.request), RequestsOpenAPIResponse(resp))
    return resp


def test_every_endpoint_matches_contract(api, spec):
    search = check(spec, api.get("/v1/places/search", params={"q": "cafe", **DIZENGOFF}))
    assert search.status_code == 200 and search.json()["results"], search.text
    some_id = search.json()["results"][0]["id"]
    check(spec, api.get(f"/v1/places/{some_id}"))
    check(spec, api.get("/v1/boundaries/containing", params=DIZENGOFF))
    check(spec, api.get("/v1/dataset"))
    check(spec, api.get("/v1/status"))
    along = check(spec, api.post("/v1/search/along-route", json={"route": ROUTE, "buffer_m": 300}))
    assert along.status_code == 200 and along.json()["results"]


def test_error_responses_match_contract(api, spec):
    check(spec, api.get("/v1/places/search", params={"limit": 101, **DIZENGOFF}))
    check(spec, api.get("/v1/places/n0"))
    check(spec, api.get("/v1/places/search", params={"lat": 34.774, "lon": 32.0779}))


@pytest.mark.parametrize("path,params", [
    ("/v1/dataset", {}),
    ("/v1/places/search", {"q": "רחוב"}),                 # 400
    ("/v1/places/n0", {}),                                # 404
    ("/v1/places/search", {"lat": 34.774, "lon": 32.0779}),  # 422
])
def test_headers_everywhere(api, path, params):
    r = api.get(path, params=params)
    assert r.headers.get("X-Dataset-Release", "").isdigit(), (r.status_code, r.headers)
    assert r.headers.get("X-Request-Id")


@pytest.mark.parametrize("method,path,kw,status,slug", [
    ("get", "/v1/places/search", {"params": {"limit": 101, **DIZENGOFF}}, 400, "invalid-parameter"),
    ("get", "/v1/places/search", {"params": {"radius_m": 5001, **DIZENGOFF}}, 400, "invalid-parameter"),
    ("get", "/v1/places/search", {"params": {"q": "  "}}, 400, "invalid-parameter"),
    ("post", "/v1/search/along-route", {"json": {"route": ROUTE, "buffer_m": 2001}}, 400, "invalid-parameter"),
    ("post", "/v1/search/along-route", {"json": {"route": {"type": "LineString", "coordinates": [[34.77, 32.07]]}}},
     400, "invalid-parameter"),
    ("post", "/v1/search/along-route",
     {"json": {"route": {"type": "LineString", "coordinates": [[34.770, 32.065], [34.790, 32.085]] * 11}}},
     422, "route-too-long"),
    ("post", "/v1/search/along-route", {"data": b"x" * (300 * 1024), "headers": {"Content-Type": "application/json"}},
     413, "payload-too-large"),
])
def test_limits_rejected(api, method, path, kw, status, slug):
    r = getattr(api, method)(path, **kw)
    assert r.status_code == status, r.text
    assert r.json()["type"] == f"urn:hexpath:problem:{slug}"


def test_real_data_is_served(api):
    d = api.get("/v1/dataset").json()
    assert d["counts"]["place"] > 1000 and d["counts"]["address"] > 10000
    hebrew = api.get("/v1/places/search", params={"q": "קפה", **DIZENGOFF, "radius_m": 5000}).json()["results"]
    assert hebrew, "expected Hebrew-named places in central Tel Aviv"
