@echo off
title TrueMPG - MY TRUCK (steadier Bluetooth)
echo ============================================
echo   TrueMPG - LIVE (steadier baud for MX+)
echo ============================================
echo.
echo Use THIS one if 3_START_MY_TRUCK is flaky.
echo Same rules: MX+ paired, ignition ON, set COM5
echo to your outgoing OBDLink port below.
echo ============================================
echo.
python "%~dp0truempg_full.py" --port COM5 --baud 115200
pause
