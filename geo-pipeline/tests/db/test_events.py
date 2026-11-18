import pytest

from geopipeline.events import relay_pending

from .conftest import connect, variant

pytestmark = pytest.mark.db


class Recorder:
    def __init__(self):
        self.sent = []

    def send(self, event):
        self.sent.append(event)


def test_relay_marks_sent(clean, store, mini_dataset):
    store.publish(store.stage(mini_dataset, {}, None))
    store.publish(store.stage(variant(mini_dataset, 1), {}, None))
    rec = Recorder()
    assert relay_pending(connect(clean.writer), rec) == 2
    assert [e["payload"]["release_id"] for e in rec.sent] == [1, 2]
    assert relay_pending(connect(clean.writer), rec) == 0
