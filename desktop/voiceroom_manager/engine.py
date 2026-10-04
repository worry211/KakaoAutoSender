from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import json
import threading
import time

from .adb import Adb, AdbError
from .model import AppConfig, RoomConfig, StateStore, next_check_for_active, retry_delay_seconds
from .power import keep_windows_awake
from .ui import UiTree, center, valid_bounds


@dataclass
class ProbeResult:
    status: str
    detail: str = ""


class VoiceRoomEngine:
    def __init__(self, config: AppConfig, state_path: str | Path, log_path: str | Path, snapshot_dir: str | Path):
        self.config = config
        self.state = StateStore(state_path)
        self.log_path = Path(log_path)
        self.snapshot_dir = Path(snapshot_dir)
        self.adb = Adb(config.adb_path, config.device_serial)

    def prepare(self) -> None:
        model = self.adb.ensure_device()
        self.adb.wake_and_keep_awake(self.config.keep_device_awake)
        if self.config.mute_audio:
            self.adb.mute_audio()
        self.log("READY", detail=f"ADB device: {model}")

    def due_rooms(self, now: float | None = None) -> list[RoomConfig]:
        now = time.time() if now is None else now
        due = []
        for room in self.config.rooms:
            if not room.enabled:
                continue
            state = self.state.get(room.id)
            if state.next_check_at <= now:
                due.append(room)
        due.sort(key=lambda room: self.state.get(room.id).next_check_at)
        return due

    def process_room(self, room: RoomConfig) -> ProbeResult:
        state = self.state.get(room.id)
        now = time.time()
        try:
            self.log("PROBE_START", room=room)
            self._open_room(room)
            tree = self._tree()
            if self._is_active(room, tree):
                state.last_verified_at = now
                state.status = "ACTIVE"
                state.last_error = ""
                state.failures = 0
                state.next_check_at = next_check_for_active(room, state, now)
                self.state.save()
                self.log("ACTIVE", room=room, detail=f"next={int(state.next_check_at)}")
                return ProbeResult("ACTIVE", "보이스룸 실행 중")

            created = self._create_voice_room(room)
            if created:
                now = time.time()
                state.started_at = now
                state.last_verified_at = now
                state.next_check_at = now + room.reopen_after_seconds - room.precheck_seconds
                state.failures = 0
                state.status = "CREATED"
                state.last_error = ""
                self.state.save()
                self.log("CREATED", room=room, detail=f"next={int(state.next_check_at)}")
                return ProbeResult("CREATED", "보이스룸 개설 확인")
            raise RuntimeError("보이스룸 개설 후 활성 상태를 확인하지 못함")
        except Exception as exc:
            state.failures += 1
            state.status = "ERROR"
            state.last_error = f"{type(exc).__name__}: {exc}"
            state.next_check_at = time.time() + retry_delay_seconds(state.failures)
            self.state.save()
            self.log("ERROR", room=room, detail=state.last_error)
            try:
                self.adb.snapshot(self.snapshot_dir, f"{room.id}-error")
            except Exception:
                pass
            return ProbeResult("ERROR", state.last_error)

    def run_once(self, stop_event: threading.Event | None = None) -> int:
        due = self.due_rooms()
        processed = 0
        for index, room in enumerate(due):
            if stop_event is not None and stop_event.is_set():
                break
            self.process_room(room)
            processed += 1
            if index < len(due) - 1 and not self._wait(self.config.room_gap_seconds, stop_event):
                break
        return processed

    def run_forever(self, stop_event: threading.Event | None = None) -> None:
        keep_windows_awake(True)
        try:
            self.prepare()
            self.log("DAEMON_START")
            while stop_event is None or not stop_event.is_set():
                count = self.run_once(stop_event)
                if count == 0:
                    if not self._wait(self.config.poll_seconds, stop_event):
                        break
            self.log("DAEMON_STOP")
        finally:
            keep_windows_awake(False)

    def snapshot(self, prefix: str = "manual") -> tuple[Path, Path]:
        self.prepare()
        return self.adb.snapshot(self.snapshot_dir, prefix)

    def status_rows(self) -> list[dict[str, object]]:
        rows = []
        for room in self.config.rooms:
            state = self.state.get(room.id)
            rows.append({
                "id": room.id, "title": room.title, "enabled": room.enabled,
                "status": state.status, "started_at": state.started_at,
                "last_verified_at": state.last_verified_at, "next_check_at": state.next_check_at,
                "failures": state.failures, "last_error": state.last_error,
            })
        return rows

    def _wait(self, seconds: float, stop_event: threading.Event | None) -> bool:
        if stop_event is None:
            time.sleep(seconds)
            return True
        return not stop_event.wait(seconds)

    def _open_room(self, room: RoomConfig) -> None:
        selectors = room.merged_selectors()
        if room.room_url:
            self.adb.open_url(room.room_url)
            time.sleep(2.5)
            tree = self._tree()
            if self._looks_like_room(room, tree, selectors):
                return
        self.adb.launch_package(self.config.kakao_package)
        time.sleep(2.0)
        tree = self._tree()
        if self._looks_like_room(room, tree, selectors):
            return
        width, height = self.adb.screen_size()
        for attempt in range(12):
            tree = self._tree()
            exact = tree.find([room.title], exact=True)
            if exact:
                target = tree.best_click_target(exact[0])
                if valid_bounds(target.bounds):
                    self.adb.tap(*center(target.bounds))
                    time.sleep(1.8)
                    if self._looks_like_room(room, self._tree(), selectors):
                        return
            if attempt < 11:
                self.adb.swipe(width // 2, int(height * 0.78), width // 2, int(height * 0.32))
                time.sleep(0.45)
        raise RuntimeError(f"방을 찾지 못함: {room.title}")

    def _looks_like_room(self, room: RoomConfig, tree: UiTree, selectors: dict[str, list[str]]) -> bool:
        has_title = tree.has_any([room.title], exact=True)
        has_controls = tree.has_any(selectors["more"]) or tree.has_any(selectors["room_ready"])
        return has_title and has_controls

    def _create_voice_room(self, room: RoomConfig) -> bool:
        selectors = room.merged_selectors()
        tree = self._tree()
        if not self._click_any(tree, selectors["more"]):
            raise RuntimeError("방의 더보기/+ 버튼을 찾지 못함")
        time.sleep(1.0)
        tree = self._tree()
        if self._is_active(room, tree):
            return True
        if not self._click_any(tree, selectors["voice_room"], exact=False):
            raise RuntimeError("보이스룸 메뉴를 찾지 못함")
        time.sleep(1.5)
        tree = self._tree()
        if self._is_active(room, tree):
            return True
        if not self._click_any(tree, selectors["create"], exact=False):
            if not self._click_any(tree, selectors["confirm"], exact=True):
                raise RuntimeError("보이스룸 개설 버튼을 찾지 못함")
        time.sleep(1.8)
        tree = self._tree()
        if self._is_active(room, tree):
            return True
        if self._click_any(tree, selectors["confirm"], exact=True):
            time.sleep(1.5)
            tree = self._tree()
        return self._is_active(room, tree)

    def _is_active(self, room: RoomConfig, tree: UiTree) -> bool:
        return tree.has_any(room.merged_selectors()["active"], exact=False)

    def _click_any(self, tree: UiTree, terms: list[str], exact: bool = False) -> bool:
        for node in tree.find(terms, exact=exact):
            target = tree.best_click_target(node)
            if valid_bounds(target.bounds):
                self.adb.tap(*center(target.bounds))
                return True
        return False

    def _tree(self) -> UiTree:
        xml = self.adb.dump_ui()
        if not xml.strip():
            raise AdbError("Empty UI hierarchy")
        return UiTree(xml)

    def log(self, event: str, room: RoomConfig | None = None, detail: str = "") -> None:
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        row = {"ts": time.time(), "event": event, "room_id": room.id if room else "", "room_title": room.title if room else "", "detail": detail}
        with self.log_path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
