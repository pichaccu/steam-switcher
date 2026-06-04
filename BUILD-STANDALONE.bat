@echo off
echo ==========================================
echo  Steam Switcher - Standalone EXE (release)
echo  No installed Java required to RUN it.
echo ==========================================

javac -version >nul 2>&1
if errorlevel 1 ( echo [ERROR] A JDK is required (not just a JRE). & pause & exit /b 1 )

:: --- 1) Build the JAR ---
if exist build rd /s /q build
mkdir build\resources\emoji
if exist steam_small.png ( copy steam_small.png build\resources\steam.png >nul ) else ( copy steam.png build\resources\steam.png >nul )
if exist emoji xcopy emoji build\resources\emoji /e /i /q >nul
echo [1/3] Compiling...
javac --release 8 -encoding UTF-8 -d build SteamSwitcher.java
if errorlevel 1 ( echo [ERROR] Compilation failed. & rd /s /q build & pause & exit /b 1 )
jar --create --file SteamSwitcher.jar --main-class SteamSwitcher -C build .
rd /s /q build

:: --- 2) Trimmed runtime (jlink) ---
echo [2/3] Building minimal Java runtime...
if exist runtime-min rd /s /q runtime-min
jlink --add-modules java.desktop --strip-debug --no-header-files --no-man-pages --compress=2 --output runtime-min
if errorlevel 1 ( echo [ERROR] jlink failed. & pause & exit /b 1 )
:: GraalVM only: drop the large unused JIT compiler (harmless if absent)
if exist runtime-min\bin\jvmcicompiler.dll del runtime-min\bin\jvmcicompiler.dll

:: --- 3) Package the app-image ---
echo [3/3] Packaging standalone EXE...
if exist dist-standalone rd /s /q dist-standalone
if exist _appinput rd /s /q _appinput
mkdir _appinput
copy SteamSwitcher.jar _appinput\ >nul
jpackage --type app-image --name SteamSwitcher --input _appinput --main-jar SteamSwitcher.jar ^
  --main-class SteamSwitcher --icon steam.ico --runtime-image runtime-min ^
  --java-options "-XX:-UseJVMCICompiler" --java-options "-XX:-UseJVMCINativeLibrary" ^
  --dest dist-standalone
set RC=%errorlevel%
rd /s /q _appinput
rd /s /q runtime-min
if not "%RC%"=="0" ( echo [ERROR] jpackage failed. & pause & exit /b 1 )

echo.
echo ==========================================
echo  DONE!  dist-standalone\SteamSwitcher\SteamSwitcher.exe
echo  (move the whole SteamSwitcher folder together)
echo ==========================================
pause
