from __future__ import annotations

from dataclasses import replace
from datetime import datetime
from pathlib import Path
import os
import subprocess
import threading
import time
import uuid
import tkinter as tk
from tkinter import messagebox, ttk

from .adb import Adb
from .engine import VoiceRoomEngine
from .model import AppConfig, RoomConfig, StateStore
from .power import keep_windows_awake


STATUS_LABELS = {"NEW": "대기", "ACTIVE": "실행 중", "CREATED": "재개설 완료", "ERROR": "오류"}


def format_ts(value: float | None) -> str:
    if not value:
        return "-"
    return datetime.fromtimestamp(value).strftime("%m-%d %H:%M:%S")


def remaining_text(room: RoomConfig, started_at: float | None, now: float | None = None) -> str:
    if not started_at:
        return "기준시간 확인 중"
    now = time.time() if now is None else now
    remaining = int(started_at + room.reopen_after_seconds - now)
    if remaining <= 0:
        return "종료 여부 확인"
    hours, rem = divmod(remaining, 3600)
    minutes = rem // 60
    return f"{hours}시간 {minutes:02d}분"


class RoomDialog(tk.Toplevel):
    def __init__(self, master: tk.Misc, room: RoomConfig | None = None):
        super().__init__(master)
        self.title("방 설정")
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()
        self.result: RoomConfig | None = None
        self.room = room
        wrap = ttk.Frame(self, padding=16)
        wrap.grid(row=0, column=0, sticky="nsew")
        ttk.Label(wrap, text="카카오 오픈채팅방 이름").grid(row=0, column=0, sticky="w")
        self.title_var = tk.StringVar(value=room.title if room else "")
        title_entry = ttk.Entry(wrap, textvariable=self.title_var, width=50)
        title_entry.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(4, 12))
        ttk.Label(wrap, text="오픈채팅 링크 (선택, 있으면 방 진입이 더 안정적)").grid(row=2, column=0, sticky="w")
        self.url_var = tk.StringVar(value=room.room_url if room else "")
        ttk.Entry(wrap, textvariable=self.url_var, width=50).grid(row=3, column=0, columnspan=2, sticky="ew", pady=(4, 12))
        self.enabled_var = tk.BooleanVar(value=room.enabled if room else True)
        ttk.Checkbutton(wrap, text="이 방 자동관리 사용", variable=self.enabled_var).grid(row=4, column=0, columnspan=2, sticky="w")
        ttk.Label(wrap, text="※ 먼저 ‘안전 인식 점검’으로 방/보이스룸 메뉴가 잡히는지 확인한 뒤 자동관리를 켜는 것을 권장해.", foreground="#666666", wraplength=430).grid(row=5, column=0, columnspan=2, sticky="w", pady=(10, 0))
        ttk.Button(wrap, text="취소", command=self.destroy).grid(row=6, column=0, sticky="ew", pady=(16, 0), padx=(0, 5))
        ttk.Button(wrap, text="저장", command=self._save).grid(row=6, column=1, sticky="ew", pady=(16, 0), padx=(5, 0))
        title_entry.focus_set()
        self.bind("<Return>", lambda _e: self._save())
        self.bind("<Escape>", lambda _e: self.destroy())
        self.protocol("WM_DELETE_WINDOW", self.destroy)
        self.wait_visibility()

    def _save(self) -> None:
        title = self.title_var.get().strip()
        if not title:
            messagebox.showwarning("방 설정", "오픈채팅방 이름을 입력해줘.", parent=self)
            return
        room_id = self.room.id if self.room else uuid.uuid4().hex[:12]
        base = self.room or RoomConfig(id=room_id, title=title)
        self.result = replace(base, title=title, room_url=self.url_var.get().strip(), enabled=bool(self.enabled_var.get()))
        self.destroy()


