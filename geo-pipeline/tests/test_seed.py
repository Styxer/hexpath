from geopipeline.seed import export_seed


def test_seed_is_deterministic(tmp_path):
    export_seed(tmp_path / "a.sql")
    export_seed(tmp_path / "b.sql")
    assert (tmp_path / "a.sql").read_bytes() == (tmp_path / "b.sql").read_bytes()


def test_seed_has_release_and_places(tmp_path):
    sql = export_seed(tmp_path / "s.sql").read_text(encoding="utf-8")
    assert "hexpath_create_partitions(1)" in sql
    assert sql.count("INSERT INTO place ") == 8
    assert "'ACTIVE'" in sql
