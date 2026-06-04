"""
Steam Account Switcher
======================
Helyi GUI alkalmazás – jelszó nélküli Steam fiókváltáshoz.
Minden adat csak a te gépeden tárolódik.

Futtatás: python steam_switcher.py
Követelmény: Python 3.x (tkinter és winreg beépített Windows-on)
"""

import tkinter as tk
from tkinter import messagebox, simpledialog, filedialog
import json
import os
import re
import subprocess
import winreg
import time

# ── Konfiguráció ──────────────────────────────────────────────────────────────

ACCOUNTS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "steam_accounts.json")
STEAM_REG_PATH = r"SOFTWARE\Valve\Steam"

STEAM_EXE_DEFAULTS = [
    r"C:\Program Files (x86)\Steam\steam.exe",
    r"C:\Program Files\Steam\steam.exe",
]

VDF_DEFAULTS = [
    r"C:\Program Files (x86)\Steam\config\loginusers.vdf",
    r"C:\Program Files\Steam\config\loginusers.vdf",
]

# ── Steam megkeresése ─────────────────────────────────────────────────────────

def find_steam_exe():
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, STEAM_REG_PATH) as key:
            path, _ = winreg.QueryValueEx(key, "InstallPath")
            exe = os.path.join(path, "steam.exe")
            if os.path.exists(exe):
                return exe
    except Exception:
        pass
    for path in STEAM_EXE_DEFAULTS:
        if os.path.exists(path):
            return path
    return None

def find_vdf():
    exe = find_steam_exe()
    if exe:
        vdf = os.path.join(os.path.dirname(exe), "config", "loginusers.vdf")
        if os.path.exists(vdf):
            return vdf
    for path in VDF_DEFAULTS:
        if os.path.exists(path):
            return path
    return None

# ── Fiókok betöltése ──────────────────────────────────────────────────────────

