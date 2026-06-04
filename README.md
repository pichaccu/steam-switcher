# Steam Account Switcher

A lightweight **Steam account switcher** for Windows. Switch between your
remembered Steam accounts with one click and start Steam with the selected
account. Plus: a **quick-launch game** button, **colored emoji** in account
names, a per-account **last login** timestamp, a **multi-language UI**, and a
built-in **updater**.

> ⚠️ **This project was generated entirely by AI.**
> See [AI transparency](#-ai-transparency) and
> [Security](#-security-no-hidden-behavior) below.

---

## ✨ Features

- Lists Steam accounts from the local `loginusers.vdf` (passwords are **never** handled).
- One-click / double-click account switching (Steam restarts with the chosen account).
- Delete an account from Steam's login list.
- **Quick-launch game** button (which game is configurable in Settings; default CS2 / appid 730).
- **Colored emoji** in account names (bundled [Twemoji](#-license--attribution) images,
  because Swing on its own would only draw empty boxes).
- **Last login** timestamp stored per account (local cache file).
- **Multi-language UI** – defaults to the Windows language, falls back to English.
  Bundled languages: English, Hungarian, German, Spanish, French, Italian,
  Portuguese, Russian, Polish, Chinese, Japanese.
- **In-app updater** that pulls the latest release from GitHub over HTTPS (no git required).
- Zero external dependencies (Java standard library only).

---

## 🖥️ Requirements

- **Windows** (with Steam installed).
- To run the `.jar`: an **installed Java** (version 8 or newer).
  - For the `.exe` variants see [Build](#-build).
- Accounts must already be **"remembered"** in Steam (the app only changes which
  one is set to auto-login; it never enters credentials).

---

## 🚀 Usage

1. Start `SteamSwitcher.jar` (double-click) or one of the compiled `.exe` files.
2. Pick an account from the list.
3. **Log in** (or double-click) → Steam restarts with the selected account.
4. Use the **🎮 quick-launch** button (top-right) to start the configured game.
5. Open **Settings** to change the UI language, the quick-launch game (App ID),
   the `steam.exe` path, or to **check for updates**.

---

## 🔨 Build

Build outputs (`.jar`, `.exe`, `dist-standalone/`) are **not** version-controlled;
they can be produced any time from the sources below.

### 1) JAR (smallest; requires installed Java)
```bat
BUILD.bat
```
Output: `SteamSwitcher.jar` (~4–5 MB, including the bundled emoji set).

### 2) Small .exe (requires installed Java)
Needs [Launch4j](https://launch4j.sourceforge.net/) (download and unzip it into a
`launch4j/` folder in the repo root – it is not version-controlled):
```bat
launch4j\launch4jc.exe launch4j-config.xml
```
Output: `SteamSwitcher.exe` (~5 MB; the jar is embedded, uses the installed Java).

### 3) Standalone .exe (no installed Java needed)
Uses the JDK's own `jpackage` + `jlink` (JDK 17+ recommended):
```bash
jlink --add-modules java.desktop --strip-debug --no-header-files --no-man-pages --compress=2 --output runtime-min
del runtime-min\bin\jvmcicompiler.dll     :: GraalVM only: drop the ~42 MB unused JIT
mkdir _appinput & copy SteamSwitcher.jar _appinput\
jpackage --type app-image --name SteamSwitcher --input _appinput --main-jar SteamSwitcher.jar ^
  --main-class SteamSwitcher --icon steam.ico --runtime-image runtime-min ^
  --java-options "-XX:-UseJVMCICompiler" --java-options "-XX:-UseJVMCINativeLibrary" ^
  --dest dist-standalone
```
Output: a `dist-standalone/SteamSwitcher/` folder (~50 MB, with an embedded Java runtime).

---

## 🔒 Security (no hidden behavior)

The full source is available here and auditable. The app performs **only the
following documented, local operations**:

- **Reads:** `loginusers.vdf` (Steam account list) and the Steam install path from
  the registry (`reg query`, read-only).
- **Writes / modifies when switching accounts:**
  - flags in `loginusers.vdf` (`RememberPassword`, `MostRecent`, `AllowAutoLogin`),
  - registry auto-login values: `HKCU\Software\Valve\Steam` → `AutoLoginUser`, `RememberPassword`.
- **Processes:** stops Steam gracefully (`steam.exe -shutdown`, falling back to `taskkill`),
  then starts it again (`steam.exe -login <account>`); the quick-launch button runs
  `steam.exe -applaunch <appid>`.
- **Network:** only the **Settings → Check for updates** action contacts GitHub over
  HTTPS to look for a newer release; nothing else makes network calls.
- **Local cache files** (in your user profile): `~/.steamswitcher.properties` (settings)
  and `~/.steamswitcher_lastlogin.properties` (last-login times).

What it does **not** do:

- ❌ No telemetry, analytics, or background services.
- ❌ **No password handling or storage** – Steam itself authenticates; the app only
  selects which remembered account logs in.
- ❌ No data sent to any third party.

It uses only the Java standard library, so the code can be reviewed end to end.

---

## 🤖 AI transparency

This project was **generated 100% by AI**, disclosed here for full transparency:

- **Model:** Anthropic **Claude (Claude Opus 4.8)**
- **Agent / tool:** **Claude Code**
- **Human role:** direction and requirements; the code was written, built, and tested by the AI.

---

## 🧩 Other implementations

The `other-implementations/` folder contains earlier/alternative takes on the same idea:

- `Program.cs`, `SteamSwitcher.csproj`, `FORDITAS.bat` – C# (.NET) version
- `steam_switcher.py` – Python version
- `SteamSwitcher.vbs` – VBScript version

The maintained, primary version is the **Java** app (`SteamSwitcher.java`).

---

## 📜 License / attribution

- The emoji images come from the **[Twemoji](https://github.com/jdecked/twemoji)**
  project. Twemoji graphics are licensed under **CC-BY 4.0** (© Twitter / the jdecked
  twemoji community). The code is under the **MIT** license.
- This project is not affiliated with Valve or Steam; "Steam" and "CS2" are trademarks
  of their respective owners.
