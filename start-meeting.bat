@echo off
setlocal
title AI Meeting - Start
echo Preparing AI Meeting. Keep Docker Desktop running and Tailscale signed in.
echo Builds may take a few minutes. Do not run this during an active recording.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-private.ps1" -EnablePaidAi -OpenMeeting
if errorlevel 1 (
    echo.
    echo Startup failed. Read the error above, fix it, and run this file again.
    pause
    exit /b 1
)
echo.
echo Ready. In the browser: sign in, check Usage budget, select LIVE, then start recording.
echo On another PC: open the HTTPS address printed above with Tailscale connected.
pause
endlocal
