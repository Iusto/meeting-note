@echo off
setlocal
title AI Meeting - Start
echo Preparing AI Meeting. Keep Docker Desktop running and Tailscale signed in.
echo The first run downloads Whisper large-v3. Run this when no recording is active.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-private.ps1" -EnableAi -OpenMeeting
if errorlevel 1 (
    echo.
    echo Startup failed. Read the error above, fix it, and run this file again.
    pause
    exit /b 1
)
echo.
echo Ready. In the browser: sign in, select local AI transcription or upload a recording.
echo On another PC: open the HTTPS address printed above with Tailscale connected.
pause
endlocal
