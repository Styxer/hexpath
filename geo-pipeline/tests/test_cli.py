import os
import subprocess
import sys
from dataclasses import dataclass

import pytest

from conftest import REPO


@dataclass
class Result:
    exit_code: int
    stdout: str
    stderr: str


@pytest.fixture
def cli():
    def run(*args, env=None):
        base = {k: v for k, v in os.environ.items() if not k.startswith("HEXPATH_")}
        p = subprocess.run([sys.executable, "-m", "geopipeline", *args], capture_output=True, text=True,
                           cwd=REPO, env={**base, **(env or {})}, timeout=120)
        return Result(p.returncode, p.stdout, p.stderr)
    return run


def test_override_without_reason_is_usage_error(cli):
    assert cli("run", "--override").exit_code == 2


def test_missing_extract_is_input_error(cli, tmp_path):
    r = cli("run", "--source", "osm", env={"HEXPATH_OSM_PATH": str(tmp_path / "nope.json.gz")})
    assert r.exit_code == 2 and "not found" in r.stderr and "Traceback" not in r.stderr


def test_corrupt_extract_is_input_error(cli, tmp_path):
    bad = tmp_path / "bad.json.gz"
    bad.write_bytes(b"not gzip at all")
    r = cli("run", "--source", "osm", env={"HEXPATH_OSM_PATH": str(bad)})
    assert r.exit_code == 2 and "Traceback" not in r.stderr


def test_unknown_source_is_usage_error(cli):
    r = cli("run", "--source", "fixture:nope")
    assert r.exit_code == 2 and "Traceback" not in r.stderr


def test_missing_dsn_is_usage_error(cli):
    r = cli("run", "--source", "fixture:mini")
    assert r.exit_code == 2 and "HEXPATH_WRITER_DSN" in r.stderr
