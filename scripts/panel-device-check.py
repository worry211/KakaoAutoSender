"""Exercise actual shell UID panel helper on an emulator. Never installs/sends through Kakao."""
import json
import queue
import subprocess
import sys
import threading
import time
import uuid


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=20)


def session(mode):
    print(f"Panel helper scenario: {mode}", flush=True)
    token = str(uuid.uuid4())
    p = subprocess.Popen(["adb", "shell", "CLASSPATH=/data/local/tmp/kakaomacro-panel-test.apk",
                          "app_process", "/", "com.local.kakaoautosender.PanelShell", token],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, bufsize=1)
    lines = queue.Queue()
    transcript = []
    def consume():
        for line in p.stdout:
            transcript.append(line.strip())
            lines.put(line.strip())
    threading.Thread(target=consume, daemon=True).start()
    def wait(expected):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            if line == f"KMPANEL {expected} {token}":
                return
            if line.startswith("KMPANEL ERROR"):
                raise AssertionError(transcript)
        raise AssertionError(transcript)
    try:
        wait("READY")
        p.stdin.write(f"OFF {token}\n"); p.stdin.flush()
        wait("OFF")
        power = adb("shell", "dumpsys", "power")
        assert "mWakefulness=Awake" in power, "OS went to sleep instead of physical panel control"
        if mode == "stop":
            p.stdin.write(f"STOP {token}\n"); p.stdin.flush()
        elif mode == "disconnect":
            p.stdin.close()
        elif mode == "timeout":
            pass  # keep stdin open: native watchdog must terminate and restore independently
        elif mode == "controller-kill":
            ids = next(line.split() for line in transcript if line.startswith("KMPANEL PIDS "))
            adb("shell", "kill", "-9", ids[2])
        p.wait(timeout=12)
        assert p.returncode == 0 or mode == "controller-kill", transcript
        assert "KMPANEL RESTORE_FAILED" not in transcript, transcript
        deadline = time.monotonic() + 10
        while f"RESTORED {token}" not in adb("logcat", "-d", "-s", "KakaoMacroPanelGuard:I"):
            assert time.monotonic() < deadline, "Detached guardian did not acknowledge restoration"
            time.sleep(0.5)
        assert "mWakefulness=Awake" in adb("shell", "dumpsys", "power")
        return {"mode": mode, "offCommandAcknowledged": True, "osStayedAwake": True,
                "guardianRestoreAcknowledged": True, "physicalPanelVerified": False}
    finally:
        with open(f"panel-{mode}-transcript.txt", "w") as output:
            output.write("\n".join(transcript))
        with open(f"panel-{mode}-logcat.txt", "w") as output:
            output.write(adb("logcat", "-d"))
        if p.poll() is None:
            p.kill(); p.wait(timeout=5)
            # The helper watchdog remains responsible for restoring after transport loss.


adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
adb("push", sys.argv[1], "/data/local/tmp/kakaomacro-panel-test.apk")
results = [session(mode) for mode in ["stop", "disconnect", "timeout", "controller-kill"]]
with open("panel-device-results.json", "w") as output:
    json.dump({"api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
               "results": results, "kakaoPhotoReceiptVerified": False}, output, indent=2)
print("Panel helper command/lifecycle checks passed; physical panel and Kakao receipt unverified.")
