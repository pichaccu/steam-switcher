import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;

/**
 * Steam Account Switcher
 *
 * Lightweight Steam account switcher for Windows.
 * - Switch between remembered Steam accounts (passwords are never handled).
 * - Configurable quick-launch game button.
 * - Colored emoji in account names (bundled Twemoji images).
 * - Per-account "last login" timestamp.
 * - Multi-language UI (defaults to the Windows language, English fallback).
 * - In-app updater that pulls releases from GitHub over HTTPS (no git needed).
 *
 * Build: javac -encoding UTF-8 SteamSwitcher.java   (Java 8+ compatible)
 */
public class SteamSwitcher extends JFrame {

    static final String VERSION = "1.0.5";
    static final String REPO    = "pichaccu/steam-switcher";

    // ── Colors ────────────────────────────────────────────────────────────────
    static final Color BG_DARK   = new Color(26,  26,  46);
    static final Color BG_MID    = new Color(22,  33,  62);
    static final Color BG_LIST   = new Color(15,  52,  96);
    static final Color ACCENT    = new Color(83,  52, 131);
    static final Color ACCENT_HO = new Color(109, 68, 172);
    static final Color TEXT_LT   = new Color(226, 232, 240);
    static final Color TEXT_MUT  = new Color(148, 163, 184);
    static final Color TEXT_WARN = new Color(253, 230, 138);
    static final Color TEXT_OK   = new Color(134, 239, 172);

    static final class Account {
        final String steamId, username, displayName;
        Account(String steamId, String username, String displayName) {
            this.steamId = steamId; this.username = username; this.displayName = displayName;
        }
        String steamId()     { return steamId; }
        String username()    { return username; }
        String displayName() { return displayName; }
        @Override public String toString() {
            return displayName.equals(username) ? username : displayName + "  (" + username + ")";
        }
    }

    static final class Game {
        final String appid, name;
        Game(String appid, String name) { this.appid = appid; this.name = name; }
        @Override public String toString() { return name + "  (" + appid + ")"; }
    }

    private List<Account> accounts = new ArrayList<Account>();
    private String vdfPath;
    private String steamExe;
    private String gameAppId;
    private String gameName;

    private final Settings settings = new Settings();

    private final DefaultListModel<Account> listModel = new DefaultListModel<Account>();
    private final JList<Account> accountList          = new JList<Account>(listModel);
    private final JLabel statusLabel                  = new JLabel();

    // Last login times (SteamID -> epoch ms), stored in a cache file
    static final Map<String, Long> LAST_LOGIN = new HashMap<String, Long>();
    static final File LL_FILE = new File(System.getProperty("user.home"),
                                         ".steamswitcher_lastlogin.properties");
    static final java.text.SimpleDateFormat LL_FMT = new java.text.SimpleDateFormat("yyyy.MM.dd HH:mm");

    // Per-account nicknames (SteamID -> nickname). When a nickname is set, only the
    // nickname is shown in the list (the username is hidden). Clear it to reveal the name.
    static final Map<String, String> NICKNAMES = new HashMap<String, String>();
    static final File NICK_FILE = new File(System.getProperty("user.home"),
                                           ".steamswitcher_nicknames.properties");

