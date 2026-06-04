using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace SteamSwitcher;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

// ── VDF kezelés ───────────────────────────────────────────────────────────────

record SteamAccount(string SteamId, string Username, string DisplayName);

static class Vdf
{
    public static string? FindPath()
    {
        // Registry-ből
        var regPath = Registry.LocalMachine
            .OpenSubKey(@"SOFTWARE\Valve\Steam")
            ?.GetValue("InstallPath") as string;
        if (regPath != null)
        {
            var p = Path.Combine(regPath, "config", "loginusers.vdf");
            if (File.Exists(p)) return p;
        }
        // Fix helyek
        foreach (var root in new[] {
            @"C:\Program Files (x86)\Steam",
            @"C:\Program Files\Steam" })
        {
            var p = Path.Combine(root, "config", "loginusers.vdf");
            if (File.Exists(p)) return p;
        }
        return null;
    }

    public static string? FindSteamExe()
    {
        var regPath = Registry.LocalMachine
            .OpenSubKey(@"SOFTWARE\Valve\Steam")
            ?.GetValue("InstallPath") as string;
        if (regPath != null)
        {
            var exe = Path.Combine(regPath, "steam.exe");
            if (File.Exists(exe)) return exe;
        }
        foreach (var p in new[] {
            @"C:\Program Files (x86)\Steam\steam.exe",
            @"C:\Program Files\Steam\steam.exe" })
            if (File.Exists(p)) return p;
        return null;
    }

    public static List<SteamAccount> ReadAccounts(string vdfPath)
    {
        var accounts = new List<SteamAccount>();
        if (!File.Exists(vdfPath)) return accounts;

        var text = File.ReadAllText(vdfPath);

        // Minden fiókblokk: "SteamID64" { ... }
        var blockPattern = new Regex(
            @"""(?<id>\d{10,})""\s*\{(?<body>[^{}]*)\}",
            RegexOptions.Singleline);

        foreach (Match m in blockPattern.Matches(text))
        {
            var id   = m.Groups["id"].Value;
            var body = m.Groups["body"].Value;

            var username    = Extract(body, "AccountName");
            var displayName = Extract(body, "PersonaName");

            if (!string.IsNullOrWhiteSpace(username))
                accounts.Add(new SteamAccount(id, username, displayName ?? username));
        }
        return accounts;
    }

    public static bool PatchAccount(string vdfPath, string targetUsername)
    {
        if (!File.Exists(vdfPath)) return false;
        var text = File.ReadAllText(vdfPath);

        var blockPattern = new Regex(
            @"(""(?<id>\d{10,})""\s*\{)(?<body>[^{}]*?)(\})",
            RegexOptions.Singleline);

        var result = blockPattern.Replace(text, m =>
        {
            var body  = m.Groups["body"].Value;
            var accName = Extract(body, "AccountName") ?? "";
            bool isTarget = string.Equals(accName, targetUsername,
                                          StringComparison.OrdinalIgnoreCase);

            body = SetField(body, "RememberPassword", "1");
            body = SetField(body, "MostRecent",       isTarget ? "1" : "0");
            if (isTarget)
                body = SetField(body, "AllowAutoLogin", "1");

            return m.Groups[1].Value + body + m.Groups[4].Value;
        });

        File.WriteAllText(vdfPath, result);
        return true;
    }

    public static bool RemoveAccount(string vdfPath, string targetUsername)
    {
        if (!File.Exists(vdfPath)) return false;
        var text = File.ReadAllText(vdfPath);

        var blockPattern = new Regex(
            @"""(?<id>\d{10,})""\s*\{(?<body>[^{}]*)\}",
            RegexOptions.Singleline);

        var result = blockPattern.Replace(text, m =>
        {
            var body = m.Groups["body"].Value;
            var accName = Extract(body, "AccountName") ?? "";
            return string.Equals(accName, targetUsername,
                                 StringComparison.OrdinalIgnoreCase)
                ? "" : m.Value;
        });

        File.WriteAllText(vdfPath, result);
        return true;
    }

