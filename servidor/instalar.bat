@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo   Instalando Eco (servidor)...
echo.
where py >nul 2>nul
if %errorlevel%==0 (
    py -3.12 -m venv .venv 2>nul || py -3 -m venv .venv
) else (
    python -m venv .venv
)
if not exist ".venv\Scripts\python.exe" (
    echo   No encontre Python. Instala Python 3.12 desde python.org marcando "Add python.exe to PATH".
    pause
    exit /b 1
)
call ".venv\Scripts\activate.bat"
python -m pip install --upgrade pip
pip install -r requirements.txt
if not exist config.toml copy config.example.toml config.toml >nul
echo.
echo   Listo. Abre config.toml, llena tus datos y luego ejecuta iniciar.bat
echo.
pause