    // Username currently set to auto-login (the "active" account) – highlighted green.
    static String ACTIVE_USER;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() { new SteamSwitcher().setVisible(true); }
        });
    }

    public SteamSwitcher() {
        I18n.setLanguage(settings.get("language", "system"));
        gameAppId = settings.get("quicklaunch.appid", "730");
        gameName  = settings.get("quicklaunch.name", "CS2");

        setTitle(tr("title"));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(420, 460);
        setMinimumSize(new Dimension(420, 460));
        setResizable(false);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout());

        URL iconUrl = SteamSwitcher.class.getResource("/resources/steam.png");
        if (iconUrl != null) {
            try { setIconImage(new ImageIcon(iconUrl).getImage()); } catch (Exception ignored) {}
        }

        buildUi();

        loadLastLogin();
        loadNicknames();
        String override = settings.get("steam.exe", null);
        steamExe = (override != null && new File(override).exists())
                   ? override : VdfHelper.findSteamExe();
        vdfPath  = VdfHelper.findVdf(steamExe);
        loadAccounts();

        if (steamExe == null) setStatus(tr("steamNotFound"), TEXT_WARN);
    }

    static String tr(String key, Object... args) { return I18n.tr(key, args); }

    // ── UI ──────────────────────────────────────────────────────────────────
    private void buildUi() {
        add(buildHeader(),  BorderLayout.NORTH);
        add(buildList(),    BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(null);
        header.setBackground(BG_MID);
        header.setPreferredSize(new Dimension(0, 68));

        JLabel iconLabel = new JLabel();
        URL iconUrl = SteamSwitcher.class.getResource("/resources/steam.png");
        if (iconUrl != null) {
            try {
                Image img = new ImageIcon(iconUrl).getImage().getScaledInstance(36, 36, Image.SCALE_SMOOTH);
                iconLabel.setIcon(new ImageIcon(img));
            } catch (Exception ignored) {}
        }
        iconLabel.setBounds(16, 16, 36, 36);

        JLabel title = new JLabel(tr("title"));
        title.setFont(new Font("Segoe UI", Font.BOLD, 15));
        title.setForeground(TEXT_LT);
        title.setBounds(62, 12, 190, 26);

        JLabel sub = new JLabel(tr("subtitle"));
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        sub.setForeground(TEXT_MUT);
        sub.setBounds(63, 38, 190, 18);

        // Quick-launch game button (top-right), with a real colored controller emoji
        JButton play = makeBtn(gameName, ACCENT, ACCENT_HO, TEXT_LT);
        play.setBounds(258, 17, 132, 34);
        play.setToolTipText(tr("launchTip", gameName));
        URL padUrl = SteamSwitcher.class.getResource("/resources/emoji/1f3ae.png");
        if (padUrl != null) {
            try {
                Image pad = new ImageIcon(padUrl).getImage().getScaledInstance(16, 16, Image.SCALE_SMOOTH);
                play.setIcon(new ImageIcon(pad));
                play.setIconTextGap(6);
            } catch (Exception ignored) {}
        }
        play.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { launchGame(); }
        });

        header.add(iconLabel);
        header.add(title);
        header.add(sub);
        header.add(play);
        return header;
    }

    private JScrollPane buildList() {
        accountList.setBackground(BG_LIST);
        accountList.setForeground(TEXT_LT);
        accountList.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        accountList.setSelectionBackground(ACCENT);
        accountList.setSelectionForeground(Color.WHITE);
        accountList.setFixedCellHeight(36);
        accountList.setBorder(new EmptyBorder(4, 8, 4, 8));
        accountList.setCellRenderer(new AccountCellRenderer());
        accountList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) switchAccount();
            }
        });

        // Right-click an account to set/clear its nickname.
        final JPopupMenu popup = new JPopupMenu();
        JMenuItem nickItem = new JMenuItem(tr("nickname"));
        nickItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { setNicknameForSelected(); }
        });
        popup.add(nickItem);
        accountList.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e)  { maybePopup(e); }
            public void mouseReleased(MouseEvent e) { maybePopup(e); }
            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = accountList.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    accountList.setSelectedIndex(idx);
                    popup.show(accountList, e.getX(), e.getY());
                }
            }
        });

        JScrollPane scroll = new JScrollPane(accountList);
        scroll.setBorder(new MatteBorder(0, 10, 0, 10, BG_DARK));
        scroll.getViewport().setBackground(BG_LIST);
        return scroll;
    }

    private JPanel buildButtons() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG_DARK);
        outer.setBorder(new EmptyBorder(8, 10, 10, 10));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.setOpaque(false);

        JButton btnLogin    = makeBtn(tr("login"),    ACCENT, ACCENT_HO, TEXT_LT);
        JButton btnDelete   = makeBtn(tr("delete"),   BG_MID, BG_LIST,   TEXT_MUT);
        JButton btnSettings = makeBtn(tr("settings"), BG_MID, BG_LIST,   TEXT_MUT);
        Dimension bsz = new Dimension(120, 34);   // three must fit the 420px window
        btnLogin.setPreferredSize(bsz);
        btnDelete.setPreferredSize(bsz);
        btnSettings.setPreferredSize(bsz);

        btnLogin.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { switchAccount(); }
        });
        btnDelete.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { deleteAccount(); }
        });
        btnSettings.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { openSettings(); }
        });

        btnRow.add(btnLogin);
        btnRow.add(btnDelete);
        btnRow.add(btnSettings);

        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 9));
        statusLabel.setForeground(TEXT_MUT);
        statusLabel.setBackground(new Color(13, 13, 26));
        statusLabel.setOpaque(true);
        statusLabel.setPreferredSize(new Dimension(0, 24));
        statusLabel.setBorder(new EmptyBorder(0, 10, 0, 0));

        outer.add(btnRow,      BorderLayout.CENTER);
        outer.add(statusLabel, BorderLayout.SOUTH);
        return outer;
    }

    private JButton makeBtn(String text, final Color bg, final Color hover, Color fg) {
        JButton b = new JButton(text) {
            boolean hovered = false;
            {
                addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                    public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(hovered ? hover : bg);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                super.paintComponent(g);
            }
        };
        b.setForeground(fg);
        b.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(130, 34));
        return b;
    }

    // ── Account list cell: draws colored emoji (Twemoji) + last-login time ─────
    static class AccountCellRenderer extends JComponent implements ListCellRenderer<Account> {
        private List<Object> segs = Collections.emptyList();
        private boolean selected;
        private boolean active;
        private String lastLogin = "";
        private final Font font  = new Font("Segoe UI", Font.PLAIN, 12);
        private final Font small = new Font("Segoe UI", Font.PLAIN, 9);

        public Component getListCellRendererComponent(JList<? extends Account> list,
                Account value, int index, boolean isSelected, boolean cellHasFocus) {
            String nick = NICKNAMES.get(value.steamId());
            String shown = (nick != null && !nick.trim().isEmpty()) ? nick : value.toString();
            segs = EmojiText.parse(shown);
            Long ts = LAST_LOGIN.get(value.steamId());
            lastLogin = (ts != null) ? LL_FMT.format(new Date(ts)) : "";
            active = value.username() != null && ACTIVE_USER != null
                     && value.username().equalsIgnoreCase(ACTIVE_USER);
            selected = isSelected;
            setOpaque(true);
            return this;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,     RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(selected ? ACCENT : BG_LIST);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (active) {                       // green left bar marks the active account
                g.setColor(TEXT_OK);
                g.fillRect(0, 0, 3, getHeight());
            }

            int rightLimit = getWidth() - 10;
            if (!lastLogin.isEmpty()) {
                g.setFont(small);
                FontMetrics fm2 = g.getFontMetrics();
                int tw = fm2.stringWidth(lastLogin);
                int ty = (getHeight() - fm2.getHeight()) / 2 + fm2.getAscent();
                g.setColor(selected ? new Color(215, 205, 240) : TEXT_MUT);
                g.drawString(lastLogin, getWidth() - tw - 10, ty);
                rightLimit = getWidth() - tw - 18;
            }

            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int es = fm.getAscent();
            int baseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            int x = 8;
            g.setColor(active ? TEXT_OK : TEXT_LT);
            for (Object seg : segs) {
                if (seg instanceof Image) {
                    if (x + es > rightLimit) break;
                    g.drawImage((Image) seg, x, baseline - es, es, es, null);
                    x += es + 1;
                } else {
                    String t = (String) seg;
                    if (x + fm.stringWidth(t) > rightLimit) {
                        while (t.length() > 1 && x + fm.stringWidth(t + "…") > rightLimit)
                            t = t.substring(0, t.length() - 1);
                        g.drawString(t + "…", x, baseline);
                        break;
                    }
                    g.drawString(t, x, baseline);
                    x += fm.stringWidth(t);
                }
            }
            g.dispose();
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────
    private void loadAccounts() {
        accounts = vdfPath != null ? VdfHelper.readAccounts(vdfPath) : new ArrayList<Account>();
        listModel.clear();
        for (Account a : accounts) listModel.addElement(a);
        refreshActive();
        setStatus(tr("loaded", String.valueOf(accounts.size())), TEXT_MUT);
    }

    // Which account is currently set to auto-login (registry), falling back to the
    // most-recent one in the vdf. Used to highlight the active account green.
    private void refreshActive() {
        String u = RegistryHelper.readAutoLoginUser();
        if (u == null || u.trim().isEmpty())
            u = (vdfPath != null) ? VdfHelper.findMostRecent(vdfPath) : null;
        ACTIVE_USER = u;
    }

    private void switchAccount() {
        int idx = accountList.getSelectedIndex();
        if (idx < 0) { info(tr("selectFirst"), tr("login")); return; }
        Account acc = accounts.get(idx);

        if (steamExe == null) {
            JOptionPane.showMessageDialog(this, tr("steamMissing"), tr("errorTitle"),
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        int ok = JOptionPane.showConfirmDialog(this,
            tr("confirmLogin", acc.displayName(), acc.username()),
            tr("confirmTitle"), JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;

        setStatus(tr("stopping"), TEXT_MUT);
        stopSteam();

        setStatus(tr("patching"), TEXT_MUT);
        if (vdfPath != null) VdfHelper.patchAccount(vdfPath, acc.username());
        RegistryHelper.setAutoLogin(acc.username());

        setStatus(tr("starting", acc.displayName()), TEXT_MUT);
        try {
            // -login selects the account explicitly (works even after a logout / "change account")
            Runtime.getRuntime().exec(new String[]{ steamExe, "-login", acc.username() });
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                tr("launchFail", "Steam") + "\n" + ex.getMessage(),
                tr("errorTitle"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        LAST_LOGIN.put(acc.steamId(), System.currentTimeMillis());
        saveLastLogin();
        ACTIVE_USER = acc.username();      // mark the just-switched account active (green)
        accountList.repaint();
        setStatus(tr("loggedIn", acc.displayName()), TEXT_OK);
    }

    private void deleteAccount() {
        int idx = accountList.getSelectedIndex();
        if (idx < 0) { info(tr("selectFirst"), tr("deleteTitle")); return; }
        Account acc = accounts.get(idx);
        int ok = JOptionPane.showConfirmDialog(this,
            tr("confirmDelete", acc.displayName(), acc.username()),
            tr("deleteTitle"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        if (vdfPath != null) VdfHelper.removeAccount(vdfPath, acc.username());
        loadAccounts();
        setStatus(tr("deleted", acc.displayName()), TEXT_MUT);
    }

    private void launchGame() {
        try {
            if (steamExe != null)
                Runtime.getRuntime().exec(new String[]{ steamExe, "-applaunch", gameAppId });
            else
                Runtime.getRuntime().exec(new String[]{ "cmd", "/c", "start", "", "steam://rungameid/" + gameAppId });
            setStatus(tr("launching", gameName), TEXT_OK);
        } catch (Exception ex) {
            setStatus(tr("launchFail", gameName), TEXT_WARN);
        }
    }

    private void info(String msg, String title) {
        JOptionPane.showMessageDialog(this, msg, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText("  " + text);
        statusLabel.setForeground(color);
    }

    // ── Settings dialog ─────────────────────────────────────────────────────────
    private void openSettings() { buildSettingsDialog().setVisible(true); }

    JDialog buildSettingsDialog() {
        final JDialog d = new JDialog(this, tr("settingsTitle"), true);
        d.getContentPane().setBackground(BG_DARK);
        d.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 10, 6, 10);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        final JComboBox<String> langBox = new JComboBox<String>();
        langBox.addItem(tr("systemDefault") + "  [system]");
        for (String code : I18n.codes()) langBox.addItem(I18n.displayName(code) + "  [" + code + "]");
        String curLang = settings.get("language", "system");
        langBox.setSelectedIndex(0);
        for (int i = 0; i < I18n.codes().length; i++)
            if (I18n.codes()[i].equals(curLang)) langBox.setSelectedIndex(i + 1);

        final JTextField nameField  = new JTextField(settings.get("quicklaunch.name", "CS2"), 12);
        final JTextField appIdField = new JTextField(settings.get("quicklaunch.appid", "730"), 12);
        final JTextField steamField = new JTextField(steamExe == null ? "" : steamExe, 18);

        JButton browse = makeBtn(tr("browse"), BG_MID, BG_LIST, TEXT_LT);
        browse.setPreferredSize(new Dimension(112, 28));
        browse.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser("C:\\Program Files (x86)\\Steam");
                fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
                    public boolean accept(File f) {
                        return f.isDirectory() || f.getName().equalsIgnoreCase("steam.exe");
                    }
                    public String getDescription() { return "steam.exe"; }
                });
                if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION)
                    steamField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        // "Games…" button: pick the App ID from installed games
        JButton gamesBtn = makeBtn(tr("pickGame"), BG_MID, BG_LIST, TEXT_LT);
        gamesBtn.setPreferredSize(new Dimension(112, 28));
        gamesBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Game g = showGamePicker(d);
                if (g != null) { nameField.setText(g.name); appIdField.setText(g.appid); }
            }
        });

        row = addRow(d, c, row, tr("language"),  langBox,    null);
        row = addRow(d, c, row, tr("quickGame"), nameField,  null);
        row = addRow(d, c, row, tr("appId"),     appIdField, gamesBtn);
        row = addRow(d, c, row, tr("steamPath"), steamField, browse);

        JLabel info = new JLabel("<html>" + tr("version") + ": " + VERSION
                + " &nbsp;•&nbsp; " + tr("runAs") + ": " + runtimeKind() + "</html>");
        info.setForeground(TEXT_MUT);
        info.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        c.gridx = 0; c.gridy = row++; c.gridwidth = 3;
        d.add(info, c);
        c.gridwidth = 1;

        JButton update = makeBtn(tr("checkUpdate"), BG_MID,  BG_LIST,   TEXT_LT);
        JButton save   = makeBtn(tr("save"),        ACCENT,  ACCENT_HO, TEXT_LT);
        JButton cancel = makeBtn(tr("cancel"),      BG_MID,  BG_LIST,   TEXT_MUT);
        update.setPreferredSize(new Dimension(150, 30));
        save.setPreferredSize(new Dimension(90, 30));
        cancel.setPreferredSize(new Dimension(90, 30));

        update.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { Updater.checkAndUpdate(SteamSwitcher.this, d); }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { d.dispose(); }
        });
        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int li = langBox.getSelectedIndex();
                settings.set("language", li <= 0 ? "system" : I18n.codes()[li - 1]);
                settings.set("quicklaunch.name",  nameField.getText().trim());
                settings.set("quicklaunch.appid", appIdField.getText().trim());
                String sp = steamField.getText().trim();
                settings.set("steam.exe", sp.isEmpty() ? null : sp);
                settings.save();
                d.dispose();
                restartUi();
            }
        });

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        btns.setOpaque(false);
        btns.add(update); btns.add(cancel); btns.add(save);
        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL;
        d.add(btns, c);

        d.pack();
        d.setMinimumSize(new Dimension(540, d.getHeight()));
        d.setLocationRelativeTo(this);
        return d;
    }

    private int addRow(JDialog d, GridBagConstraints c, int row, String label,
                       JComponent field, JComponent extra) {
        JLabel l = new JLabel(label);
        l.setForeground(TEXT_LT);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        l.setBorder(new EmptyBorder(0, 0, 0, 12));   // room so long labels don't clip
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.gridwidth = 1;
        d.add(l, c);
        c.gridx = 1; c.weightx = 1;
        d.add(field, c);
        c.gridx = 2; c.weightx = 0;
        d.add(extra != null ? extra : new JLabel(), c);
        return row + 1;
    }

    // Small picker dialog listing installed Steam games (filterable).
    private Game showGamePicker(Component parent) {
        final List<Game> all = VdfHelper.findInstalledGames(steamExe);
        if (all.isEmpty()) {
            JOptionPane.showMessageDialog(parent, tr("noGames"), tr("installedGames"),
                JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        final JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(parent),
            tr("installedGames"), Dialog.ModalityType.APPLICATION_MODAL);
        dlg.getContentPane().setBackground(BG_DARK);
        dlg.setLayout(new BorderLayout(8, 8));
        ((JComponent) dlg.getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        final DefaultListModel<Game> model = new DefaultListModel<Game>();
        for (Game g : all) model.addElement(g);
        final JList<Game> list = new JList<Game>(model);
        list.setBackground(BG_LIST);
        list.setForeground(TEXT_LT);
        list.setSelectionBackground(ACCENT);
        list.setSelectionForeground(Color.WHITE);
        list.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        if (!model.isEmpty()) list.setSelectedIndex(0);

        final JTextField filter = new JTextField();
        filter.setToolTipText(tr("search"));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            void refilter() {
                String q = filter.getText().trim().toLowerCase();
                model.clear();
                for (Game g : all)
                    if (q.isEmpty() || g.name.toLowerCase().contains(q) || g.appid.contains(q))
                        model.addElement(g);
                if (!model.isEmpty()) list.setSelectedIndex(0);
            }
            public void insertUpdate(DocumentEvent e)  { refilter(); }
            public void removeUpdate(DocumentEvent e)  { refilter(); }
            public void changedUpdate(DocumentEvent e) { refilter(); }
        });

        final Game[] result = new Game[1];
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    result[0] = list.getSelectedValue();
                    dlg.dispose();
                }
            }
        });

        JButton ok     = makeBtn("OK",          ACCENT, ACCENT_HO, TEXT_LT);
        JButton cancel = makeBtn(tr("cancel"),   BG_MID, BG_LIST,   TEXT_MUT);
        ok.setPreferredSize(new Dimension(90, 30));
        cancel.setPreferredSize(new Dimension(90, 30));
        ok.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { result[0] = list.getSelectedValue(); dlg.dispose(); }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { dlg.dispose(); }
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        south.setOpaque(false);
        south.add(cancel); south.add(ok);

        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(BG_MID));
        sp.getViewport().setBackground(BG_LIST);

        dlg.add(filter, BorderLayout.NORTH);
        dlg.add(sp,     BorderLayout.CENTER);
        dlg.add(south,  BorderLayout.SOUTH);
        dlg.setSize(380, 440);
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
        return result[0];
    }

    private void restartUi() {
        dispose();
        SwingUtilities.invokeLater(new Runnable() {
            public void run() { new SteamSwitcher().setVisible(true); }
        });
    }

    // ── Robust Steam shutdown (fixes "doesn't work after logout / change account") ──
    private void stopSteam() {
        try {
            if (steamExe != null) {
                try { Runtime.getRuntime().exec(new String[]{ steamExe, "-shutdown" }); } catch (Exception ignored) {}
            }
            for (int i = 0; i < 24; i++) {       // wait up to ~12s for a clean exit
                if (!isSteamRunning()) return;
                Thread.sleep(500);
            }
            Runtime.getRuntime().exec(new String[]{ "taskkill", "/F", "/IM", "steam.exe" });
            Thread.sleep(1500);
        } catch (Exception ignored) {}
    }

    private boolean isSteamRunning() {
        try {
            Process p = Runtime.getRuntime().exec(
                new String[]{ "tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH" });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line; boolean found = false;
            while ((line = r.readLine()) != null)
                if (line.toLowerCase().contains("steam.exe")) found = true;
            r.close();
            return found;
        } catch (Exception e) { return false; }
    }

    // ── Runtime detection (jar / exe / standalone) ──────────────────────────────
    static String runtimeKind() {
        if (System.getProperty("jpackage.app-path") != null) return "EXE (standalone)";
        String loc = codeLocation();
        if (loc != null) {
            String low = loc.toLowerCase();
            if (low.endsWith(".exe")) return "EXE";
            if (low.endsWith(".jar")) return "JAR";
        }
        return "classes (dev)";
    }

    static File runningFile() {
        String app = System.getProperty("jpackage.app-path");
        if (app != null) return new File(app);
        String loc = codeLocation();
        return loc != null ? new File(loc) : null;
    }

    static String codeLocation() {
        try {
            return new File(SteamSwitcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getPath();
        } catch (Exception e) { return null; }
    }

    // ── Last-login cache ────────────────────────────────────────────────────────
    private void loadLastLogin() {
        LAST_LOGIN.clear();
        if (!LL_FILE.exists()) return;
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(LL_FILE);
            p.load(in);
            for (String k : p.stringPropertyNames())
                try { LAST_LOGIN.put(k, Long.parseLong(p.getProperty(k))); } catch (Exception ignored) {}
        } catch (Exception ignored) {
        } finally { if (in != null) try { in.close(); } catch (Exception ignored) {} }
    }

    private void saveLastLogin() {
        Properties p = new Properties();
        for (Map.Entry<String, Long> e : LAST_LOGIN.entrySet())
            p.setProperty(e.getKey(), Long.toString(e.getValue()));
        OutputStream out = null;
        try {
            out = new FileOutputStream(LL_FILE);
            p.store(out, "SteamSwitcher - last login (epoch ms)");
        } catch (Exception ignored) {
        } finally { if (out != null) try { out.close(); } catch (Exception ignored) {} }
    }

    // ── Nicknames ────────────────────────────────────────────────────────────────
    private void setNicknameForSelected() {
        int idx = accountList.getSelectedIndex();
        if (idx < 0) return;
        Account acc = accounts.get(idx);
        String cur = NICKNAMES.get(acc.steamId());
        Object val = JOptionPane.showInputDialog(this, tr("nicknamePrompt"), tr("nickname"),
            JOptionPane.PLAIN_MESSAGE, null, null, cur != null ? cur : "");
        if (val == null) return;                 // cancelled
        String nick = val.toString().trim();
        if (nick.isEmpty()) NICKNAMES.remove(acc.steamId());   // cleared -> real name returns
        else                NICKNAMES.put(acc.steamId(), nick);
        saveNicknames();
        accountList.repaint();
    }

    private void loadNicknames() {
        NICKNAMES.clear();
        if (!NICK_FILE.exists()) return;
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(NICK_FILE);
            p.load(in);
            for (String k : p.stringPropertyNames()) NICKNAMES.put(k, p.getProperty(k));
        } catch (Exception ignored) {
        } finally { if (in != null) try { in.close(); } catch (Exception ignored) {} }
    }

    private void saveNicknames() {
        Properties p = new Properties();
        for (Map.Entry<String, String> e : NICKNAMES.entrySet())
            p.setProperty(e.getKey(), e.getValue());
        OutputStream out = null;
        try {
            out = new FileOutputStream(NICK_FILE);
            p.store(out, "SteamSwitcher - per-account nicknames");
        } catch (Exception ignored) {
        } finally { if (out != null) try { out.close(); } catch (Exception ignored) {} }
    }

    // =========================================================================
    // Settings persistence
    static class Settings {
        static final File FILE = new File(System.getProperty("user.home"), ".steamswitcher.properties");
        final Properties p = new Properties();
        Settings() {
            if (FILE.exists()) {
                InputStream in = null;
                try { in = new FileInputStream(FILE); p.load(in); }
                catch (Exception ignored) {}
                finally { if (in != null) try { in.close(); } catch (Exception ignored) {} }
            }
        }
        String get(String k, String d) { return p.getProperty(k, d); }
        void set(String k, String v) { if (v == null) p.remove(k); else p.setProperty(k, v); }
        void save() {
            OutputStream o = null;
            try { o = new FileOutputStream(FILE); p.store(o, "SteamSwitcher settings"); }
            catch (Exception ignored) {}
            finally { if (o != null) try { o.close(); } catch (Exception ignored) {} }
        }
    }

    // =========================================================================
    // In-app updater: pulls the latest release from GitHub over HTTPS (no git).
    static class Updater {
        static void checkAndUpdate(final SteamSwitcher app, final Component parent) {
            new Thread(new Runnable() { public void run() {
                try {
                    String json = httpGet("https://api.github.com/repos/" + REPO + "/releases/latest");
                    if (json == null) { msg(parent, tr("updNone")); return; }
                    String tag = find(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
                    if (tag == null) { msg(parent, tr("updNone")); return; }
                    if (!isNewer(tag, VERSION)) { msg(parent, tr("updLatest", VERSION)); return; }

                    int yes = JOptionPane.showConfirmDialog(parent,
                        tr("updAvail", tag, VERSION), tr("checkUpdate"), JOptionPane.YES_NO_OPTION);
                    if (yes != JOptionPane.YES_OPTION) return;

                    String kind = runtimeKind();
                    String wanted = kind.equals("JAR") ? "SteamSwitcher.jar"
                                  : kind.equals("EXE") ? "SteamSwitcher.exe" : null;
                    File target = runningFile();
                    if (wanted == null || target == null || !target.exists()) { msg(parent, tr("updManual")); return; }
                    String asset = findAsset(json, wanted);
                    if (asset == null) { msg(parent, tr("updManual")); return; }

                    app.setStatus(tr("updDownloading"), TEXT_MUT);
                    File dest = new File(target.getParentFile(), target.getName() + ".new");
                    download(asset, dest);

                    swapAndRestart(target, dest);   // launches a hidden helper that swaps + restarts
                    // Show the message synchronously so the user sees it BEFORE we exit.
                    JOptionPane.showMessageDialog(parent, tr("updDone"), tr("checkUpdate"),
                        JOptionPane.INFORMATION_MESSAGE);
                    System.exit(0);
                } catch (Exception ex) {
                    msg(parent, tr("updFail", String.valueOf(ex.getMessage())));
                }
            }}).start();
        }

        private static void swapAndRestart(File target, File downloaded) throws IOException {
            long pid = currentPid();
            String t = target.getAbsolutePath();
            String n = downloaded.getAbsolutePath();

            // Helper batch: wait for this app to close (capped so it can never hang
            // forever), replace the file, relaunch it, then delete itself.
            File bat = File.createTempFile("ssw_update", ".bat");
            StringBuilder s = new StringBuilder();
            s.append("@echo off\r\n");
            s.append("setlocal\r\n");
            s.append("set /a n=0\r\n");
            s.append(":wait\r\n");
            s.append("tasklist /FI \"PID eq ").append(pid).append("\" | find \"").append(pid).append("\" >nul || goto swap\r\n");
            s.append("set /a n+=1\r\n");
            s.append("if %n% GEQ 60 goto swap\r\n");
            s.append("timeout /t 1 /nobreak >nul\r\n");
            s.append("goto wait\r\n");
            s.append(":swap\r\n");
            s.append("move /y \"").append(n).append("\" \"").append(t).append("\" >nul\r\n");
            s.append("start \"\" \"").append(t).append("\"\r\n");
            s.append("del \"%~f0\"\r\n");
            writeText(bat, s.toString());

            // Run the batch in a hidden window (window style 0) so no console pops up.
            File vbs = File.createTempFile("ssw_update", ".vbs");
            writeText(vbs, "CreateObject(\"WScript.Shell\").Run \"cmd /c \"\"" + bat.getAbsolutePath() + "\"\"\", 0, False\r\n");
            Runtime.getRuntime().exec(new String[]{ "wscript", vbs.getAbsolutePath() });
        }

        private static long currentPid() {
            try {
                String n = ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"
                return Long.parseLong(n.substring(0, n.indexOf('@')));
            } catch (Exception e) { return -1; }
        }

        static String findAsset(String json, String fileName) {
            Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            while (m.find()) { String u = m.group(1); if (u.endsWith("/" + fileName)) return u; }
            return null;
        }

        static boolean isNewer(String latest, String current) {
            int[] a = ver(latest), b = ver(current);
            int len = Math.max(a.length, b.length);
            for (int i = 0; i < len; i++) {
                int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
                if (x != y) return x > y;
            }
            return false;
        }

        static int[] ver(String s) {
            if (s == null) return new int[0];
            s = s.trim();
            while (s.length() > 0 && !Character.isDigit(s.charAt(0))) s = s.substring(1);
            String[] parts = s.split("[^0-9]+");
            List<Integer> out = new ArrayList<Integer>();
            for (String p : parts) if (p.length() > 0) try { out.add(Integer.parseInt(p)); } catch (Exception ignored) {}
            int[] r = new int[out.size()];
            for (int i = 0; i < r.length; i++) r[i] = out.get(i);
            return r;
        }

        static String httpGet(String url) throws IOException {
            HttpURLConnection c = open(url);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code >= 300 && code < 400) return httpGet(c.getHeaderField("Location"));
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }

        static void download(String url, File dest) throws IOException {
            HttpURLConnection c = open(url);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { download(c.getHeaderField("Location"), dest); return; }
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
        }

        static HttpURLConnection open(String url) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "SteamSwitcher-Updater");
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            return c;
        }

        static String find(String s, String regex) {
            Matcher m = Pattern.compile(regex).matcher(s);
            return m.find() ? m.group(1) : null;
        }

        static void msg(final Component parent, final String text) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    JOptionPane.showMessageDialog(parent, text, tr("checkUpdate"), JOptionPane.INFORMATION_MESSAGE);
                }
            });
        }

        static void writeText(File f, String content) throws IOException {
            OutputStream o = new FileOutputStream(f);
            o.write(content.getBytes(StandardCharsets.UTF_8));
            o.close();
        }
    }

    // =========================================================================
    // Emoji renderer support (Twemoji PNGs bundled under /resources/emoji)
    static class EmojiText {
        private static final Map<String, Image> CACHE = new HashMap<String, Image>();

        static List<Object> parse(String s) {
            List<Object> out = new ArrayList<Object>();
            StringBuilder text = new StringBuilder();
            int i = 0, n = s.length();
            while (i < n) {
                int cp = s.codePointAt(i);
                int cc = Character.charCount(cp);
                boolean keycap = false;
                if (cp == 0x23 || cp == 0x2A || (cp >= 0x30 && cp <= 0x39)) {
                    int j = i + cc;
                    if (j < n && s.charAt(j) == 0xFE0F) j++;
                    if (j < n && s.charAt(j) == 0x20E3) keycap = true;
                }
                if (isEmojiStart(cp) || keycap) {
                    if (text.length() > 0) { out.add(text.toString()); text.setLength(0); }
                    int start = i, j = i + cc, prev = cp;
                    while (j < n) {
                        int ncp = s.codePointAt(j);
                        int ncc = Character.charCount(ncp);
                        if (isContinuation(ncp) || (prev == 0x200D && isEmojiStart(ncp))) {
                            prev = ncp; j += ncc;
                        } else break;
                    }
                    String cluster = s.substring(start, j);
                    Image img = imageFor(cluster);
                    out.add(img != null ? (Object) img : (Object) cluster);
                    i = j;
                } else {
                    text.appendCodePoint(cp);
                    i += cc;
                }
            }
            if (text.length() > 0) out.add(text.toString());
            return out;
        }

        private static boolean isEmojiStart(int cp) {
            return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0x2190 && cp <= 0x21FF)
                || (cp >= 0x25A0 && cp <= 0x25FF)
                || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049
                || cp == 0x2122 || cp == 0x2139 || cp == 0x24C2
                || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299;
        }

        private static boolean isContinuation(int cp) {
            return cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D
                || (cp >= 0x1F3FB && cp <= 0x1F3FF)
                || cp == 0x20E3
                || (cp >= 0xE0020 && cp <= 0xE007F)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
        }

        private static Image imageFor(String cluster) {
            boolean hasZWJ = cluster.indexOf(0x200D) >= 0;
            Image img = load(codeName(cluster, !hasZWJ));
            if (img == null) img = load(codeName(cluster, true));
            if (img == null) img = load(Integer.toHexString(cluster.codePointAt(0)));
            return img;
        }

        private static String codeName(String cluster, boolean stripFE0F) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < cluster.length()) {
                int cp = cluster.codePointAt(i);
                i += Character.charCount(cp);
                if (stripFE0F && cp == 0xFE0F) continue;
                if (sb.length() > 0) sb.append('-');
                sb.append(Integer.toHexString(cp));
            }
            return sb.toString();
        }

        private static Image load(String code) {
            if (code == null || code.isEmpty()) return null;
            if (CACHE.containsKey(code)) return CACHE.get(code);
            Image img = null;
            URL u = SteamSwitcher.class.getResource("/resources/emoji/" + code + ".png");
            if (u != null) { try { img = new ImageIcon(u).getImage(); } catch (Exception ignored) {} }
            CACHE.put(code, img);
            return img;
        }
    }

    // =========================================================================
    static class VdfHelper {
        static String findVdf(String steamExe) {
            if (steamExe != null) {
                File f = new File(new File(steamExe).getParentFile(), "config/loginusers.vdf");
                if (f.exists()) return f.getAbsolutePath();
            }
            String regPath = RegistryHelper.readSteamInstallPath();
            if (regPath != null) {
                File f = new File(regPath, "config/loginusers.vdf");
                if (f.exists()) return f.getAbsolutePath();
            }
            for (String root : new String[]{
                    "C:\\Program Files (x86)\\Steam", "C:\\Program Files\\Steam" }) {
                File f = new File(root, "config\\loginusers.vdf");
                if (f.exists()) return f.getAbsolutePath();
            }
            return null;
        }

        static String findSteamExe() {
            String regPath = RegistryHelper.readSteamInstallPath();
            if (regPath != null) {
                File f = new File(regPath, "steam.exe");
                if (f.exists()) return f.getAbsolutePath();
            }
            for (String p : new String[]{
                    "C:\\Program Files (x86)\\Steam\\steam.exe", "C:\\Program Files\\Steam\\steam.exe" })
                if (new File(p).exists()) return p;
            return null;
        }

        // Scan all Steam library folders for installed games (appmanifest_*.acf).
        static List<Game> findInstalledGames(String steamExe) {
            List<Game> games = new ArrayList<Game>();
            if (steamExe == null) return games;
            File root = new File(steamExe).getParentFile();
            if (root == null) return games;

            LinkedHashSet<String> libs = new LinkedHashSet<String>();
            libs.add(root.getAbsolutePath());
            for (String rel : new String[]{ "config/libraryfolders.vdf", "steamapps/libraryfolders.vdf" }) {
                File lf = new File(root, rel);
                if (!lf.exists()) continue;
                try {
                    Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(readFile(lf.getAbsolutePath()));
                    while (m.find()) libs.add(m.group(1).replace("\\\\", "\\"));
                } catch (Exception ignored) {}
            }

            HashSet<String> seen = new HashSet<String>();
            for (String lib : libs) {
                File sa = new File(lib, "steamapps");
                File[] manifests = sa.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String n) {
                        return n.startsWith("appmanifest_") && n.endsWith(".acf");
                    }
                });
                if (manifests == null) continue;
                for (File mf : manifests) {
                    try {
                        String t = readFile(mf.getAbsolutePath());
                        String appid = find1(t, "\"appid\"\\s+\"(\\d+)\"");
                        String name  = find1(t, "\"name\"\\s+\"([^\"]+)\"");
                        if (appid != null && name != null && seen.add(appid))
                            games.add(new Game(appid, name));
                    } catch (Exception ignored) {}
                }
            }
            Collections.sort(games, new Comparator<Game>() {
                public int compare(Game a, Game b) { return a.name.compareToIgnoreCase(b.name); }
            });
            return games;
        }

        private static String find1(String s, String regex) {
            Matcher m = Pattern.compile(regex).matcher(s);
            return m.find() ? m.group(1) : null;
        }

        static List<Account> readAccounts(String vdfPath) {
            List<Account> list = new ArrayList<Account>();
            try {
                String text = readFile(vdfPath);
                Pattern p = Pattern.compile("\"(?<id>\\d{10,})\"\\s*\\{(?<body>[^{}]*)\\}", Pattern.DOTALL);
                Matcher m = p.matcher(text);
                while (m.find()) {
                    String body = m.group("body");
                    String user = extract(body, "AccountName");
                    String disp = extract(body, "PersonaName");
                    if (user != null) list.add(new Account(m.group("id"), user, disp != null ? disp : user));
                }
            } catch (Exception ignored) {}
            return list;
        }

        // Account marked MostRecent=1 in the vdf (fallback for the "active" highlight).
        static String findMostRecent(String vdfPath) {
            try {
                String text = readFile(vdfPath);
                Matcher m = Pattern.compile("\"(?:\\d{10,})\"\\s*\\{([^{}]*)\\}", Pattern.DOTALL).matcher(text);
                while (m.find()) {
                    String body = m.group(1);
                    if ("1".equals(extract(body, "MostRecent"))) return extract(body, "AccountName");
                }
            } catch (Exception ignored) {}
            return null;
        }

        static void patchAccount(String vdfPath, String target) {
            try {
                String text = readFile(vdfPath);
                Pattern p = Pattern.compile("(\"(?:\\d{10,})\"\\s*\\{)([^{}]*?)(\\})", Pattern.DOTALL);
                Matcher m = p.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String body   = m.group(2);
                    boolean isTgt = target.equalsIgnoreCase(extract(body, "AccountName"));
                    body = setField(body, "RememberPassword", "1");
                    body = setField(body, "MostRecent",       isTgt ? "1" : "0");
                    if (isTgt) body = setField(body, "AllowAutoLogin", "1");
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + body + m.group(3)));
                }
                m.appendTail(sb);
                writeFile(vdfPath, sb.toString());
            } catch (Exception ignored) {}
        }

        static void removeAccount(String vdfPath, String target) {
            try {
                String text = readFile(vdfPath);
                Pattern p = Pattern.compile("\"(?:\\d{10,})\"\\s*\\{(?:[^{}]*)\\}", Pattern.DOTALL);
                Matcher m = p.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String accName = extract(m.group(), "AccountName");
                    if (!target.equalsIgnoreCase(accName))
                        m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                    else
                        m.appendReplacement(sb, "");
                }
                m.appendTail(sb);
                writeFile(vdfPath, sb.toString());
            } catch (Exception ignored) {}
        }

        private static String extract(String body, String key) {
            Matcher m = Pattern.compile("\"" + key + "\"\\s+\"(?<v>[^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(body);
            return m.find() ? m.group("v") : null;
        }

        private static String setField(String body, String key, String val) {
            Pattern p = Pattern.compile("\"(" + key + ")\"\\s+\"[^\"]*\"", Pattern.CASE_INSENSITIVE);
            if (p.matcher(body).find())
                return p.matcher(body).replaceFirst("\"" + key + "\"\t\t\"" + val + "\"");
            return body.replaceAll("\\s+$", "") + "\n\t\t\"" + key + "\"\t\t\"" + val + "\"\n\t\t";
        }
    }

    // =========================================================================
    static class RegistryHelper {
        static String readSteamInstallPath() {
            String v = readReg("HKCU\\SOFTWARE\\Valve\\Steam", "SteamPath");
            if (v == null) v = readReg("HKLM\\SOFTWARE\\Valve\\Steam", "InstallPath");
            return v;
        }
        static String readAutoLoginUser() {
            return readReg("HKCU\\SOFTWARE\\Valve\\Steam", "AutoLoginUser");
        }
        static void setAutoLogin(String username) {
            writeReg("HKCU\\SOFTWARE\\Valve\\Steam", "AutoLoginUser",    "REG_SZ",    username);
            writeReg("HKCU\\SOFTWARE\\Valve\\Steam", "RememberPassword", "REG_DWORD", "1");
        }
        private static String readReg(String path, String key) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{ "reg", "query", path, "/v", key });
                StringBuilder out = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
                r.close();
                Matcher m = Pattern.compile(key + "\\s+REG_\\w+\\s+(.+)").matcher(out.toString());
                return m.find() ? m.group(1).trim() : null;
            } catch (Exception e) { return null; }
        }
        private static void writeReg(String path, String key, String type, String value) {
            try {
                Runtime.getRuntime().exec(new String[]{
                    "reg", "add", path, "/v", key, "/t", type, "/d", value, "/f" });
            } catch (Exception ignored) {}
        }
    }

    // ── File I/O helpers ────────────────────────────────────────────────────────
    static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
    static void writeFile(String path, String content) throws IOException {
        Files.write(Paths.get(path), content.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // Internationalization. English is the base/fallback; missing keys fall back
    // to English. Default language follows the OS locale.
    static class I18n {
        static final Map<String, Map<String, String>> L = new LinkedHashMap<String, Map<String, String>>();
        static final Map<String, String> NAMES = new LinkedHashMap<String, String>();
        static String lang = "en";

        static void setLanguage(String code) {
            if (code == null || code.equals("system")) code = detect();
            lang = L.containsKey(code) ? code : "en";
        }
        static String detect() {
            String l = Locale.getDefault().getLanguage();
            return L.containsKey(l) ? l : "en";
        }
        static String[] codes() { return L.keySet().toArray(new String[0]); }
        static String displayName(String code) {
            String n = NAMES.get(code); return n != null ? n : code;
        }
        static String tr(String key, Object... args) {
            String s = L.get(lang).get(key);
            if (s == null) s = L.get("en").get(key);
            if (s == null) s = key;
            return args.length == 0 ? s : String.format(s, args);
        }
        private static void lang(String code, String displayName, String... kv) {
            Map<String, String> m = new HashMap<String, String>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            L.put(code, m);
            NAMES.put(code, displayName);
        }

        static {
            lang("en", "English",
                "title","Steam Account Switcher",
                "subtitle","Double-click an account to log in",
                "login","Log in",
                "delete","Delete",
                "settings","Settings",
                "launchTip","Launch %s",
                "loaded","%s account(s) loaded",
                "stopping","Stopping Steam…",
                "patching","Updating login files…",
                "starting","Starting Steam – %s…",
                "loggedIn","Logged in: %s",
                "launching","Launching %s…",
                "launchFail","Couldn't launch %s",
                "steamNotFound","Steam.exe not found – open Settings",
                "selectFirst","Select an account first.",
                "confirmTitle","Confirm",
                "confirmLogin","Log in to this account?\n\n  %s  (%s)\n\nSteam will restart.",
                "errorTitle","Error",
                "steamMissing","Steam.exe not found.\nSet its path in Settings.",
                "deleteTitle","Delete",
                "confirmDelete","Really delete this account?\n\n  %s  (%s)\n\nThis also removes it from Steam's login list.",
                "deleted","Deleted: %s",
                "settingsTitle","Settings",
                "language","Language",
                "systemDefault","System default",
                "quickGame","Quick-launch game",
                "appId","App ID",
                "steamPath","Steam.exe path",
                "browse","Browse…",
                "version","Version",
                "runAs","Running as",
                "checkUpdate","Check for updates",
                "save","Save",
                "cancel","Cancel",
                "updLatest","You're up to date (%s).",
                "updAvail","New version %s available (you have %s). Download now?",
                "updNone","No release has been published yet.",
                "updDownloading","Downloading update…",
                "updDone","Update downloaded. The app will now close and reopen with the new version.",
                "updFail","Update failed: %s",
                "updManual","Auto-update isn't supported for this build. Please download it from GitHub.",
                "pickGame","Games…",
                "installedGames","Installed games",
                "noGames","No installed games found.",
                "search","Search",
                "nickname","Set nickname…",
                "nicknamePrompt","Nickname for this account (leave empty to clear):");

            lang("hu", "Magyar",
                "title","Steam Fiókváltó",
                "subtitle","Dupla kattintás a fiókra = bejelentkezés",
                "login","Bejelentkezés",
                "delete","Törlés",
                "settings","Beállítások",
                "launchTip","%s indítása",
                "loaded","%s fiók betöltve",
                "stopping","Steam leállítása…",
                "patching","Bejelentkezési fájlok módosítása…",
                "starting","Steam indítása – %s…",
                "loggedIn","Bejelentkezve: %s",
                "launching","%s indítása…",
                "launchFail","Nem sikerült elindítani: %s",
                "steamNotFound","Steam.exe nem található – nyisd meg a Beállításokat",
                "selectFirst","Előbb válassz egy fiókot.",
                "confirmTitle","Megerősítés",
                "confirmLogin","Bejelentkezel ezzel a fiókkal?\n\n  %s  (%s)\n\nA Steam újraindul.",
                "errorTitle","Hiba",
                "steamMissing","Steam.exe nem található.\nÁllítsd be az útvonalát a Beállításokban.",
                "deleteTitle","Törlés",
                "confirmDelete","Biztosan törlöd ezt a fiókot?\n\n  %s  (%s)\n\nEz a Steam bejelentkezési listájából is eltávolítja.",
                "deleted","Törölve: %s",
                "settingsTitle","Beállítások",
                "language","Nyelv",
                "systemDefault","Rendszer alapértelmezett",
                "quickGame","Gyorsindítós játék",
                "appId","App azonosító",
                "steamPath","Steam.exe útvonal",
                "browse","Tallózás…",
                "version","Verzió",
                "runAs","Futtatás módja",
                "checkUpdate","Frissítés keresése",
                "save","Mentés",
                "cancel","Mégse",
                "updLatest","A legfrissebb verziót használod (%s).",
                "updAvail","Új verzió érhető el: %s (jelenleg: %s). Letöltöd most?",
                "updNone","Még nincs közzétett kiadás.",
                "updDownloading","Frissítés letöltése…",
                "updDone","Frissítés letöltve. Az app most bezárul és újranyílik az új verzióval.",
                "updFail","A frissítés nem sikerült: %s",
                "updManual","Ehhez a változathoz nincs automatikus frissítés. Töltsd le a GitHubról.",
                "pickGame","Játékok…",
                "installedGames","Telepített játékok",
                "noGames","Nincs telepített játék.",
                "search","Keresés",
                "nickname","Becenév beállítása…",
                "nicknamePrompt","Becenév ehhez a fiókhoz (üresen hagyva törli):");

            lang("de", "Deutsch",
                "title","Steam-Kontowechsler",
                "subtitle","Doppelklick auf ein Konto zum Anmelden",
                "login","Anmelden",
                "delete","Löschen",
                "settings","Einstellungen",
                "launchTip","%s starten",
                "loaded","%s Konto(s) geladen",
                "stopping","Steam wird beendet…",
                "patching","Anmeldedateien werden aktualisiert…",
                "starting","Steam wird gestartet – %s…",
                "loggedIn","Angemeldet: %s",
                "launching","%s wird gestartet…",
                "launchFail","%s konnte nicht gestartet werden",
                "steamNotFound","Steam.exe nicht gefunden – Einstellungen öffnen",
                "selectFirst","Bitte zuerst ein Konto auswählen.",
                "confirmTitle","Bestätigen",
                "confirmLogin","Mit diesem Konto anmelden?\n\n  %s  (%s)\n\nSteam wird neu gestartet.",
                "errorTitle","Fehler",
                "steamMissing","Steam.exe nicht gefunden.\nPfad in den Einstellungen festlegen.",
                "deleteTitle","Löschen",
                "confirmDelete","Dieses Konto wirklich löschen?\n\n  %s  (%s)\n\nEs wird auch aus der Steam-Anmeldeliste entfernt.",
                "deleted","Gelöscht: %s",
                "settingsTitle","Einstellungen",
                "language","Sprache",
                "systemDefault","Systemstandard",
                "quickGame","Schnellstart-Spiel",
                "appId","App-ID",
                "steamPath","Steam.exe-Pfad",
                "browse","Durchsuchen…",
                "version","Version",
                "runAs","Ausgeführt als",
                "checkUpdate","Nach Updates suchen",
                "save","Speichern",
                "cancel","Abbrechen",
                "updLatest","Du bist aktuell (%s).",
                "updAvail","Neue Version %s verfügbar (aktuell: %s). Jetzt herunterladen?",
                "updNone","Es wurde noch kein Release veröffentlicht.",
                "updDownloading","Update wird heruntergeladen…",
                "updDone","Update heruntergeladen. Neustart…",
                "updFail","Update fehlgeschlagen: %s",
                "updManual","Auto-Update wird für diese Variante nicht unterstützt. Bitte von GitHub herunterladen.");

            lang("es", "Español",
                "title","Cambiador de cuentas de Steam",
                "subtitle","Doble clic en una cuenta para iniciar sesión",
                "login","Iniciar sesión",
                "delete","Eliminar",
                "settings","Ajustes",
                "launchTip","Iniciar %s",
                "loaded","%s cuenta(s) cargadas",
                "stopping","Cerrando Steam…",
                "patching","Actualizando archivos de inicio de sesión…",
                "starting","Iniciando Steam – %s…",
                "loggedIn","Sesión iniciada: %s",
                "launching","Iniciando %s…",
                "launchFail","No se pudo iniciar %s",
                "steamNotFound","No se encontró Steam.exe – abre Ajustes",
                "selectFirst","Selecciona una cuenta primero.",
                "confirmTitle","Confirmar",
                "confirmLogin","¿Iniciar sesión con esta cuenta?\n\n  %s  (%s)\n\nSteam se reiniciará.",
                "errorTitle","Error",
                "steamMissing","No se encontró Steam.exe.\nDefine su ruta en Ajustes.",
                "deleteTitle","Eliminar",
                "confirmDelete","¿Eliminar esta cuenta?\n\n  %s  (%s)\n\nTambién se quitará de la lista de Steam.",
                "deleted","Eliminada: %s",
                "settingsTitle","Ajustes",
                "language","Idioma",
                "systemDefault","Predeterminado del sistema",
                "quickGame","Juego de inicio rápido",
                "appId","ID de app",
                "steamPath","Ruta de Steam.exe",
                "browse","Examinar…",
                "version","Versión",
                "runAs","Ejecutándose como",
                "checkUpdate","Buscar actualizaciones",
                "save","Guardar",
                "cancel","Cancelar",
                "updLatest","Estás al día (%s).",
                "updAvail","Nueva versión %s disponible (tienes %s). ¿Descargar ahora?",
                "updNone","Aún no hay ninguna versión publicada.",
                "updDownloading","Descargando actualización…",
                "updDone","Actualización descargada. Reiniciando…",
                "updFail","Error al actualizar: %s",
                "updManual","La actualización automática no es compatible con esta versión. Descárgala desde GitHub.");

            lang("fr", "Français",
                "title","Sélecteur de comptes Steam",
                "subtitle","Double-cliquez sur un compte pour vous connecter",
                "login","Connexion",
                "delete","Supprimer",
                "settings","Paramètres",
                "launchTip","Lancer %s",
                "loaded","%s compte(s) chargé(s)",
                "stopping","Arrêt de Steam…",
                "patching","Mise à jour des fichiers de connexion…",
                "starting","Démarrage de Steam – %s…",
                "loggedIn","Connecté : %s",
                "launching","Lancement de %s…",
                "launchFail","Impossible de lancer %s",
                "steamNotFound","Steam.exe introuvable – ouvrez les Paramètres",
                "selectFirst","Sélectionnez d'abord un compte.",
                "confirmTitle","Confirmer",
                "confirmLogin","Se connecter à ce compte ?\n\n  %s  (%s)\n\nSteam va redémarrer.",
                "errorTitle","Erreur",
                "steamMissing","Steam.exe introuvable.\nDéfinissez son chemin dans les Paramètres.",
                "deleteTitle","Supprimer",
                "confirmDelete","Vraiment supprimer ce compte ?\n\n  %s  (%s)\n\nIl sera aussi retiré de la liste Steam.",
                "deleted","Supprimé : %s",
                "settingsTitle","Paramètres",
                "language","Langue",
                "systemDefault","Par défaut du système",
                "quickGame","Jeu à lancement rapide",
                "appId","ID d'app",
                "steamPath","Chemin de Steam.exe",
                "browse","Parcourir…",
                "version","Version",
                "runAs","Exécuté en tant que",
                "checkUpdate","Rechercher des mises à jour",
                "save","Enregistrer",
                "cancel","Annuler",
                "updLatest","Vous êtes à jour (%s).",
                "updAvail","Nouvelle version %s disponible (vous avez %s). Télécharger ?",
                "updNone","Aucune version publiée pour le moment.",
                "updDownloading","Téléchargement de la mise à jour…",
                "updDone","Mise à jour téléchargée. Redémarrage…",
                "updFail","Échec de la mise à jour : %s",
                "updManual","La mise à jour automatique n'est pas prise en charge pour cette version. Téléchargez-la depuis GitHub.");

            lang("it", "Italiano",
                "title","Selettore account Steam",
                "subtitle","Doppio clic su un account per accedere",
                "login","Accedi",
                "delete","Elimina",
                "settings","Impostazioni",
                "launchTip","Avvia %s",
                "loaded","%s account caricati",
                "stopping","Chiusura di Steam…",
                "patching","Aggiornamento dei file di accesso…",
                "starting","Avvio di Steam – %s…",
                "loggedIn","Accesso eseguito: %s",
                "launching","Avvio di %s…",
                "launchFail","Impossibile avviare %s",
                "steamNotFound","Steam.exe non trovato – apri Impostazioni",
                "selectFirst","Seleziona prima un account.",
                "confirmTitle","Conferma",
                "confirmLogin","Accedere con questo account?\n\n  %s  (%s)\n\nSteam verrà riavviato.",
                "errorTitle","Errore",
                "steamMissing","Steam.exe non trovato.\nImposta il percorso nelle Impostazioni.",
                "deleteTitle","Elimina",
                "confirmDelete","Eliminare davvero questo account?\n\n  %s  (%s)\n\nVerrà rimosso anche dalla lista di Steam.",
                "deleted","Eliminato: %s",
                "settingsTitle","Impostazioni",
                "language","Lingua",
                "systemDefault","Predefinito di sistema",
                "quickGame","Gioco ad avvio rapido",
                "appId","ID app",
                "steamPath","Percorso di Steam.exe",
                "browse","Sfoglia…",
                "version","Versione",
                "runAs","In esecuzione come",
                "checkUpdate","Controlla aggiornamenti",
                "save","Salva",
                "cancel","Annulla",
                "updLatest","Sei aggiornato (%s).",
                "updAvail","Nuova versione %s disponibile (hai %s). Scaricare ora?",
                "updNone","Nessuna release pubblicata ancora.",
                "updDownloading","Download dell'aggiornamento…",
                "updDone","Aggiornamento scaricato. Riavvio…",
                "updFail","Aggiornamento non riuscito: %s",
                "updManual","L'aggiornamento automatico non è supportato per questa versione. Scaricala da GitHub.");

            lang("pt", "Português",
                "title","Alternador de contas Steam",
                "subtitle","Clique duplo numa conta para entrar",
                "login","Entrar",
                "delete","Excluir",
                "settings","Configurações",
                "launchTip","Iniciar %s",
                "loaded","%s conta(s) carregadas",
                "stopping","Encerrando o Steam…",
                "patching","Atualizando arquivos de login…",
                "starting","Iniciando o Steam – %s…",
                "loggedIn","Conectado: %s",
                "launching","Iniciando %s…",
                "launchFail","Não foi possível iniciar %s",
                "steamNotFound","Steam.exe não encontrado – abra Configurações",
                "selectFirst","Selecione uma conta primeiro.",
                "confirmTitle","Confirmar",
                "confirmLogin","Entrar nesta conta?\n\n  %s  (%s)\n\nO Steam será reiniciado.",
                "errorTitle","Erro",
                "steamMissing","Steam.exe não encontrado.\nDefina o caminho em Configurações.",
                "deleteTitle","Excluir",
                "confirmDelete","Excluir mesmo esta conta?\n\n  %s  (%s)\n\nTambém será removida da lista do Steam.",
                "deleted","Excluída: %s",
                "settingsTitle","Configurações",
                "language","Idioma",
                "systemDefault","Padrão do sistema",
                "quickGame","Jogo de início rápido",
                "appId","ID do app",
                "steamPath","Caminho do Steam.exe",
                "browse","Procurar…",
                "version","Versão",
                "runAs","Executando como",
                "checkUpdate","Procurar atualizações",
                "save","Salvar",
                "cancel","Cancelar",
                "updLatest","Você está atualizado (%s).",
                "updAvail","Nova versão %s disponível (você tem %s). Baixar agora?",
                "updNone","Nenhuma versão publicada ainda.",
                "updDownloading","Baixando atualização…",
                "updDone","Atualização baixada. Reiniciando…",
                "updFail","Falha na atualização: %s",
                "updManual","A atualização automática não é suportada nesta versão. Baixe pelo GitHub.");

            lang("ru", "Русский",
                "title","Переключатель аккаунтов Steam",
                "subtitle","Двойной щелчок по аккаунту — вход",
                "login","Войти",
                "delete","Удалить",
                "settings","Настройки",
                "launchTip","Запустить %s",
                "loaded","Загружено аккаунтов: %s",
                "stopping","Остановка Steam…",
                "patching","Обновление файлов входа…",
                "starting","Запуск Steam — %s…",
                "loggedIn","Выполнен вход: %s",
                "launching","Запуск %s…",
                "launchFail","Не удалось запустить %s",
                "steamNotFound","Steam.exe не найден — откройте Настройки",
                "selectFirst","Сначала выберите аккаунт.",
                "confirmTitle","Подтверждение",
                "confirmLogin","Войти в этот аккаунт?\n\n  %s  (%s)\n\nSteam будет перезапущен.",
                "errorTitle","Ошибка",
                "steamMissing","Steam.exe не найден.\nУкажите путь в Настройках.",
                "deleteTitle","Удалить",
                "confirmDelete","Точно удалить этот аккаунт?\n\n  %s  (%s)\n\nОн также будет удалён из списка входа Steam.",
                "deleted","Удалено: %s",
                "settingsTitle","Настройки",
                "language","Язык",
                "systemDefault","Системный по умолчанию",
                "quickGame","Игра для быстрого запуска",
                "appId","ID приложения",
                "steamPath","Путь к Steam.exe",
                "browse","Обзор…",
                "version","Версия",
                "runAs","Запущено как",
                "checkUpdate","Проверить обновления",
                "save","Сохранить",
                "cancel","Отмена",
                "updLatest","У вас последняя версия (%s).",
                "updAvail","Доступна новая версия %s (у вас %s). Скачать?",
                "updNone","Релизов пока нет.",
                "updDownloading","Загрузка обновления…",
                "updDone","Обновление загружено. Перезапуск…",
                "updFail","Ошибка обновления: %s",
                "updManual","Авто-обновление не поддерживается для этой сборки. Скачайте с GitHub.");

            lang("pl", "Polski",
                "title","Przełącznik kont Steam",
                "subtitle","Kliknij dwukrotnie konto, aby się zalogować",
                "login","Zaloguj",
                "delete","Usuń",
                "settings","Ustawienia",
                "launchTip","Uruchom %s",
                "loaded","Wczytano kont: %s",
                "stopping","Zamykanie Steam…",
                "patching","Aktualizacja plików logowania…",
                "starting","Uruchamianie Steam – %s…",
                "loggedIn","Zalogowano: %s",
                "launching","Uruchamianie %s…",
                "launchFail","Nie można uruchomić %s",
                "steamNotFound","Nie znaleziono Steam.exe – otwórz Ustawienia",
                "selectFirst","Najpierw wybierz konto.",
                "confirmTitle","Potwierdź",
                "confirmLogin","Zalogować się na to konto?\n\n  %s  (%s)\n\nSteam zostanie uruchomiony ponownie.",
                "errorTitle","Błąd",
                "steamMissing","Nie znaleziono Steam.exe.\nUstaw ścieżkę w Ustawieniach.",
                "deleteTitle","Usuń",
                "confirmDelete","Na pewno usunąć to konto?\n\n  %s  (%s)\n\nZostanie też usunięte z listy logowania Steam.",
                "deleted","Usunięto: %s",
                "settingsTitle","Ustawienia",
                "language","Język",
                "systemDefault","Domyślny systemowy",
                "quickGame","Gra szybkiego startu",
                "appId","ID aplikacji",
                "steamPath","Ścieżka Steam.exe",
                "browse","Przeglądaj…",
                "version","Wersja",
                "runAs","Uruchomiono jako",
                "checkUpdate","Sprawdź aktualizacje",
                "save","Zapisz",
                "cancel","Anuluj",
                "updLatest","Masz najnowszą wersję (%s).",
                "updAvail","Dostępna nowa wersja %s (masz %s). Pobrać teraz?",
                "updNone","Nie opublikowano jeszcze żadnego wydania.",
                "updDownloading","Pobieranie aktualizacji…",
                "updDone","Pobrano aktualizację. Ponowne uruchamianie…",
                "updFail","Aktualizacja nie powiodła się: %s",
                "updManual","Automatyczna aktualizacja nie jest obsługiwana dla tej wersji. Pobierz z GitHub.");

            lang("zh", "中文",
                "title","Steam 账户切换器",
                "subtitle","双击账户即可登录",
                "login","登录",
                "delete","删除",
                "settings","设置",
                "launchTip","启动 %s",
                "loaded","已加载 %s 个账户",
                "stopping","正在关闭 Steam…",
                "patching","正在更新登录文件…",
                "starting","正在启动 Steam – %s…",
                "loggedIn","已登录：%s",
                "launching","正在启动 %s…",
                "launchFail","无法启动 %s",
                "steamNotFound","未找到 Steam.exe — 请打开设置",
                "selectFirst","请先选择一个账户。",
                "confirmTitle","确认",
                "confirmLogin","登录此账户？\n\n  %s  (%s)\n\nSteam 将重新启动。",
                "errorTitle","错误",
                "steamMissing","未找到 Steam.exe。\n请在设置中指定其路径。",
                "deleteTitle","删除",
                "confirmDelete","确定删除此账户？\n\n  %s  (%s)\n\n这也会将其从 Steam 登录列表中移除。",
                "deleted","已删除：%s",
                "settingsTitle","设置",
                "language","语言",
                "systemDefault","系统默认",
                "quickGame","快速启动的游戏",
                "appId","App ID",
                "steamPath","Steam.exe 路径",
                "browse","浏览…",
                "version","版本",
                "runAs","运行方式",
                "checkUpdate","检查更新",
                "save","保存",
                "cancel","取消",
                "updLatest","已是最新版本（%s）。",
                "updAvail","有新版本 %s（当前 %s）。现在下载？",
                "updNone","尚未发布任何版本。",
                "updDownloading","正在下载更新…",
                "updDone","更新已下载。正在重启…",
                "updFail","更新失败：%s",
                "updManual","此版本不支持自动更新，请从 GitHub 下载。");

            lang("ja", "日本語",
                "title","Steam アカウント切り替え",
                "subtitle","アカウントをダブルクリックでログイン",
                "login","ログイン",
                "delete","削除",
                "settings","設定",
                "launchTip","%s を起動",
                "loaded","%s 個のアカウントを読み込みました",
                "stopping","Steam を終了しています…",
                "patching","ログインファイルを更新しています…",
                "starting","Steam を起動しています – %s…",
                "loggedIn","ログイン済み: %s",
                "launching","%s を起動しています…",
                "launchFail","%s を起動できませんでした",
                "steamNotFound","Steam.exe が見つかりません – 設定を開いてください",
                "selectFirst","先にアカウントを選択してください。",
                "confirmTitle","確認",
                "confirmLogin","このアカウントでログインしますか？\n\n  %s  (%s)\n\nSteam が再起動します。",
                "errorTitle","エラー",
                "steamMissing","Steam.exe が見つかりません。\n設定でパスを指定してください。",
                "deleteTitle","削除",
                "confirmDelete","このアカウントを削除しますか？\n\n  %s  (%s)\n\nSteam のログイン一覧からも削除されます。",
                "deleted","削除しました: %s",
                "settingsTitle","設定",
                "language","言語",
                "systemDefault","システム既定",
                "quickGame","クイック起動ゲーム",
                "appId","アプリ ID",
                "steamPath","Steam.exe のパス",
                "browse","参照…",
                "version","バージョン",
                "runAs","実行形式",
                "checkUpdate","更新を確認",
                "save","保存",
                "cancel","キャンセル",
                "updLatest","最新の状態です（%s）。",
                "updAvail","新しいバージョン %s があります（現在 %s）。今すぐダウンロードしますか？",
                "updNone","まだリリースが公開されていません。",
                "updDownloading","更新をダウンロードしています…",
                "updDone","更新をダウンロードしました。再起動します…",
                "updFail","更新に失敗しました: %s",
                "updManual","このビルドは自動更新に対応していません。GitHub からダウンロードしてください。");
        }
    }
}