def load_accounts():
    if not os.path.exists(ACCOUNTS_FILE):
        return []
    try:
        with open(ACCOUNTS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []

def save_accounts(accounts):
    with open(ACCOUNTS_FILE, "w", encoding="utf-8") as f:
        json.dump(accounts, f, ensure_ascii=False, indent=2)

# ── loginusers.vdf olvasása ───────────────────────────────────────────────────

def read_vdf_accounts(vdf_path):
    """Kiolvas minden AccountName-t a loginusers.vdf-ből."""
    if not vdf_path or not os.path.exists(vdf_path):
        return []
    try:
        with open(vdf_path, "r", encoding="utf-8") as f:
            content = f.read()
        names = re.findall(r'"AccountName"\s+"([^"]+)"', content)
        return names
    except Exception:
        return []

# ── loginusers.vdf módosítása ─────────────────────────────────────────────────

def patch_vdf(vdf_path, target_username):
    """
    Beállítja a target_username-hez:
      RememberPassword = 1
      MostRecent       = 1
      AllowAutoLogin   = 1
    A többi fióknál MostRecent = 0
    """
    if not vdf_path or not os.path.exists(vdf_path):
        return False
    try:
        with open(vdf_path, "r", encoding="utf-8") as f:
            content = f.read()

        # Megkeresi az egyes fiókblokkokat és módosítja a mezőket
        def patch_block(m):
            block = m.group(0)
            # Van-e AccountName ebben a blokkban?
            acc_match = re.search(r'"AccountName"\s+"([^"]+)"', block)
            if not acc_match:
                return block
            is_target = acc_match.group(1).lower() == target_username.lower()
            most_recent_val = "1" if is_target else "0"

            # RememberPassword
            if re.search(r'"RememberPassword"', block):
                block = re.sub(r'("RememberPassword"\s+)"[^"]*"', r'\g<1>"1"', block)
            else:
                block = block.rstrip("}\n\t ") + '\n\t\t"RememberPassword"\t\t"1"\n\t\t}'

            # MostRecent
            if re.search(r'"[Mm]ost[Rr]ecent"', block):
                block = re.sub(r'("[Mm]ost[Rr]ecent"\s+)"[^"]*"', f'\\g<1>"{most_recent_val}"', block)
            else:
                block = block.rstrip("}\n\t ") + f'\n\t\t"MostRecent"\t\t"{most_recent_val}"\n\t\t}}'

            # AllowAutoLogin (csak a célfiókhoz)
            if is_target:
                if re.search(r'"AllowAutoLogin"', block):
                    block = re.sub(r'("AllowAutoLogin"\s+)"[^"]*"', r'\g<1>"1"', block)
                else:
                    block = block.rstrip("}\n\t ") + '\n\t\t"AllowAutoLogin"\t\t"1"\n\t\t}'

            return block

        # Minden fiókblokk: a SteamID sor utáni { ... } rész
        patched = re.sub(
            r'\{[^{}]*"AccountName"[^{}]*\}',
            patch_block,
            content,
            flags=re.DOTALL
        )

        with open(vdf_path, "w", encoding="utf-8") as f:
            f.write(patched)
        return True
    except Exception as e:
        messagebox.showerror("VDF hiba", f"Nem sikerült módosítani a loginusers.vdf fájlt:\n{e}")
        return False

# ── loginusers.vdf – fiók eltávolítása ───────────────────────────────────────

def remove_from_vdf(vdf_path, target_username):
    """Teljesen eltávolítja a target_username fiókblokkját a loginusers.vdf-ből."""
    if not vdf_path or not os.path.exists(vdf_path):
        return False
    try:
        with open(vdf_path, "r", encoding="utf-8") as f:
            content = f.read()

        # Megkeresi azt a SteamID blokkot amelynek AccountName = target_username
        # Formátum: "76561xxxxxxxxx"\n\t{\n\t\t...\n\t}
        pattern = r'"[0-9]+"\s*\{[^{}]*"AccountName"\s+"' + re.escape(target_username) + r'"[^{}]*\}'
        new_content = re.sub(pattern, "", content, flags=re.DOTALL | re.IGNORECASE)

        # Ha nem változott semmi, próbálj whitespace-tűrőbb keresést
        if new_content == content:
            # Soronként keresés: megkeresi a blokkot manuálisan
            lines = content.splitlines(keepends=True)
            result = []
            i = 0
            while i < len(lines):
                # Fiókblokk kezdete: csak egy SteamID szám idézőjelben
                if re.match(r'\s*"[0-9]{10,}"\s*$', lines[i]):
                    block = lines[i]
                    j = i + 1
                    # Megkeresi a nyitó { -t
                    while j < len(lines) and lines[j].strip() == '{':
                        block += lines[j]
                        j += 1
                        break
                    # Összegyűjti a blokk tartalmát a záró }-ig
                    depth = 1
                    while j < len(lines) and depth > 0:
                        block += lines[j]
                        if '{' in lines[j]:
                            depth += 1
                        if '}' in lines[j]:
                            depth -= 1
                        j += 1
                    # Ha ebben a blokkban szerepel a célfiók neve, kihagyjuk
                    if re.search(r'"AccountName"\s+"' + re.escape(target_username) + r'"',
                                 block, re.IGNORECASE):
                        i = j
                        continue
                    else:
                        result.append(block)
                        i = j
                        continue
                result.append(lines[i])
                i += 1
            new_content = "".join(result)

        with open(vdf_path, "w", encoding="utf-8") as f:
            f.write(new_content)
        return True
    except Exception as e:
        messagebox.showerror("VDF hiba", f"Nem sikerült eltávolítani a fiókot a VDF-ből:\n{e}")
        return False



def set_auto_login(username):
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STEAM_REG_PATH, 0, winreg.KEY_SET_VALUE) as key:
            winreg.SetValueEx(key, "AutoLoginUser",    0, winreg.REG_SZ,    username)
            winreg.SetValueEx(key, "RememberPassword", 0, winreg.REG_DWORD, 1)
        return True
    except Exception as e:
        messagebox.showerror("Registry hiba", f"Nem sikerült írni a registry-be:\n{e}\n\nPróbáld rendszergazdaként futtatni!")
        return False

# ── Steam le/felindítás ───────────────────────────────────────────────────────

def is_steam_running():
    try:
        out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH"],
                             capture_output=True, text=True).stdout.lower()
        return "steam.exe" in out
    except Exception:
        return False

def kill_steam(steam_exe=None):
    # Graceful shutdown first, so Steam fully exits and re-reads AutoLoginUser on
    # the next start (fixes switching after a logout / "change account").
    if steam_exe:
        try:
            subprocess.Popen([steam_exe, "-shutdown"])
        except Exception:
            pass
    for _ in range(24):          # wait up to ~12 s for a clean exit
        if not is_steam_running():
            return
        time.sleep(0.5)
    os.system("taskkill /F /IM steam.exe >nul 2>&1")
    time.sleep(1.5)

def launch_steam(steam_exe, username=None):
    # -login selects the account explicitly (works even after a logout).
    if username:
        subprocess.Popen([steam_exe, "-login", username])
    else:
        subprocess.Popen([steam_exe])

# ── Főablak ───────────────────────────────────────────────────────────────────

