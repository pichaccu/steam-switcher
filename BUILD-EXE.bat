@echo off
echo ==========================================
echo  Steam Switcher - Small EXE (release)
echo  Single .exe; requires installed Java to RUN.
echo ==========================================

javac -version >nul 2>&1
if errorlevel 1 ( echo [ERROR] A JDK is required (not just a JRE). & pause & exit /b 1 )

:: --- 1) Build the JAR ---
if exist build rd /s /q build
mkdir build\resources\emoji
if exist steam_small.png ( copy steam_small.png build\resources\steam.png >nul ) else ( copy steam.png build\resources\steam.png >nul )
if exist emoji xcopy emoji build\resources\emoji /e /i /q >nul
echo [1/2] Compiling...
javac --release 8 -encoding UTF-8 -d build SteamSwitcher.java
if errorlevel 1 ( echo [ERROR] Compilation failed. & rd /s /q build & pause & exit /b 1 )
jar --create --file SteamSwitcher.jar --main-class SteamSwitcher -C build .
rd /s /q build

:: --- 2) Wrap into a single .exe with Launch4j (downloaded on first use) ---
if not exist launch4j\launch4jc.exe (
    echo Downloading Launch4j...
    curl -L --retry 3 -o launch4j.zip https://downloads.sourceforge.net/project/launch4j/launch4j-3/3.50/launch4j-3.50-win32.zip
    if errorlevel 1 ( echo [ERROR] Could not download Launch4j. & pause & exit /b 1 )
    powershell -NoProfile -Command "Expand-Archive -Path launch4j.zip -DestinationPath . -Force"
)
echo [2/2] Wrapping into SteamSwitcher.exe...
launch4j\launch4jc.exe launch4j-config.xml
if errorlevel 1 ( echo [ERROR] Launch4j failed. & pause & exit /b 1 )

echo.
echo ==========================================
echo  DONE!  SteamSwitcher.exe
echo ==========================================
pause
