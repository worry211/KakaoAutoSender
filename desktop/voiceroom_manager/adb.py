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


class Adb:
    def __init__(self, adb_path: str = "adb", serial: str = ""):
        self.adb_path = adb_path
        self.serial = serial.strip()

    def _base(self) -> list[str]:
        out = [self.adb_path]
        if self.serial:
            out += ["-s", self.serial]
        return out

    def run(self, *args: str, timeout: int = 30, check: bool = True) -> AdbResult:
        proc = subprocess.run(
            self._base() + list(args),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        result = AdbResult(proc.stdout.strip(), proc.stderr.strip(), proc.returncode)
        if check and proc.returncode != 0:
            raise AdbError(result.stderr or result.stdout or f"adb failed: {proc.returncode}")
        return result

    def exec_out_bytes(self, *args: str, timeout: int = 30) -> bytes:
        proc = subprocess.run(
            self._base() + ["exec-out"] + list(args),
            capture_output=True,
            timeout=timeout,
        )
        if proc.returncode != 0:
            raise AdbError(proc.stderr.decode("utf-8", "replace"))
        return proc.stdout

    def ensure_device(self) -> str:
        result = self.run("get-state")
        if result.stdout.strip() != "device":
            raise AdbError(f"ADB device is not ready: {result.stdout or result.stderr}")
        return self.run("shell", "getprop", "ro.product.model").stdout or "Android"

    def wake_and_keep_awake(self, keep_awake: bool = True) -> None:
        self.run("shell", "input", "keyevent", "224", check=False)
        if keep_awake:
            self.run("shell", "svc", "power", "stayon", "true", check=False)

    def mute_audio(self) -> None:
        for stream in ("3", "0", "5"):
            self.run(
                "shell", "cmd", "media_session", "volume",
                "--stream", stream, "--set", "0",
                check=False,
            )

    def launch_package(self, package: str) -> None:
        result = self.run(
            "shell", "monkey", "-p", package,
            "-c", "android.intent.category.LAUNCHER", "1",
            check=False,
        )
        if "No activities found" in (result.stdout + result.stderr):
            raise AdbError(f"Cannot launch package {package}")

    def open_url(self, url: str) -> None:
        self.run(
            "shell", "am", "start", "-W",
            "-a", "android.intent.action.VIEW",
            "-d", url,
            timeout=20,
            check=False,
        )

    def back(self) -> None:
        self.run("shell", "input", "keyevent", "4", check=False)

    def tap(self, x: int, y: int) -> None:
        self.run("shell", "input", "tap", str(x), str(y))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 350) -> None:
        self.run(
            "shell", "input", "swipe",
            str(x1), str(y1), str(x2), str(y2), str(duration_ms),
        )

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
