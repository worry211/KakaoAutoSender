from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import time


class AdbError(RuntimeError):
    pass


@dataclass
class AdbResult:
    stdout: str
    stderr: str
    returncode: int


@dataclass
class DeviceInfo:
    serial: str
    state: str
    model: str = ""
    product: str = ""
    device: str = ""

    @property
    def ready(self) -> bool:
        return self.state == "device"

    def label(self) -> str:
        parts = [self.serial]
        if self.model:
            parts.append(self.model.replace("_", " "))
        if self.state != "device":
            parts.append(f"[{self.state}]")
        return " · ".join(parts)


class Adb:
    def __init__(self, adb_path: str = "adb", serial: str = ""):
        self.adb_path = adb_path
        self.serial = serial.strip()

    @staticmethod
    def list_devices(adb_path: str = "adb") -> list[DeviceInfo]:
        try:
            proc = subprocess.run(
                [adb_path, "devices", "-l"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=15,
            )
        except FileNotFoundError as exc:
            raise AdbError(f"ADB 실행 파일을 찾지 못함: {adb_path}") from exc
        except subprocess.TimeoutExpired as exc:
            raise AdbError("ADB 기기 목록 조회 시간 초과") from exc
        if proc.returncode != 0:
            raise AdbError(proc.stderr.strip() or proc.stdout.strip() or "adb devices failed")

        devices: list[DeviceInfo] = []
        for raw_line in proc.stdout.splitlines():
            line = raw_line.strip()
            if not line or line.startswith("List of devices"):
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            serial, state = parts[0], parts[1]
            meta: dict[str, str] = {}
            for token in parts[2:]:
                if ":" in token:
                    key, value = token.split(":", 1)
                    meta[key] = value
            devices.append(
                DeviceInfo(
                    serial=serial,
                    state=state,
                    model=meta.get("model", ""),
                    product=meta.get("product", ""),
                    device=meta.get("device", ""),
                )
            )
        return devices

    def auto_select_device(self) -> DeviceInfo:
        devices = self.list_devices(self.adb_path)
        ready = [d for d in devices if d.ready]
        if self.serial:
            match = next((d for d in devices if d.serial == self.serial), None)
            if match is None:
                raise AdbError(f"설정된 ADB 기기를 찾지 못함: {self.serial}")
            if not match.ready:
                raise AdbError(f"설정된 ADB 기기가 준비되지 않음: {match.serial} ({match.state})")
            return match
        if not ready:
            if devices:
                detail = ", ".join(f"{d.serial}={d.state}" for d in devices)
                raise AdbError(f"사용 가능한 ADB 기기가 없음: {detail}")
            raise AdbError("ADB 기기가 연결되어 있지 않음")
        if len(ready) > 1:
            detail = ", ".join(d.label() for d in ready)
            raise AdbError(f"ADB 기기가 여러 개라 자동 선택할 수 없음: {detail}")
        self.serial = ready[0].serial
        return ready[0]

    def _base(self) -> list[str]:
        out = [self.adb_path]
        if self.serial:
            out += ["-s", self.serial]
        return out

    def run(self, *args: str, timeout: int = 30, check: bool = True) -> AdbResult:
        try:
            proc = subprocess.run(
                self._base() + list(args),
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout,
            )
        except FileNotFoundError as exc:
            raise AdbError(f"ADB 실행 파일을 찾지 못함: {self.adb_path}") from exc
        except subprocess.TimeoutExpired as exc:
            raise AdbError(f"ADB 명령 시간 초과: {' '.join(args)}") from exc
        result = AdbResult(proc.stdout.strip(), proc.stderr.strip(), proc.returncode)
        if check and proc.returncode != 0:
            raise AdbError(result.stderr or result.stdout or f"adb failed: {proc.returncode}")
        return result

    def exec_out_bytes(self, *args: str, timeout: int = 30) -> bytes:
        try:
            proc = subprocess.run(
                self._base() + ["exec-out"] + list(args),
                capture_output=True,
                timeout=timeout,
            )
        except FileNotFoundError as exc:
            raise AdbError(f"ADB 실행 파일을 찾지 못함: {self.adb_path}") from exc
        except subprocess.TimeoutExpired as exc:
            raise AdbError("ADB 바이너리 출력 시간 초과") from exc
        if proc.returncode != 0:
            raise AdbError(proc.stderr.decode("utf-8", "replace"))
        return proc.stdout

    def ensure_device(self) -> str:
        info = self.auto_select_device()
        result = self.run("get-state")
        if result.stdout.strip() != "device":
            raise AdbError(f"ADB device is not ready: {result.stdout or result.stderr}")
        model = self.run("shell", "getprop", "ro.product.model").stdout.strip()
        return model or info.model or "Android"

    def package_installed(self, package: str) -> bool:
        out = self.run("shell", "pm", "path", package, check=False).stdout
        return any(line.startswith("package:") for line in out.splitlines())

    def foreground_package(self) -> str:
        out = self.run("shell", "dumpsys", "window", "windows", check=False, timeout=20).stdout
        patterns = (
            r"mCurrentFocus=.*?\s([A-Za-z0-9._]+)/",
            r"mFocusedApp=.*?\s([A-Za-z0-9._]+)/",
        )
        for pattern in patterns:
            match = re.search(pattern, out)
            if match:
                return match.group(1)
        return ""

    def wake_and_keep_awake(self, keep_awake: bool = True) -> None:
        self.run("shell", "input", "keyevent", "224", check=False)
        if keep_awake:
            self.run("shell", "svc", "power", "stayon", "true", check=False)

    def mute_audio(self) -> None:
        for stream in ("3", "0", "5"):
            self.run("shell", "cmd", "media_session", "volume", "--stream", stream, "--set", "0", check=False)

    def launch_package(self, package: str) -> None:
        result = self.run("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1", check=False)
        if "No activities found" in (result.stdout + result.stderr):
            raise AdbError(f"Cannot launch package {package}")

    def open_url(self, url: str) -> None:
        self.run("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", url, timeout=20, check=False)

    def back(self) -> None:
        self.run("shell", "input", "keyevent", "4", check=False)

    def tap(self, x: int, y: int) -> None:
        self.run("shell", "input", "tap", str(x), str(y))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 350) -> None:
        self.run("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms))

    def screen_size(self) -> tuple[int, int]:
        out = self.run("shell", "wm", "size").stdout
        match = re.search(r"(\d+)x(\d+)", out)
        if not match:
            return 1080, 1920
        return int(match.group(1)), int(match.group(2))

    def dump_ui(self) -> str:
        remote = "/sdcard/voiceroom-window.xml"
        self.run("shell", "uiautomator", "dump", "--compressed", remote, timeout=20)
        return self.run("exec-out", "cat", remote, timeout=20).stdout

    def snapshot(self, directory: str | Path, prefix: str) -> tuple[Path, Path]:
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", prefix)[:60] or "snapshot"
        xml_path = directory / f"{stamp}-{safe}.xml"
        png_path = directory / f"{stamp}-{safe}.png"
        xml_path.write_text(self.dump_ui(), encoding="utf-8")
        png_path.write_bytes(self.exec_out_bytes("screencap", "-p"))
        return xml_path, png_path
