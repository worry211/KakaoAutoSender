from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any
import json
import time


DEFAULT_SELECTORS = {
    "room_ready": ["채팅", "메시지 입력", "메시지를 입력"],
    "more": ["더보기", "메뉴", "+"],
    "voice_room": ["보이스룸"],
    "create": ["보이스룸 만들기", "보이스룸 시작", "보이스룸 열기", "시작하기"],
    "confirm": ["시작", "만들기", "확인"],
    "active": ["보이스룸 종료", "보이스룸 나가기", "스피커 신청", "리스너"],
}


@dataclass
class RoomConfig:
    id: str
    title: str
    room_url: str = ""
    enabled: bool = True
    reopen_after_seconds: int = 48 * 60 * 60
    precheck_seconds: int = 5 * 60
    unknown_active_probe_seconds: int = 5 * 60
    selectors: dict[str, list[str]] = field(default_factory=dict)

    def merged_selectors(self) -> dict[str, list[str]]:
        merged = {k: list(v) for k, v in DEFAULT_SELECTORS.items()}
        for key, values in (self.selectors or {}).items():
            if values:
                merged[key] = [str(x) for x in values if str(x).strip()]
        return merged

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "RoomConfig":
        room_id = str(raw.get("id", "")).strip()
        title = str(raw.get("title", "")).strip()
        if not room_id or not title:
            raise ValueError("Each room needs non-empty id and title.")
        return cls(
            id=room_id,
            title=title,
            room_url=str(raw.get("room_url", "")).strip(),
            enabled=bool(raw.get("enabled", True)),
            reopen_after_seconds=max(3600, int(raw.get("reopen_after_seconds", 48 * 60 * 60))),
            precheck_seconds=max(30, int(raw.get("precheck_seconds", 5 * 60))),
            unknown_active_probe_seconds=max(60, int(raw.get("unknown_active_probe_seconds", 5 * 60))),
            selectors=dict(raw.get("selectors") or {}),
        )


@dataclass
class AppConfig:
    adb_path: str = "adb"
    device_serial: str = ""
    kakao_package: str = "com.kakao.talk"
    poll_seconds: int = 15
    room_gap_seconds: int = 8
    mute_audio: bool = True
    keep_device_awake: bool = True
    rooms: list[RoomConfig] = field(default_factory=list)

    @classmethod
    def load(cls, path: str | Path) -> "AppConfig":
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        rooms = [RoomConfig.from_dict(x) for x in raw.get("rooms", [])]
        seen: set[str] = set()
        for room in rooms:
            if room.id in seen:
                raise ValueError(f"Duplicate room id: {room.id}")
            seen.add(room.id)
        return cls(
            adb_path=str(raw.get("adb_path", "adb")).strip() or "adb",
            device_serial=str(raw.get("device_serial", "")).strip(),
            kakao_package=str(raw.get("kakao_package", "com.kakao.talk")).strip() or "com.kakao.talk",
            poll_seconds=max(5, int(raw.get("poll_seconds", 15))),
            room_gap_seconds=max(2, int(raw.get("room_gap_seconds", 8))),
            mute_audio=bool(raw.get("mute_audio", True)),
            keep_device_awake=bool(raw.get("keep_device_awake", True)),
            rooms=rooms,
        )


@dataclass
class RoomState:
    started_at: float | None = None
    last_verified_at: float | None = None
    next_check_at: float = 0.0
    failures: int = 0
    status: str = "NEW"
    last_error: str = ""

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "RoomState":
        return cls(
            started_at=_float_or_none(raw.get("started_at")),
            last_verified_at=_float_or_none(raw.get("last_verified_at")),
            next_check_at=float(raw.get("next_check_at") or 0),
            failures=max(0, int(raw.get("failures") or 0)),
            status=str(raw.get("status") or "NEW"),
            last_error=str(raw.get("last_error") or ""),
        )


class StateStore:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self._states: dict[str, RoomState] = {}
        self.load()

    def load(self) -> None:
        if not self.path.exists():
            self._states = {}
            return
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        self._states = {
            str(room_id): RoomState.from_dict(value)
            for room_id, value in dict(raw.get("rooms") or {}).items()
        }

    def get(self, room_id: str) -> RoomState:
        if room_id not in self._states:
            self._states[room_id] = RoomState(next_check_at=time.time())
        return self._states[room_id]

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "version": 1,
            "rooms": {room_id: asdict(state) for room_id, state in sorted(self._states.items())},
        }
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.path)


def next_check_for_active(room: RoomConfig, state: RoomState, now: float) -> float:
    if state.started_at is None:
        return now + room.unknown_active_probe_seconds
    target = state.started_at + room.reopen_after_seconds
    early = target - room.precheck_seconds
    if now < early:
        return early
    return now + 60


def retry_delay_seconds(failures: int) -> int:
    schedule = (60, 180, 600, 1800, 3600)
    index = min(max(0, failures - 1), len(schedule) - 1)
    return schedule[index]


def _float_or_none(value: Any) -> float | None:
    if value is None or value == "":
        return None
    return float(value)
