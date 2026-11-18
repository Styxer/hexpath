from dataclasses import replace

from geopipeline.model import input_hash


def test_input_hash_stable_under_reordering(mini_dataset):
    shuffled = replace(mini_dataset, places=tuple(reversed(mini_dataset.places)))
    assert input_hash(shuffled) == input_hash(mini_dataset)


def test_input_hash_changes_when_a_place_moves(mini_dataset):
    moved = replace(mini_dataset.places[0], lat=mini_dataset.places[0].lat + 0.001)
    changed = replace(mini_dataset, places=(moved,) + mini_dataset.places[1:])
    assert input_hash(changed) != input_hash(mini_dataset)
