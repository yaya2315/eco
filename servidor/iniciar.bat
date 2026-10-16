@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Eco
call ".venv\Scripts\activate.bat"
python eco_servidor.py %*
pause