class SettingsDialog(tk.Toplevel):
    def __init__(self, master: tk.Misc, config: AppConfig):
        super().__init__(master)
        self.title("환경 설정")
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()
        self.result: AppConfig | None = None
        self.config = config
        self.devices: dict[str, str] = {}
        wrap = ttk.Frame(self, padding=16)
        wrap.grid(row=0, column=0, sticky="nsew")
        ttk.Label(wrap, text="ADB 실행 파일").grid(row=0, column=0, sticky="w")
        self.adb_var = tk.StringVar(value=config.adb_path)
        ttk.Entry(wrap, textvariable=self.adb_var, width=52).grid(row=1, column=0, columnspan=3, sticky="ew", pady=(4, 12))
        ttk.Label(wrap, text="Android 기기 / 에뮬레이터").grid(row=2, column=0, sticky="w")
        self.serial_var = tk.StringVar(value=config.device_serial)
        self.device_combo = ttk.Combobox(wrap, textvariable=self.serial_var, width=40)
        self.device_combo.grid(row=3, column=0, columnspan=2, sticky="ew", pady=(4, 12))
        ttk.Button(wrap, text="자동 찾기", command=self._discover).grid(row=3, column=2, padx=(8, 0), pady=(4, 12))
        self.mute_var = tk.BooleanVar(value=config.mute_audio)
        self.awake_var = tk.BooleanVar(value=config.keep_device_awake)
        ttk.Checkbutton(wrap, text="Android 소리 자동 음소거", variable=self.mute_var).grid(row=4, column=0, columnspan=3, sticky="w")
        ttk.Checkbutton(wrap, text="Android 에뮬레이터/기기 깨움 유지", variable=self.awake_var).grid(row=5, column=0, columnspan=3, sticky="w")
        ttk.Label(wrap, text="기기 번호를 비워두면 연결된 정상 기기가 1개일 때 자동 선택해. 여러 개면 명시적으로 선택해야 해.", foreground="#666666", wraplength=470).grid(row=6, column=0, columnspan=3, sticky="w", pady=(10, 0))
        ttk.Button(wrap, text="취소", command=self.destroy).grid(row=7, column=0, sticky="ew", pady=(16, 0), padx=(0, 5))
        ttk.Button(wrap, text="저장", command=self._save).grid(row=7, column=1, columnspan=2, sticky="ew", pady=(16, 0), padx=(5, 0))
        self.bind("<Escape>", lambda _e: self.destroy())
        self.protocol("WM_DELETE_WINDOW", self.destroy)
        self.wait_visibility()

    def _discover(self) -> None:
        adb_path = self.adb_var.get().strip() or "adb"
        try:
            devices = Adb.list_devices(adb_path)
        except Exception as exc:
            messagebox.showerror("자동 찾기", f"{type(exc).__name__}: {exc}", parent=self)
            return
        ready = [d for d in devices if d.ready]
        self.devices = {d.label(): d.serial for d in ready}
        labels = list(self.devices)
        self.device_combo["values"] = labels
        if not ready:
            messagebox.showwarning("자동 찾기", "연결된 정상 ADB 기기를 찾지 못했어.", parent=self)
            return
        if len(ready) == 1:
            self.serial_var.set(ready[0].serial)
            messagebox.showinfo("자동 찾기", f"기기 확인: {ready[0].label()}", parent=self)
            return
        self.serial_var.set(labels[0])
        messagebox.showinfo("자동 찾기", "기기가 여러 개야. 목록에서 사용할 기기를 선택해줘.", parent=self)

    def _save(self) -> None:
        raw = self.serial_var.get().strip()
        serial = self.devices.get(raw, raw)
        self.result = replace(self.config, adb_path=self.adb_var.get().strip() or "adb", device_serial=serial, mute_audio=bool(self.mute_var.get()), keep_device_awake=bool(self.awake_var.get()))
        self.destroy()