    // ── Segédek ───────────────────────────────────────────────────────────────

    static string? Extract(string body, string key)
    {
        var m = Regex.Match(body, $@"""{key}""\s+""(?<v>[^""]+)""",
                            RegexOptions.IgnoreCase);
        return m.Success ? m.Groups["v"].Value : null;
    }

    static string SetField(string body, string key, string value)
    {
        var pattern = $@"""({key})""\s+""[^""]*""";
        if (Regex.IsMatch(body, pattern, RegexOptions.IgnoreCase))
            return Regex.Replace(body, pattern,
                $@"""{key}""\t\t""{value}""", RegexOptions.IgnoreCase);
        // Ha nem létezik, hozzáadjuk a záró } elé
        return body.TrimEnd() + $"\n\t\t\"{key}\"\t\t\"{value}\"\n\t\t";
    }
}

// ── Registry ──────────────────────────────────────────────────────────────────

static class SteamRegistry
{
    const string Path = @"SOFTWARE\Valve\Steam";

    public static bool SetAutoLogin(string username)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(Path, writable: true);
            if (key == null) return false;
            key.SetValue("AutoLoginUser",    username);
            key.SetValue("RememberPassword", 1, RegistryValueKind.DWord);
            return true;
        }
        catch { return false; }
    }
}

// ── Főablak ───────────────────────────────────────────────────────────────────

class MainForm : Form
{
    readonly ListBox  _list   = new();
    readonly Label    _status = new();
    readonly Button   _btnAdd = new(), _btnRename = new(),
                      _btnDel = new(), _btnSettings = new();

    List<SteamAccount> _accounts = new();
    string? _vdfPath;
    string? _steamExe;

    // Sötét téma színek
    static readonly Color BgDark    = Color.FromArgb(26,  26,  46);
    static readonly Color BgMid     = Color.FromArgb(22,  33,  62);
    static readonly Color BgList    = Color.FromArgb(15,  52,  96);
    static readonly Color AccentPur = Color.FromArgb(83,  52, 131);
    static readonly Color TextLight = Color.FromArgb(226, 232, 240);
    static readonly Color TextMuted = Color.FromArgb(148, 163, 184);

