@echo off
setlocal
cd /d "%~dp0"

where py >nul 2>nul
if %errorlevel%==0 (
  set "PY=py -3"
) else (
  set "PY=python"
)

if not exist "voiceroom_config.json" (
  copy /Y "config.example.json" "voiceroom_config.json" >nul
  echo [SETUP] voiceroom_config.json created.
  echo [SETUP] Edit rooms before unattended use.
  notepad "voiceroom_config.json"
  exit /b 0
)

%PY% -m voiceroom_manager.cli --config "voiceroom_config.json" run
if errorlevel 1 (
  echo.
  echo [ERROR] VoiceRoom Manager stopped with an error.
  pause
)
