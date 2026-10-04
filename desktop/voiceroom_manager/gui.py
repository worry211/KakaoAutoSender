from __future__ import annotations

from dataclasses import replace
from datetime import datetime
from pathlib import Path
import threading
import time
import uuid
import tkinter as tk
from tkinter import messagebox, ttk

from .engine import VoiceRoomEngine
from .model import AppConfig, RoomConfig, StateStore


STATUS_LABELS = {
    "NEW": "대기",
    "ACTIVE": "실행 중",
    "CREATED": "재개설 완료",
    "ERROR": "오류",
}


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
        title_entry = ttk.Entry(wrap, textvariable=self.title_var, width=46)
        title_entry.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(4, 12))

        ttk.Label(wrap, text="오픈채팅 링크 (선택, 있으면 더 안정적)").grid(row=2, column=0, sticky="w")
        self.url_var = tk.StringVar(value=room.room_url if room else "")
        ttk.Entry(wrap, textvariable=self.url_var, width=46).grid(row=3, column=0, columnspan=2, sticky="ew", pady=(4, 12))

        self.enabled_var = tk.BooleanVar(value=room.enabled if room else True)
        ttk.Checkbutton(wrap, text="이 방 자동관리 사용", variable=self.enabled_var).grid(row=4, column=0, columnspan=2, sticky="w")

        ttk.Button(wrap, text="취소", command=self.destroy).grid(row=5, column=0, sticky="ew", pady=(16, 0), padx=(0, 5))
        ttk.Button(wrap, text="저장", command=self._save).grid(row=5, column=1, sticky="ew", pady=(16, 0), padx=(5, 0))

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
        self.result = replace(
            base,
            title=title,
            room_url=self.url_var.get().strip(),
            enabled=bool(self.enabled_var.get()),
        )
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
        self.root.geometry("1040x650")
        self.root.minsize(900, 560)

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

        ttk.Label(
            outer,
            text="PC는 켜두고 모니터만 꺼도 돼. 여러 방의 보이스룸 상태를 확인하고 종료 시 순서대로 다시 열어.",
        ).pack(anchor="w", pady=(5, 14))

        toolbar = ttk.Frame(outer)
        toolbar.pack(fill="x", pady=(0, 10))
        self.start_btn = ttk.Button(toolbar, text="전체 시작", command=self.start)
        self.start_btn.pack(side="left")
        self.stop_btn = ttk.Button(toolbar, text="전체 중단", command=self.stop, state="disabled")
        self.stop_btn.pack(side="left", padx=(7, 0))
        ttk.Separator(toolbar, orient="vertical").pack(side="left", fill="y", padx=12)
        ttk.Button(toolbar, text="방 추가", command=self.add_room).pack(side="left")
        ttk.Button(toolbar, text="수정", command=self.edit_room).pack(side="left", padx=(7, 0))
        ttk.Button(toolbar, text="삭제", command=self.delete_room).pack(side="left", padx=(7, 0))
        ttk.Separator(toolbar, orient="vertical").pack(side="left", fill="y", padx=12)
        ttk.Button(toolbar, text="선택 방 테스트", command=self.probe_selected).pack(side="left")
        ttk.Button(toolbar, text="진단 캡처", command=self.snapshot).pack(side="left", padx=(7, 0))
        ttk.Button(toolbar, text="기기 확인", command=self.device_check).pack(side="left", padx=(7, 0))

        columns = ("enabled", "status", "remaining", "next", "failures")
        self.tree = ttk.Treeview(outer, columns=columns, show="tree headings", selectmode="browse")
        self.tree.heading("#0", text="오픈채팅방")
        self.tree.heading("enabled", text="자동관리")
        self.tree.heading("status", text="상태")
        self.tree.heading("remaining", text="남은 예상시간")
        self.tree.heading("next", text="다음 확인")
        self.tree.heading("failures", text="실패")
        self.tree.column("#0", width=310, minwidth=200)
        self.tree.column("enabled", width=80, anchor="center")
        self.tree.column("status", width=110, anchor="center")
        self.tree.column("remaining", width=145, anchor="center")
        self.tree.column("next", width=160, anchor="center")
        self.tree.column("failures", width=65, anchor="center")
        self.tree.pack(fill="both", expand=True)
        self.tree.bind("<Double-1>", lambda _e: self.edit_room())

        bottom = ttk.Frame(outer)
        bottom.pack(fill="x", pady=(10, 0))
        self.detail_var = tk.StringVar(value="방을 추가한 뒤 ‘선택 방 테스트’로 실제 카카오 화면 인식을 먼저 확인해.")
        ttk.Label(bottom, textvariable=self.detail_var).pack(side="left", fill="x", expand=True)
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

    def start(self) -> None:
        if self.worker and self.worker.is_alive():
            return
        self._reload_config()
        if not any(room.enabled for room in self.config.rooms):
            messagebox.showwarning("전체 시작", "자동관리할 방이 없어. 방을 추가하거나 사용 체크를 켜줘.")
            return
        self.stop_event.clear()
        self.engine = VoiceRoomEngine(self.config, self.state_path, self.log_path, self.snapshot_dir)
        self.worker = threading.Thread(target=self._run_worker, daemon=True)
        self.worker.start()
        self.start_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        self.master_status.configure(text="● 시작 중")
        self.detail_var.set("ADB 기기와 카카오톡 상태를 확인하는 중이야.")

    def _run_worker(self) -> None:
        try:
            assert self.engine is not None
            self.engine.run_forever(self.stop_event)
            self.root.after(0, lambda: self._worker_finished(""))
        except Exception as exc:
            self.root.after(0, lambda: self._worker_finished(f"{type(exc).__name__}: {exc}"))

    def _worker_finished(self, error: str) -> None:
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
        self.detail_var.set("현재 작업이 끝나는 즉시 중단할게.")

    def device_check(self) -> None:
        if not self._require_stopped("기기 확인"):
            return
        if self.busy:
            return
        self.busy = True
        self.detail_var.set("ADB 기기를 확인하는 중...")
        threading.Thread(target=self._device_check_worker, daemon=True).start()

    def _device_check_worker(self) -> None:
        try:
            self._reload_config()
            engine = VoiceRoomEngine(self.config, self.state_path, self.log_path, self.snapshot_dir)
            model = engine.adb.ensure_device()
            text = f"기기 연결 정상: {model}"
        except Exception as exc:
            text = f"기기 확인 실패: {type(exc).__name__}: {exc}"
        self.root.after(0, lambda: self._finish_busy(text))

    def probe_selected(self) -> None:
        if not self._require_stopped("방 테스트"):
            return
        room = self._selected_room()
        if room is None:
            messagebox.showinfo("방 테스트", "테스트할 방을 먼저 선택해줘.")
            return
        if self.busy:
            return
        self.busy = True
        self.detail_var.set(f"‘{room.title}’ 카카오 화면을 확인하는 중...")
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
            text = f"테스트 실패: {type(exc).__name__}: {exc}"
        self.root.after(0, lambda: self._finish_busy(text))

    def snapshot(self) -> None:
        if not self._require_stopped("진단 캡처"):
            return
        if self.busy:
            return
        self.busy = True
        self.detail_var.set("현재 카카오 화면 진단 파일을 저장하는 중...")
        threading.Thread(target=self._snapshot_worker, daemon=True).start()

    def _snapshot_worker(self) -> None:
        try:
            self._reload_config()
            engine = VoiceRoomEngine(self.config, self.state_path, self.log_path, self.snapshot_dir)
            xml_path, png_path = engine.snapshot("gui")
            text = f"진단 저장 완료: {xml_path.name}, {png_path.name}"
        except Exception as exc:
            text = f"진단 캡처 실패: {type(exc).__name__}: {exc}"
        self.root.after(0, lambda: self._finish_busy(text))

    def _finish_busy(self, text: str) -> None:
        self.busy = False
        self.detail_var.set(text)
        self._refresh_table()

    def _refresh_table(self, select_id: str | None = None) -> None:
        current = select_id
        if current is None and self.tree.selection():
            current = self.tree.selection()[0]

        store = StateStore(self.state_path)
        known_ids = {room.id for room in self.config.rooms}
        for iid in self.tree.get_children():
            if iid not in known_ids:
                self.tree.delete(iid)

        now = time.time()
        for room in self.config.rooms:
            state = store.get(room.id)
            values = (
                "ON" if room.enabled else "OFF",
                STATUS_LABELS.get(state.status, state.status),
                remaining_text(room, state.started_at, now),
                format_ts(state.next_check_at),
                state.failures,
            )
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
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    config_path = Path.cwd() / "voiceroom_config.json"
    VoiceRoomApp(root, config_path)
    root.mainloop()


if __name__ == "__main__":
    main()
