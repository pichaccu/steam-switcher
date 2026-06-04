@echo off
echo ================================
echo  Steam Switcher - JAR Build
echo ================================

:: Check for the JDK
javac -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] javac not found - a JDK is required, not just a JRE.
    echo Install a JDK: https://adoptium.net
    pause & exit /b 1
)

:: Clean build folder
if exist build rd /s /q build
mkdir build\resources

:: Window icon into the JAR (use the small one if present, otherwise the full image)
if exist steam_small.png (
    copy steam_small.png build\resources\steam.png >nul
) else (
    copy steam.png build\resources\steam.png >nul
)

:: Twemoji images into the JAR (colored emoji in the account list)
if exist emoji (
    mkdir build\resources\emoji
    xcopy emoji build\resources\emoji /e /i /q >nul
)

:: Compile (UTF-8 source, Java 8 target so any installed Java 8+ can run it)
echo [1/3] Compiling...
javac --release 8 -encoding UTF-8 -d build SteamSwitcher.java
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    pause & exit /b 1
)

:: Package the JAR (Main-Class set, so no separate manifest is needed)
echo [2/3] Packaging JAR...
jar --create --file SteamSwitcher.jar --main-class SteamSwitcher -C build .
if errorlevel 1 (
    echo [ERROR] JAR packaging failed.
    pause & exit /b 1
)

:: Cleanup
echo [3/3] Cleaning up...
rd /s /q build

echo.
echo ================================
echo  DONE! Run: SteamSwitcher.jar
echo  (double-click - requires installed Java)
echo ================================
pause
