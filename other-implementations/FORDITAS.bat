@echo off
echo ========================================
echo  Steam Switcher - Forditas
echo ========================================
echo.

:: .NET SDK ellenorzese
dotnet --version >nul 2>&1
if errorlevel 1 (
    echo [HIBA] A .NET SDK nincs telepitve!
    echo.
    echo Telepites: https://dotnet.microsoft.com/download
    echo Valaszd a ".NET 8.0 SDK" opciót, futtasd le,
    echo majd indítsd ujra ezt a .bat fajlt.
    echo.
    pause
    exit /b 1
)

echo [OK] .NET SDK megtalálva
echo.
echo Forditas folyamatban... (1-2 perc)
echo.

dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o "%~dp0dist"

if errorlevel 1 (
    echo.
    echo [HIBA] A forditas sikertelen volt!
    pause
    exit /b 1
)

echo.
echo ========================================
echo  KESZ! A program itt talalhato:
echo  %~dp0dist\SteamSwitcher.exe
echo ========================================
echo.
explorer "%~dp0dist"
pause