class VoiceRoomApp:
    def __init__(self, root: tk.Tk, config_path: Path):
        self.root = root
        self.config_path = config_path
        self.base_dir = config_path.parent
        self.state_path = self.base_dir / "voiceroom_state.json"
        self.log_path = self.base_dir / "logs" / "voiceroom.jsonl"
        self.snapshot_dir = self.base_dir / "diagnostics"
        self.stop_event = threading.Event()
        self.worker: threading.Thread | None = None
        self.engine: VoiceRoomEngine | None = None
        self.busy = False
        self.config = self._load_or_create_config()
        self._configure_window()
        self._build_ui()
        self._refresh_table()
        self.root.after(1000, self._tick)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _load_or_create_config(self) -> AppConfig:
        if self.config_path.exists():
            return AppConfig.load(self.config_path)
        config = AppConfig()
        config.save(self.config_path)
        return config

    def _configure_window(self) -> None:
        self.root.title("카카오 보이스룸 매니저")
        self.root.geometry("1180x700")
        self.root.minsize(980, 590)
        style = ttk.Style()
        if "vista" in style.theme_names():
            style.theme_use("vista")
        style.configure("Treeview", rowheight=30)
        style.configure("Title.TLabel", font=("맑은 고딕", 20, "bold"))
        style.configure("Status.TLabel", font=("맑은 고딕", 11, "bold"))

    def _build_ui(self) -> None:
        outer = ttk.Frame(self.root, padding=18)
        outer.pack(fill="both", expand=True)
        header = ttk.Frame(outer)
        header.pack(fill="x")
        ttk.Label(header, text="보이스룸 매니저", style="Title.TLabel").pack(side="left")
        self.master_status = ttk.Label(header, text="● 중지됨", style="Status.TLabel")
        self.master_status.pack(side="right")
        ttk.Label(outer, text="카톡 명령어 없이 프로그램이 여러 방의 보이스룸을 관리해. PC는 켜두되 모니터는 꺼도 돼.").pack(anchor="w", pady=(5, 14))
        toolbar1 = ttk.Frame(outer)
        toolbar1.pack(fill="x", pady=(0, 7))
        self.start_btn = ttk.Button(toolbar1, text="전체 시작", command=self.start)
        self.start_btn.pack(side="left")
        self.stop_btn = ttk.Button(toolbar1, text="전체 중단", command=self.stop, state="disabled")
        self.stop_btn.pack(side="left", padx=(7, 0))
        ttk.Separator(toolbar1, orient="vertical").pack(side="left", fill="y", padx=12)
        ttk.Button(toolbar1, text="방 추가", command=self.add_room).pack(side="left")
        ttk.Button(toolbar1, text="수정", command=self.edit_room).pack(side="left", padx=(7, 0))
        ttk.Button(toolbar1, text="삭제", command=self.delete_room).pack(side="left", padx=(7, 0))
        ttk.Separator(toolbar1, orient="vertical").pack(side="left", fill="y", padx=12)
        ttk.Button(toolbar1, text="환경 설정", command=self.settings).pack(side="left")
        ttk.Button(toolbar1, text="환경 점검", command=self.preflight).pack(side="left", padx=(7, 0))
        toolbar2 = ttk.Frame(outer)
        toolbar2.pack(fill="x", pady=(0, 10))
        ttk.Label(toolbar2, text="실기 점검").pack(side="left")
        ttk.Button(toolbar2, text="안전 인식 점검", command=self.inspect_selected).pack(side="left", padx=(8, 0))
        ttk.Button(toolbar2, text="실제 재개설 테스트", command=self.probe_selected).pack(side="left", padx=(7, 0))
        ttk.Button(toolbar2, text="진단 캡처", command=self.snapshot).pack(side="left", padx=(7, 0))
        ttk.Button(toolbar2, text="진단 폴더 열기", command=self.open_diagnostics).pack(side="left", padx=(7, 0))
        columns = ("enabled", "status", "remaining", "next", "failures")
        self.tree = ttk.Treeview(outer, columns=columns, show="tree headings", selectmode="browse")
        self.tree.heading("#0", text="오픈채팅방")
        self.tree.heading("enabled", text="자동관리")
        self.tree.heading("status", text="상태")
        self.tree.heading("remaining", text="남은 예상시간")
        self.tree.heading("next", text="다음 확인")
        self.tree.heading("failures", text="실패")
        self.tree.column("#0", width=330, minwidth=220)
        self.tree.column("enabled", width=85, anchor="center")
        self.tree.column("status", width=120, anchor="center")
        self.tree.column("remaining", width=155, anchor="center")
        self.tree.column("next", width=170, anchor="center")
        self.tree.column("failures", width=70, anchor="center")
        self.tree.pack(fill="both", expand=True)
        self.tree.bind("<Double-1>", lambda _e: self.edit_room())
        self.tree.bind("<<TreeviewSelect>>", lambda _e: self._show_selected_detail())
        bottom = ttk.Frame(outer)
        bottom.pack(fill="x", pady=(10, 0))
        self.detail_var = tk.StringVar(value="방을 추가한 뒤 ‘환경 점검’ → ‘안전 인식 점검’ 순서로 확인해.")
        ttk.Label(bottom, textvariable=self.detail_var, wraplength=900).pack(side="left", fill="x", expand=True)
        ttk.Label(bottom, text="소리: 자동 음소거").pack(side="right")

    def _reload_config(self) -> None:
        self.config = AppConfig.load(self.config_path)

    def _save_config(self) -> None:
        self.config.save(self.config_path)

    def _selected_room(self) -> RoomConfig | None:
        selected = self.tree.selection()
        if not selected:
            return None
        room_id = selected[0]
        return next((room for room in self.config.rooms if room.id == room_id), None)

    def _require_stopped(self, action: str) -> bool:
        if self.worker and self.worker.is_alive():
            messagebox.showinfo(action, "전체 자동관리를 잠깐 중단한 뒤 실행해줘.")
            return False
        return True

    def _require_idle(self) -> bool:
        if self.busy:
            messagebox.showinfo("작업 중", "현재 점검 작업이 끝난 뒤 다시 눌러줘.")
            return False
        return True

    def add_room(self) -> None:
        if not self._require_stopped("방 추가"):
            return
        dialog = RoomDialog(self.root)
        self.root.wait_window(dialog)
        if dialog.result is None:
            return
        self.config.rooms.append(dialog.result)
        self._save_config()
        self._refresh_table(select_id=dialog.result.id)
        self.detail_var.set("방을 저장했어. 자동 시작 전에 ‘안전 인식 점검’을 권장해.")

    def edit_room(self) -> None:
        if not self._require_stopped("방 수정"):
            return
        room = self._selected_room()
        if room is None:
            messagebox.showinfo("방 수정", "수정할 방을 먼저 선택해줘.")
            return
        dialog = RoomDialog(self.root, room)
        self.root.wait_window(dialog)
        if dialog.result is None:
            return
        self.config.rooms = [dialog.result if item.id == room.id else item for item in self.config.rooms]
        self._save_config()
        self._refresh_table(select_id=room.id)

    def delete_room(self) -> None:
        if not self._require_stopped("방 삭제"):
            return
        room = self._selected_room()
        if room is None:
            messagebox.showinfo("방 삭제", "삭제할 방을 먼저 선택해줘.")
            return
        if not messagebox.askyesno("방 삭제", f"‘{room.title}’ 자동관리 설정을 삭제할까?"):
            return
        self.config.rooms = [item for item in self.config.rooms if item.id != room.id]
        self._save_config()
        store = StateStore(self.state_path)
        store.remove(room.id)
        store.save()
        self._refresh_table()

    def settings(self) -> None:
        if not self._require_stopped("환경 설정"):
            return
        dialog = SettingsDialog(self.root, self.config)
        self.root.wait_window(dialog)
        if dialog.result is None:
            return
        self.config = dialog.result
        self._save_config()
        self.detail_var.set("환경 설정을 저장했어. ‘환경 점검’으로 연결 상태를 확인해.")

    def start(self) -> None:
        if self.worker and self.worker.is_alive():
            return
        if not self._require_idle():
            return
        self._reload_config()
        if not any(room.enabled for room in self.config.rooms):
            messagebox.showwarning("전체 시작", "자동관리할 방이 없어. 방을 추가하거나 사용 체크를 켜줘.")
            return
        self.stop_event.clear()
        self.engine = VoiceRoomEngine(self.config, self.state_path, self.log_path, self.snapshot_dir)
        self.worker = threading.Thread(target=self._run_worker, daemon=True)
        self.worker.start()
        keep_windows_awake(True)
        self.start_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        self.master_status.configure(text="● 시작 중")
        self.detail_var.set("ADB 기기와 카카오톡 상태를 확인한 뒤 자동관리를 시작할게.")

    def _run_worker(self) -> None:
        try:
            assert self.engine is not None
            self.engine.run_forever(self.stop_event)
            self.root.after(0, lambda: self._worker_finished(""))
        except Exception as exc:
            self.root.after(0, lambda: self._worker_finished(f"{type(exc).__name__}: {exc}"))

    def _worker_finished(self, error: str) -> None:
        keep_windows_awake(False)
        self.start_btn.configure(state="normal")
        self.stop_btn.configure(state="disabled")
        self.master_status.configure(text="● 중지됨")
        if error:
            self.detail_var.set(f"자동관리 중단: {error}")
            messagebox.showerror("자동관리 오류", error)
        else:
            self.detail_var.set("자동관리가 중단됐어.")

    def stop(self) -> None:
        self.stop_event.set()
        self.master_status.configure(text="● 중단 중")
        self.detail_var.set("현재 ADB 작업이 끝나는 즉시 중단할게.")

    def preflight(self) -> None:
        if not self._require_stopped("환경 점검") or not self._require_idle():
            return
        self.busy = True
        self.detail_var.set("ADB·카카오톡·UI 접근을 점검하는 중...")
        threading.Thread(target=self._preflight_worker, daemon=True).start()

    def _preflight_worker(self) -> None:
        try:
            self._reload_config()
            engine = VoiceRoomEngine(self.config, self.state_path, self.log_path, self.snapshot_dir)
            result = engine.preflight()
            if result.ok:
                text = f"환경 정상 · {result.model} · {result.device_serial} · 카카오톡 설치 확인 · UI 접근 확인"
                if result.foreground_package:
                    text += f" · 현재 화면 {result.foreground_package}"
            else:
                text = f"환경 점검 실패 · {result.detail}"
        except Exception as exc:
            text = f"환경 점검 실패: {type(exc).__name__}: {exc}"
        self.root.after(0, lambda: self._finish_busy(text))

    def inspect_selected(self) -> None:
        if not self._require_stopped("안전 인식 점검") or not self._require_idle():
            return
        room = self._selected_room()
        if room is None:
            messagebox.showinfo("안전 인식 점검", "점검할 방을 먼저 선택해줘.")
            return
        self.busy = True
        self.detail_var.set(f"‘{room.title}’ 방과 보이스룸 메뉴를 안전하게 확인하는 중...")
        threading.Thread(target=self._inspect_worker, args=(room.id,), daemon=True).start()

    def _inspect_worker(self, room_id: str) -> None:
        try:
            self._reload_config()
            engine = VoiceRoomEngine(self.config, self.state_path, self.log_path, self.snapshot_dir)
            room = next(item for item in self.config.rooms if item.id == room_id)
            result = engine.inspect_room(room)
            text = f"{room.title}: {result.status} · {result.detail}"
        except Exception as exc:
            text = f"인식 점검 실패: {type(exc).__name__}: {exc}"
        self.root.after(0, lambda: self._finish_busy(text))

    def probe_selected(self) -> None:
        if not self._require_stopped("실제 재개설 테스트") or not self._require_idle():
            return
        room = self._selected_room()
        if room is None:
            messagebox.showinfo("실제 재개설 테스트", "테스트할 방을 먼저 선택해줘.")
            return
        if not messagebox.askyesno("실제 재개설 테스트", "이 테스트는 보이스룸이 꺼져 있으면 실제로 새 보이스룸을 만들 수 있어.\n‘안전 인식 점검’이 먼저 성공했고 지금 실제 개설을 테스트해도 괜찮을 때만 계속할까?"):
            return
        self.busy = True
        self.detail_var.set(f"‘{room.title}’ 실제 보이스룸 상태/개설 동작을 테스트하는 중...")
        threading.Thread(target=self._probe_worker, args=(room.id,), daemon=True).start()

    def _probe_worker(self, room_id: str) -> None:
        try:
            self._reload_config()
            engine = VoiceRoomEngine(self.config, self.state_path, self.log_path, self.snapshot_dir)
            engine.prepare()
            room = next(item for item in self.config.rooms if item.id == room_id)
            result = engine.process_room(room)
            text = f"{room.title}: {STATUS_LABELS.get(result.status, result.status)} · {result.detail}"
        except Exception as exc:
            text = f"실제 테스트 실패: {type(exc).__name__}: {exc}"
        self.root.after(0, lambda: self._finish_busy(text))

    def snapshot(self) -> None:
        if not self._require_stopped("진단 캡처") or not self._require_idle():
            return
        self.busy = True
        self.detail_var.set("현재 Android 화면과 UI 구조를 저장하는 중...")
        threading.Thread(target=self._snapshot_worker, daemon=True).start()

    def _snapshot_worker(self) -> None:
        try:
            self._reload_config()
            engine = VoiceRoomEngine(self.config, self.state_path, self.log_path, self.snapshot_dir)
            xml_path, png_path = engine.snapshot("manual")
            text = f"진단 저장 완료 · {xml_path.name} · {png_path.name}"
        except Exception as exc:
            text = f"진단 캡처 실패: {type(exc).__name__}: {exc}"
        self.root.after(0, lambda: self._finish_busy(text))

    def open_diagnostics(self) -> None:
        self.snapshot_dir.mkdir(parents=True, exist_ok=True)
        try:
            if os.name == "nt":
                os.startfile(self.snapshot_dir)  # type: ignore[attr-defined]
            elif sys_platform() == "darwin":
                subprocess.Popen(["open", str(self.snapshot_dir)])
            else:
                subprocess.Popen(["xdg-open", str(self.snapshot_dir)])
        except Exception as exc:
            messagebox.showerror("진단 폴더", f"폴더를 열지 못했어: {exc}")

    def _finish_busy(self, text: str) -> None:
        self.busy = False
        self.detail_var.set(text)
        self._refresh_table()

    def _show_selected_detail(self) -> None:
        room = self._selected_room()
        if room is None:
            return
        store = StateStore(self.state_path)
        state = store.get(room.id)
        if state.last_error:
            self.detail_var.set(f"{room.title} · 최근 오류: {state.last_error}")
        else:
            self.detail_var.set(f"{room.title} · 자동관리 {'ON' if room.enabled else 'OFF'} · 최근 확인 {format_ts(state.last_verified_at)}")

    def _refresh_table(self, select_id: str | None = None) -> None:
        store = StateStore(self.state_path)
        current = select_id
        if current is None:
            selected = self.tree.selection()
            current = selected[0] if selected else None
        known_ids = {room.id for room in self.config.rooms}
        for iid in self.tree.get_children():
            if iid not in known_ids:
                self.tree.delete(iid)
        now = time.time()
        for room in self.config.rooms:
            state = store.get(room.id)
            values = ("ON" if room.enabled else "OFF", STATUS_LABELS.get(state.status, state.status), remaining_text(room, state.started_at, now), format_ts(state.next_check_at), state.failures)
            if self.tree.exists(room.id):
                self.tree.item(room.id, text=room.title, values=values)
            else:
                self.tree.insert("", "end", iid=room.id, text=room.title, values=values)
        if current and self.tree.exists(current):
            self.tree.selection_set(current)
        running = bool(self.worker and self.worker.is_alive() and not self.stop_event.is_set())
        if running:
            self.master_status.configure(text="● 자동관리 실행 중")
        elif self.worker and self.worker.is_alive():
            self.master_status.configure(text="● 중단 중")

    def _tick(self) -> None:
        try:
            self._reload_config()
            self._refresh_table()
        except Exception as exc:
            self.detail_var.set(f"상태 새로고침 오류: {exc}")
        self.root.after(1000, self._tick)

    def _on_close(self) -> None:
        self.stop_event.set()
        keep_windows_awake(False)
        self.root.destroy()


def sys_platform() -> str:
    import sys
    return sys.platform


def main() -> None:
    root = tk.Tk()
    config_path = Path.cwd() / "voiceroom_config.json"
    VoiceRoomApp(root, config_path)
    root.mainloop()


if __name__ == "__main__":
    main()