class SteamSwitcher(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Steam Fiókváltó")
        self.resizable(False, False)
        self.configure(bg="#1a1a2e")

        self.accounts = load_accounts()
        self.steam_exe = find_steam_exe()
        self.vdf_path  = find_vdf()

        self._build_ui()

        # Ha van VDF, auto-importálja az ismeretlen fiókokat
        if self.vdf_path:
            self._auto_import_vdf()

        self._refresh_list()

        self.update_idletasks()
        w, h = self.winfo_width(), self.winfo_height()
        sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
        self.geometry(f"+{(sw-w)//2}+{(sh-h)//2}")

    # ── UI ────────────────────────────────────────────────────────────────────

    def _build_ui(self):
        PAD = 16

        header = tk.Frame(self, bg="#16213e", pady=12)
        header.pack(fill="x")
        tk.Label(header, text="🎮  Steam Fiókváltó",
                 font=("Segoe UI", 15, "bold"), bg="#16213e", fg="#e2e8f0").pack()
        tk.Label(header, text="Kattints duplán egy fiókra a bejelentkezéshez",
                 font=("Segoe UI", 9), bg="#16213e", fg="#94a3b8").pack()

        if not self.steam_exe:
            warn = tk.Frame(self, bg="#7c3100", pady=6)
            warn.pack(fill="x")
            tk.Label(warn, text="⚠  Nem található a Steam.exe – add meg a ⚙ gombbal",
                     font=("Segoe UI", 9), bg="#7c3100", fg="#fde68a").pack()

        list_frame = tk.Frame(self, bg="#1a1a2e", padx=PAD, pady=PAD)
        list_frame.pack(fill="both", expand=True)

        scrollbar = tk.Scrollbar(list_frame, bg="#16213e", troughcolor="#16213e")
        scrollbar.pack(side="right", fill="y")

        self.listbox = tk.Listbox(
            list_frame,
            font=("Segoe UI", 12),
            bg="#0f3460", fg="#e2e8f0",
            selectbackground="#533483", selectforeground="#ffffff",
            activestyle="none", borderwidth=0, highlightthickness=0,
            width=34, height=8,
            yscrollcommand=scrollbar.set,
            cursor="hand2"
        )
        self.listbox.pack(side="left", fill="both", expand=True)
        scrollbar.config(command=self.listbox.yview)

        self.listbox.bind("<Double-Button-1>", self._on_switch)
        self.listbox.bind("<Return>", self._on_switch)

        btn_frame = tk.Frame(self, bg="#1a1a2e")
        btn_frame.pack(fill="x", padx=PAD, pady=(0, PAD))

        cfg = {"font": ("Segoe UI", 10), "relief": "flat", "cursor": "hand2",
               "padx": 14, "pady": 7, "borderwidth": 0}

        tk.Button(btn_frame, text="＋  Hozzáadás",
                  bg="#533483", fg="#ffffff", activebackground="#6d44a8",
                  command=self._add_account, **cfg).pack(side="left", padx=(0, 6))
        tk.Button(btn_frame, text="✎  Átnevezés",
                  bg="#16213e", fg="#94a3b8", activebackground="#1e2d4a",
                  command=self._rename_account, **cfg).pack(side="left", padx=(0, 6))
        tk.Button(btn_frame, text="✕  Törlés",
                  bg="#16213e", fg="#94a3b8", activebackground="#1e2d4a",
                  command=self._delete_account, **cfg).pack(side="left", padx=(0, 6))
        tk.Button(btn_frame, text="⚙",
                  bg="#16213e", fg="#94a3b8", activebackground="#1e2d4a",
                  command=self._set_steam_path, **cfg).pack(side="right")

        self.status_var = tk.StringVar(value="Kész.")
        tk.Label(self, textvariable=self.status_var,
                 font=("Segoe UI", 9), bg="#0d0d1a", fg="#64748b",
                 anchor="w", padx=PAD, pady=5).pack(fill="x")

    # ── Auto-import VDF fiókjai ───────────────────────────────────────────────

    def _auto_import_vdf(self):
        vdf_names = read_vdf_accounts(self.vdf_path)
        existing  = {a["username"].lower() for a in self.accounts}
        added = 0
        for name in vdf_names:
            if name.lower() not in existing:
                self.accounts.append({"username": name, "label": ""})
                existing.add(name.lower())
                added += 1
        if added:
            save_accounts(self.accounts)

    # ── Lista frissítés ───────────────────────────────────────────────────────

    def _refresh_list(self):
        self.listbox.delete(0, "end")
        for acc in self.accounts:
            label = acc.get("label") or acc["username"]
            self.listbox.insert("end", f"  👤  {label}  ({acc['username']})")

    # ── Fiókváltás ────────────────────────────────────────────────────────────

    def _on_switch(self, event=None):
        sel = self.listbox.curselection()
        if not sel:
            return
        acc      = self.accounts[sel[0]]
        username = acc["username"]
        label    = acc.get("label") or username

        if not self.steam_exe:
            messagebox.showerror("Hiba", "Nem található a Steam.exe!\nAdd meg a ⚙ gombbal.")
            return

        if not messagebox.askyesno("Fiókváltás",
                f"Átváltasz erre a fiókra?\n\n👤  {label}\n🔑  {username}\n\nA Steam újraindul!"):
            return

        self.status_var.set("⏳  Steam leállítása...")
        self.update()
        kill_steam(self.steam_exe)

        self.status_var.set("⏳  loginusers.vdf módosítása...")
        self.update()
        if self.vdf_path:
            patch_vdf(self.vdf_path, username)
        else:
            messagebox.showwarning("Figyelem",
                "Nem található a loginusers.vdf fájl.\n"
                "Csak a registry-t írom át – ez egyes esetekben nem elég.\n"
                "Add meg a Steam mappát a ⚙ gombbal!")

        self.status_var.set(f"⏳  Fiók beállítása: {username}")
        self.update()
        if not set_auto_login(username):
            self.status_var.set("❌  Hiba történt.")
            return

        self.status_var.set(f"🚀  Steam indítása – {label}...")
        self.update()
        launch_steam(self.steam_exe, username)
        self.status_var.set(f"✅  Bejelentkezve: {label}")

    # ── Fiók hozzáadása ───────────────────────────────────────────────────────

    def _add_account(self):
        username = simpledialog.askstring(
            "Fiók hozzáadása",
            "Steam felhasználónév (login name):\n(Nem az email, hanem a Steam fiókod neve)",
            parent=self)
        if not username:
            return
        username = username.strip()
        if any(a["username"].lower() == username.lower() for a in self.accounts):
            messagebox.showwarning("Már létezik", f"'{username}' már szerepel a listában.")
            return
        label = simpledialog.askstring("Becenév (opcionális)",
            "Adj meg egy becenevet (pl. 'FPS fiók').\nHagyd üresen, ha nem kell.",
            parent=self)
        self.accounts.append({"username": username, "label": (label or "").strip()})
        save_accounts(self.accounts)
        self._refresh_list()
        self.status_var.set(f"✅  Hozzáadva: {username}")

    # ── Átnevezés ─────────────────────────────────────────────────────────────

    def _rename_account(self):
        sel = self.listbox.curselection()
        if not sel:
            messagebox.showinfo("Válassz fiókot", "Előbb válassz egy fiókot!")
            return
        acc = self.accounts[sel[0]]
        new_label = simpledialog.askstring("Átnevezés",
            f"Új becenév ({acc['username']}):",
            initialvalue=acc.get("label", ""), parent=self)
        if new_label is None:
            return
        acc["label"] = new_label.strip()
        save_accounts(self.accounts)
        self._refresh_list()

    # ── Törlés ────────────────────────────────────────────────────────────────

    def _delete_account(self):
        sel = self.listbox.curselection()
        if not sel:
            messagebox.showinfo("Válassz fiókot", "Előbb válassz egy fiókot!")
            return
        acc      = self.accounts[sel[0]]
        username = acc["username"]
        label    = acc.get("label") or username
        if not messagebox.askyesno("Törlés",
                f"Biztosan törlöd?\n\n👤  {label}\n\n"
                f"Ez eltávolítja a fiókot a Steam bejelentkezési listájából is!"):
            return

        # 1) JSON-ból törlés
        self.accounts.pop(sel[0])
        save_accounts(self.accounts)

        # 2) loginusers.vdf-ből törlés
        if self.vdf_path and os.path.exists(self.vdf_path):
            remove_from_vdf(self.vdf_path, username)

        self._refresh_list()
        self.status_var.set(f"🗑  Törölve: {label}")

    # ── Steam útvonal beállítás ───────────────────────────────────────────────

    def _set_steam_path(self):
        path = filedialog.askopenfilename(
            title="Válaszd ki a steam.exe-t",
            filetypes=[("Steam", "steam.exe"), ("Minden fájl", "*.*")],
            initialdir=r"C:\Program Files (x86)\Steam")
        if path and os.path.exists(path):
            self.steam_exe = path
            self.vdf_path  = os.path.join(os.path.dirname(path), "config", "loginusers.vdf")
            if os.path.exists(self.vdf_path):
                self._auto_import_vdf()
                self._refresh_list()
                self.status_var.set(f"✅  Steam megtalálva, fiókok importálva.")
            else:
                self.vdf_path = None
                self.status_var.set(f"✅  Steam.exe beállítva (VDF nem található).")

# ── Belépési pont ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    app = SteamSwitcher()
    app.mainloop()
