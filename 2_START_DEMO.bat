@echo off
title TrueMPG - DEMO (no adapter needed)
echo ============================================
echo   TrueMPG - DEMO MODE
echo ============================================
echo.
echo Simulated truck - works with NO adapter.
echo Your browser will open to 127.0.0.1:5000
echo To STOP: close this window.
echo ============================================
echo.
python "%~dp0truempg_full.py" --demo
pause
