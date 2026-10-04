from __future__ import annotations

import ctypes
import os


ES_CONTINUOUS = 0x80000000
ES_SYSTEM_REQUIRED = 0x00000001


def keep_windows_awake(enabled: bool) -> bool:
    """Prevent Windows idle sleep while still allowing the physical display to turn off."""
    if os.name != "nt":
        return False
    flags = ES_CONTINUOUS | (ES_SYSTEM_REQUIRED if enabled else 0)
    result = ctypes.windll.kernel32.SetThreadExecutionState(flags)
    return bool(result)
