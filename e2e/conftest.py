"""End-to-end tests against a running stack: PostGIS, the pipeline CLI and the service.

HEXPATH_BASE_URL   service URL (default http://localhost:8080)
HEXPATH_WRITER_DSN pipeline database DSN (for the release lifecycle drill)
"""
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest
import requests
from openapi_core import OpenAPI

REPO = Path(__file__).resolve().parents[1]
BASE = os.environ.get("HEXPATH_BASE_URL", "http://localhost:8080")


class Api:
    def __init__(self, base):
        self.base = base
        self.session = requests.Session()

    def get(self, path, **kw):
        return self.session.get(self.base + path, timeout=30, **kw)

    def post(self, path, **kw):
        return self.session.post(self.base + path, timeout=30, **kw)

    def release(self) -> int:
        return self.get("/v1/dataset").json()["release"]


@pytest.fixture(scope="session")
def api():
    return Api(BASE)


@pytest.fixture(scope="session")
def spec():
    return OpenAPI.from_file_path(str(REPO / "api" / "openapi.yaml"))


@pytest.fixture(scope="session")
def cli():
    def run(*args):
        p = subprocess.run([sys.executable, "-m", "geopipeline", *args], cwd=REPO, capture_output=True, text=True,
                           timeout=300)
        print(p.stdout, p.stderr)
        return p
    return run


def wait_for_release_change(api, old, timeout_s=15):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        current = api.release()
        if current != old:
            return current
        time.sleep(0.5)
    raise AssertionError(f"service still on release {old} after {timeout_s} s")
