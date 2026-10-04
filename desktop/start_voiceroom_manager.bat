@echo off
setlocal
cd /d "%~dp0"

where py >nul 2>nul
if %errorlevel%==0 (
  set "PY=py -3"
) else (
  set "PY=python"
)

%PY% -m voiceroom_manager.gui
if errorlevel 1 (
  echo.
  echo [ERROR] VoiceRoom Manager GUI stopped with an error.
  echo Make sure Python 3 and ADB are installed/configured.
  pause
)
