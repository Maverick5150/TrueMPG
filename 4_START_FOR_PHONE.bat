@echo off
title TrueMPG - PHONE MODE
echo ============================================
echo   TrueMPG - view on your PHONE over wifi
echo ============================================
echo.
echo 1. Phone and this PC on the SAME wifi.
echo 2. This PC's IP prints below (after "IPv4").
echo    Looks like 192.168.x.x
echo 3. On your phone browser go to:  that-IP:5000
echo    Example:  192.168.1.24:5000
echo.
echo Set COM5 to your MX+ outgoing port if needed.
echo To STOP: close this window.
echo ============================================
echo.
ipconfig | findstr /C:"IPv4"
echo.
python "%~dp0truempg_full.py" --port COM5 --host 0.0.0.0
pause
