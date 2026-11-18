def test_page_served_with_attribution(api):
    html = api.get("/").text
    assert "© OpenStreetMap contributors" in html and 'integrity="sha' in html
    assert 'src="app.js"' in html


def test_page_script_is_served(api):
    r = api.get("/app.js")
    assert r.status_code == 200 and "along-route" in r.text


def test_page_is_not_behind_the_api_readiness_gate(api):
    assert api.get("/").headers.get("X-Request-Id")
