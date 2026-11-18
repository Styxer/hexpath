"""One pipeline run at a time, enforced by a Postgres session-level advisory lock."""
from contextlib import contextmanager

LOCK_KEY = 0x68657870  # "hexp"


class PipelineBusy(RuntimeError):
    pass


@contextmanager
def pipeline_lock(conn):
    got = conn.execute("select pg_try_advisory_lock(%s)", (LOCK_KEY,)).fetchone()[0]
    if not got:
        raise PipelineBusy("another pipeline run holds the lock; try again when it finishes")
    try:
        yield
    finally:
        conn.execute("select pg_advisory_unlock(%s)", (LOCK_KEY,))