    public MainForm()
    {
        Text            = "Steam Fiókváltó";
        Size            = new Size(420, 440);
        MinimumSize     = new Size(420, 440);
        BackColor       = BgDark;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox     = false;
        StartPosition   = FormStartPosition.CenterScreen;

        BuildUi();

        _vdfPath  = Vdf.FindPath();
        _steamExe = Vdf.FindSteamExe();

        LoadAccounts();

        if (_steamExe == null)
            SetStatus("⚠  Steam.exe nem található – kattints a ⚙ gombra", Color.FromArgb(253, 230, 138));
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    void BuildUi()
    {
        // Fejléc
        var header = new Panel { Dock = DockStyle.Top, Height = 64, BackColor = BgMid };
        var title  = new Label {
            Text      = "🎮  Steam Fiókváltó",
            Font      = new Font("Segoe UI", 13, FontStyle.Bold),
            ForeColor = TextLight,
            AutoSize  = false,
            TextAlign = ContentAlignment.MiddleCenter,
            Dock      = DockStyle.Top, Height = 38
        };
        var subtitle = new Label {
            Text      = "Dupla kattintás = bejelentkezés",
            Font      = new Font("Segoe UI", 9),
            ForeColor = TextMuted,
            AutoSize  = false,
            TextAlign = ContentAlignment.MiddleCenter,
            Dock      = DockStyle.Fill
        };
        header.Controls.AddRange([title, subtitle]);

        // Listbox
        _list.Dock            = DockStyle.Fill;
        _list.BackColor       = BgList;
        _list.ForeColor       = TextLight;
        _list.Font            = new Font("Segoe UI", 11);
        _list.BorderStyle     = BorderStyle.None;
        _list.ItemHeight      = 32;
        _list.DrawMode        = DrawMode.OwnerDrawFixed;
        _list.DrawItem       += DrawListItem;
        _list.DoubleClick    += (_, _) => SwitchAccount();

        var listPanel = new Panel {
            Dock    = DockStyle.Fill,
            Padding = new Padding(12, 8, 12, 8)
        };
        listPanel.Controls.Add(_list);

        // Gombsor
        StyleBtn(_btnAdd,      "＋  Hozzáadás", AccentPur, TextLight);
        StyleBtn(_btnRename,   "✎  Átnevezés",  BgMid,     TextMuted);
        StyleBtn(_btnDel,      "✕  Törlés",     BgMid,     TextMuted);
        StyleBtn(_btnSettings, "⚙",             BgMid,     TextMuted);

        _btnAdd.Click      += (_, _) => AddAccount();
        _btnRename.Click   += (_, _) => RenameAccount();
        _btnDel.Click      += (_, _) => DeleteAccount();
        _btnSettings.Click += (_, _) => PickSteamExe();

        var btnPanel = new FlowLayoutPanel {
            Dock        = DockStyle.Bottom,
            Height      = 52,
            BackColor   = BgDark,
            Padding     = new Padding(10, 8, 10, 0),
            FlowDirection = FlowDirection.LeftToRight
        };
        btnPanel.Controls.Add(_btnAdd);
        btnPanel.Controls.Add(_btnRename);
        btnPanel.Controls.Add(_btnDel);

        // ⚙ jobb oldalra
        var spacer = new Panel { Width = 1, Height = 1 };
        btnPanel.Controls.Add(spacer);
        btnPanel.SetFlowBreak(spacer, false);
        _btnSettings.Anchor = AnchorStyles.Right;
        btnPanel.Controls.Add(_btnSettings);

        // Státuszsor
        _status.Dock      = DockStyle.Bottom;
        _status.Height    = 26;
        _status.BackColor = Color.FromArgb(13, 13, 26);
        _status.ForeColor = TextMuted;
        _status.Font      = new Font("Segoe UI", 8.5f);
        _status.Padding   = new Padding(10, 4, 0, 0);
        _status.Text      = "Betöltés...";

        Controls.AddRange([header, listPanel, btnPanel, _status]);
    }

    static void StyleBtn(Button b, string text, Color bg, Color fg)
    {
        b.Text        = text;
        b.BackColor   = bg;
        b.ForeColor   = fg;
        b.FlatStyle   = FlatStyle.Flat;
        b.FlatAppearance.BorderSize = 0;
        b.Font        = new Font("Segoe UI", 9.5f);
        b.Cursor      = Cursors.Hand;
        b.Height      = 34;
        b.AutoSize    = true;
        b.Padding     = new Padding(10, 0, 10, 0);
        b.Margin      = new Padding(0, 0, 6, 0);
    }

    void DrawListItem(object? sender, DrawItemEventArgs e)
    {
        if (e.Index < 0 || e.Index >= _accounts.Count) return;
        var acc = _accounts[e.Index];

        var isSelected = (e.State & DrawItemState.Selected) != 0;
        e.Graphics.FillRectangle(
            new SolidBrush(isSelected ? AccentPur : BgList),
            e.Bounds);

        var label = string.IsNullOrWhiteSpace(acc.DisplayName) || acc.DisplayName == acc.Username
            ? acc.Username
            : $"{acc.DisplayName}  ({acc.Username})";

        e.Graphics.DrawString("  👤  " + label,
            e.Font ?? _list.Font,
            new SolidBrush(TextLight),
            e.Bounds,
            new StringFormat { LineAlignment = StringAlignment.Center });
    }

    // ── Fiókok betöltése ──────────────────────────────────────────────────────

    void LoadAccounts()
    {
        _accounts = _vdfPath != null ? Vdf.ReadAccounts(_vdfPath) : new();
        _list.Items.Clear();
        foreach (var a in _accounts)
            _list.Items.Add(a);
        SetStatus($"✅  {_accounts.Count} fiók betöltve");
    }

    // ── Fiókváltás ────────────────────────────────────────────────────────────

    void SwitchAccount()
    {
        if (_list.SelectedIndex < 0) return;
        var acc = _accounts[_list.SelectedIndex];

        if (_steamExe == null)
        {
            MessageBox.Show("Nem található a Steam.exe!\nAdd meg a ⚙ gombbal.",
                "Hiba", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        var confirm = MessageBox.Show(
            $"Átváltasz erre a fiókra?\n\n👤  {acc.DisplayName}\n🔑  {acc.Username}\n\nA Steam újraindul!",
            "Fiókváltás", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
        if (confirm != DialogResult.Yes) return;

        SetStatus("⏳  Steam leállítása...");
        KillSteam();

        SetStatus("⏳  Fájlok módosítása...");
        if (_vdfPath != null) Vdf.PatchAccount(_vdfPath, acc.Username);
        SteamRegistry.SetAutoLogin(acc.Username);

        SetStatus($"🚀  Steam indítása – {acc.DisplayName}...");
        Process.Start(_steamExe);
        SetStatus($"✅  Bejelentkezve: {acc.DisplayName}");
    }

    // ── Hozzáadás ─────────────────────────────────────────────────────────────

    void AddAccount()
    {
        // A VDF-ből már mindent olvasunk, kézi hozzáadás nem szükséges
        MessageBox.Show(
            "A fiókok automatikusan töltődnek be a Steam bejelentkezési fájljából.\n\n" +
            "Ha hiányzik egy fiók, lépj be abba a fiókba egyszer manuálisan a Steamben " +
            "(\"Emlékezz rám\" pipával), majd nyisd újra ezt az ablakot.",
            "Fiók hozzáadása", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    // ── Átnevezés (csak megjelenítési név, a VDF-ben PersonaName) ─────────────

    void RenameAccount()
    {
        if (_list.SelectedIndex < 0)
        {
            MessageBox.Show("Előbb válassz egy fiókot!", "Átnevezés",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        MessageBox.Show(
            "A megjelenített név a Steam profilnevedből jön (PersonaName).\n" +
            "Ezt a Steamen belül tudod megváltoztatni.",
            "Átnevezés", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    // ── Törlés ────────────────────────────────────────────────────────────────

    void DeleteAccount()
    {
        if (_list.SelectedIndex < 0)
        {
            MessageBox.Show("Előbb válassz egy fiókot!", "Törlés",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        var acc = _accounts[_list.SelectedIndex];
        var confirm = MessageBox.Show(
            $"Biztosan törlöd?\n\n👤  {acc.DisplayName}\n🔑  {acc.Username}\n\n" +
            "Ez eltávolítja a fiókot a Steam bejelentkezési listájából is!",
            "Törlés", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
        if (confirm != DialogResult.Yes) return;

        if (_vdfPath != null) Vdf.RemoveAccount(_vdfPath, acc.Username);
        LoadAccounts();
        SetStatus($"🗑  Törölve: {acc.DisplayName}");
    }

    // ── Steam.exe kiválasztása ────────────────────────────────────────────────

    void PickSteamExe()
    {
        using var dlg = new OpenFileDialog {
            Title  = "Válaszd ki a steam.exe-t",
            Filter = "Steam|steam.exe|Minden fájl|*.*",
            InitialDirectory = @"C:\Program Files (x86)\Steam"
        };
        if (dlg.ShowDialog() != DialogResult.OK) return;
        _steamExe = dlg.FileName;
        var dir = Path.GetDirectoryName(_steamExe)!;
        var vdf = Path.Combine(dir, "config", "loginusers.vdf");
        if (File.Exists(vdf)) _vdfPath = vdf;
        LoadAccounts();
        SetStatus("✅  Steam megtalálva, fiókok frissítve.");
    }

    // ── Segédek ───────────────────────────────────────────────────────────────

    static void KillSteam()
    {
        foreach (var p in Process.GetProcessesByName("steam"))
            try { p.Kill(); } catch { }
        Thread.Sleep(2000);
    }

    void SetStatus(string text, Color? color = null)
    {
        _status.ForeColor = color ?? TextMuted;
        _status.Text      = "  " + text;
    }
}
