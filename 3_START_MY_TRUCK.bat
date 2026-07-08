@echo off
title TrueMPG - MY TRUCK (OBDLink MX+)
echo ============================================
echo   TrueMPG - LIVE on your F-150 (MX+)
echo ============================================
echo.
echo BEFORE RUNNING:
echo   1. MX+ plugged into the truck, ignition ON
echo   2. MX+ paired in Windows Bluetooth
echo   3. Change COM5 below to YOUR outgoing port
echo      (Bluetooth settings ^> More options ^> COM Ports tab
echo       ^> the "Outgoing" OBDLink port)
echo.
echo Browser opens to 127.0.0.1:5000
echo If it won't connect, try  3b_START_MY_TRUCK_SLOWER
echo To STOP: close this window.
echo ============================================
echo.
python "%~dp0truempg_full.py" --port COM5
pause
