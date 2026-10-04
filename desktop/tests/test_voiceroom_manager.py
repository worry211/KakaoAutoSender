import json
import tempfile
import unittest
from pathlib import Path

from voiceroom_manager.model import AppConfig, RoomConfig, RoomState, StateStore, next_check_for_active, retry_delay_seconds
from voiceroom_manager.ui import UiTree, center, parse_bounds


class UiTreeTest(unittest.TestCase):
    def test_find_and_clickable_parent(self):
        xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation='0'><node text='' content-desc='' resource-id='' clickable='true' bounds='[10,20][210,120]'><node text='보이스룸' content-desc='' resource-id='' clickable='false' bounds='[20,30][200,100]' /></node></hierarchy>"""
        tree = UiTree(xml)
        found = tree.find(["보이스룸"], exact=True)
        self.assertEqual(1, len(found))
        target = tree.best_click_target(found[0])
        self.assertTrue(target.clickable)
        self.assertEqual((110, 70), center(target.bounds))

    def test_bounds(self):
        self.assertEqual((1, 2, 30, 40), parse_bounds("[1,2][30,40]"))


class ModelTest(unittest.TestCase):
    def test_config_load(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "config.json"
            path.write_text(json.dumps({"rooms": [{"id": "a", "title": "방 A"}]}, ensure_ascii=False), encoding="utf-8")
            config = AppConfig.load(path)
            self.assertEqual("a", config.rooms[0].id)
            self.assertEqual(172800, config.rooms[0].reopen_after_seconds)

    def test_duplicate_room_ids_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "config.json"
            path.write_text(json.dumps({"rooms": [{"id": "a", "title": "A"}, {"id": "a", "title": "B"}]}), encoding="utf-8")
            with self.assertRaises(ValueError):
                AppConfig.load(path)

    def test_active_schedule_known_start(self):
        room = RoomConfig(id="a", title="A", reopen_after_seconds=172800, precheck_seconds=300)
        state = RoomState(started_at=1000)
        self.assertEqual(173500, next_check_for_active(room, state, 2000))

    def test_active_schedule_unknown_start(self):
        room = RoomConfig(id="a", title="A", unknown_active_probe_seconds=300)
        state = RoomState(started_at=None)
        self.assertEqual(2300, next_check_for_active(room, state, 2000))

    def test_retry_backoff(self):
        self.assertEqual(60, retry_delay_seconds(1))
        self.assertEqual(180, retry_delay_seconds(2))
        self.assertEqual(3600, retry_delay_seconds(99))

    def test_state_store_round_trip(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "state.json"
            store = StateStore(path)
            state = store.get("a")
            state.status = "ACTIVE"
            state.next_check_at = 1234
            store.save()
            reloaded = StateStore(path)
            self.assertEqual("ACTIVE", reloaded.get("a").status)
            self.assertEqual(1234, reloaded.get("a").next_check_at)


if __name__ == "__main__":
    unittest.main()
