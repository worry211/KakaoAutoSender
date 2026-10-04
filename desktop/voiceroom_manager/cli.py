from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path
import shutil
import sys

from .engine import VoiceRoomEngine
from .model import AppConfig


def fmt_ts(value: float | None) -> str:
    if not value:
        return "-"
    return datetime.fromtimestamp(value).strftime("%Y-%m-%d %H:%M:%S")


def build_engine(config_path: Path) -> VoiceRoomEngine:
    config = AppConfig.load(config_path)
    base = config_path.parent
    return VoiceRoomEngine(config, base / "voiceroom_state.json", base / "logs" / "voiceroom.jsonl", base / "diagnostics")


def cmd_init(args: argparse.Namespace) -> int:
    target = Path(args.config)
    if target.exists():
        print(f"이미 존재: {target}")
        return 0
    example = Path(__file__).resolve().parent.parent / "config.example.json"
    shutil.copyfile(example, target)
    print(f"생성 완료: {target}")
    print("rooms에 방 id/title을 넣고, 가능하면 room_url도 입력해.")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    engine = build_engine(Path(args.config))
    for row in engine.status_rows():
        print(f"[{row['status']}] {row['id']} | {row['title']} | 다음확인={fmt_ts(row['next_check_at'])} | 최근확인={fmt_ts(row['last_verified_at'])} | 실패={row['failures']}")
        if row["last_error"]:
            print(f"  오류: {row['last_error']}")
    return 0


def cmd_once(args: argparse.Namespace) -> int:
    engine = build_engine(Path(args.config))
    engine.prepare()
    count = engine.run_once()
    print(f"처리한 방: {count}개")
    return 0


def cmd_probe(args: argparse.Namespace) -> int:
    engine = build_engine(Path(args.config))
    engine.prepare()
    room = next((x for x in engine.config.rooms if x.id == args.room), None)
    if room is None:
        print(f"없는 방 id: {args.room}", file=sys.stderr)
        return 2
    result = engine.process_room(room)
    print(f"{room.title}: {result.status} - {result.detail}")
    return 0 if result.status != "ERROR" else 1


def cmd_run(args: argparse.Namespace) -> int:
    engine = build_engine(Path(args.config))
    try:
        engine.run_forever()
    except KeyboardInterrupt:
        print("\n중단됨")
        return 0


def cmd_snapshot(args: argparse.Namespace) -> int:
    engine = build_engine(Path(args.config))
    xml_path, png_path = engine.snapshot(args.prefix)
    print(f"UI XML: {xml_path}")
    print(f"Screenshot: {png_path}")
    return 0


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="KakaoTalk VoiceRoom Manager (Windows + ADB)")
    p.add_argument("--config", default="voiceroom_config.json")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("init")
    sub.add_parser("status")
    sub.add_parser("once")
    sub.add_parser("run")
    probe = sub.add_parser("probe")
    probe.add_argument("--room", required=True)
    snap = sub.add_parser("snapshot")
    snap.add_argument("--prefix", default="manual")
    return p


def main() -> int:
    args = parser().parse_args()
    commands = {"init": cmd_init, "status": cmd_status, "once": cmd_once, "run": cmd_run, "probe": cmd_probe, "snapshot": cmd_snapshot}
    return commands[args.command](args)


if __name__ == "__main__":
    raise SystemExit(main())
