// Drop Portal: Windows companion for the Drop Android app.
// Sits in the tray, probes the local network for a phone running Drop, and while one is found
// shows a small always-on-top drop target on the right edge of the screen.
// Build on Windows: C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /codepage:65001 /target:winexe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll /r:Microsoft.VisualBasic.dll DropPortal.cs BuildInfo.cs
// (Do not use Mono's mcs: its exe fails on Windows .NET with a TypeLoadException for Queue<T>.)
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

static class Program {
    [STAThread]
    static void Main(string[] args) {
        // Never die silently: show what went wrong and keep a copy in %LOCALAPPDATA%\DropPortal\error.txt
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += delegate(object s, ThreadExceptionEventArgs e) { Fail(e.Exception); };
        AppDomain.CurrentDomain.UnhandledException += delegate(object s, UnhandledExceptionEventArgs e) { Fail(e.ExceptionObject as Exception); };
        try { Run(args); } catch (Exception e) { Fail(e); }
    }

    static void Fail(Exception e) {
        string text = "Drop Portal stopped because of an error:\r\n\r\n" + e;
        try {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DropPortal");
            Directory.CreateDirectory(dir);
            File.WriteAllText(Path.Combine(dir, "error.txt"), text);
            text += "\r\n\r\n(Saved to " + Path.Combine(dir, "error.txt") + ")";
        } catch { }
        MessageBox.Show(text, "Drop Portal", MessageBoxButtons.OK, MessageBoxIcon.Error);
        Environment.Exit(1);
    }

    static void Run(string[] args) {
        // After a self-update the new copy is started with "--after <old pid>": wait for the old one to quit.
        var rest = new List<string>();
        for (int i = 0; i < args.Length; i++) {
            int pid;
            if (args[i] == "--after" && i + 1 < args.Length && int.TryParse(args[i + 1], out pid)) {
                try { Process.GetProcessById(pid).WaitForExit(15000); } catch { }
                i++;
            } else rest.Add(args[i]);
        }
        args = rest.ToArray();
        try { File.Delete(Application.ExecutablePath + ".old"); } catch { }
        bool first;
        using (new Mutex(true, "DropPortal-single-instance", out first)) {
            if (!first) {
                MessageBox.Show("Drop Portal is already running. Look for the blue icon by the clock (click the ^ arrow).", "Drop Portal");
                return;
            }
            Application.EnableVisualStyles();
            Application.Run(new Portal(args));
        }
    }
}

class Portal : Form {
    const int DiscoveryPort = 8079;          // must match Server.DISCOVERY_PORT in the Android app
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string AppKey = @"Software\DropPortal";

    string phone;                            // "ip:port" while a phone is visible, else null
    IPAddress lastIp;                        // probed directly too, in case broadcast is filtered
    DateTime lastSeen;
    bool approved, checking, busy, hot, allowShow;
    string status = "";
    readonly Queue<string> queue = new Queue<string>();
    readonly UdpClient udp = new UdpClient(0);
    readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
    readonly NotifyIcon tray = new NotifyIcon();

    // Items the phone offers to this computer (files or text), shown as clickable rows under the drop area.
    class PhoneItem { public long Id, Size; public string Name, Text; }
    const int BaseH = 230, RowH = 30, MaxRows = 4;
    List<PhoneItem> items = new List<PhoneItem>();
    readonly Dictionary<string, string> fetched = new Dictionary<string, string>();  // phone/id -> local path
    readonly HashSet<string> ignored = new HashSet<string>();                       // rows hidden on this PC
    bool listing, downloading;
    int hoverRow = -1;
    ContextMenuStrip trayMenu;

    static string Key(PhoneItem it) { return it.Id + "|" + (it.Name ?? it.Text); }

    // ---- where received files go (changeable from the tray menu) ----
    static string SaveDir() {
        try {
            using (var k = Registry.CurrentUser.OpenSubKey(AppKey)) {
                var s = k == null ? null : k.GetValue("saveDir") as string;
                if (!string.IsNullOrEmpty(s)) return s;
            }
        } catch { }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Drop");
    }

    void ChooseSaveDir() {
        using (var d = new FolderBrowserDialog { Description = "Where should files from your phone be saved?", SelectedPath = SaveDir(), ShowNewFolderButton = true }) {
            if (d.ShowDialog() != DialogResult.OK) return;
            try { Registry.CurrentUser.CreateSubKey(AppKey).SetValue("saveDir", d.SelectedPath); } catch { }
            SetStatus("Saving to " + Path.GetFileName(d.SelectedPath.TrimEnd('\\')));
            try { tray.ShowBalloonTip(4000, "Drop", "Files from your phone will be saved to\n" + d.SelectedPath, ToolTipIcon.Info); } catch { }
        }
    }

    // ---- per-row menu: open / show / ignore / delete ----
    void RowMenu(PhoneItem it, Point at) {
        var m = new ContextMenuStrip();
        string have;
        bool local = it.Text == null && fetched.TryGetValue(phone + "/" + Key(it), out have) && File.Exists(have);
        m.Items.Add(it.Text != null ? "Copy text" : "Open", null, delegate { OpenItem(it); });
        if (local) m.Items.Add("Show in folder", null, delegate {
            try { Process.Start("explorer.exe", "/select,\"" + fetched[phone + "/" + Key(it)] + "\""); } catch { }
        });
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Ignore (hide here)", null, delegate { Ignore(it); });
        m.Items.Add(local ? "Delete from PC and phone…" : "Delete from phone…", null, delegate { Delete(it); });
        m.Closed += delegate { BeginInvoke((MethodInvoker)delegate { m.Dispose(); }); };
        m.Show(this, at);
    }

    void Ignore(PhoneItem it) {
        lock (ignored) ignored.Add(Key(it));
        items.RemoveAll(x => Key(x) == Key(it));
        hoverRow = -1;
        Relayout();
        SetStatus("Hidden");
    }

    void Delete(PhoneItem it) {
        string key = phone + "/" + Key(it), path;
        bool local = it.Text == null && fetched.TryGetValue(key, out path) && File.Exists(path);
        string what = it.Text != null ? "this text" : "\"" + it.Name + "\"";
        string msg = local
            ? "Delete " + what + "?\n\nThe copy on this PC goes to the Recycle Bin, and it is removed from Drop's list on the phone. The original file on the phone is not touched."
            : "Remove " + what + " from Drop's list on the phone?\n\nThe original file on the phone is not touched.";
        if (MessageBox.Show(msg, "Drop", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
        if (local) {
            try {
                Microsoft.VisualBasic.FileIO.FileSystem.DeleteFile(fetched[key],
                    Microsoft.VisualBasic.FileIO.UIOption.OnlyErrorDialogs, Microsoft.VisualBasic.FileIO.RecycleOption.SendToRecycleBin);
                fetched.Remove(key);
            } catch (Exception e) { SetStatus("Could not delete PC copy\n" + Reason(e)); return; }
        }
        Ignore(it);                              // gone from the panel right away
        string target = phone;
        ThreadPool.QueueUserWorkItem(delegate {
            int code = 0;
            try {
                var r = Request(target, "/remove?id=" + it.Id, "POST");
                r.ContentLength = 0;
                using (var resp = (HttpWebResponse)r.GetResponse()) code = (int)resp.StatusCode;
            } catch (WebException e) { var resp = e.Response as HttpWebResponse; if (resp != null) code = (int)resp.StatusCode; }
            catch { }
            BeginInvoke((MethodInvoker)delegate {
                if (code == 200) SetStatus(local ? "Deleted on PC and phone ✓" : "Removed from phone ✓");
                else if (code == 404) SetStatus((local ? "Deleted on PC.\n" : "") + "Update Drop on the phone to remove it there");
                else SetStatus((local ? "Deleted on PC.\n" : "") + "Phone did not answer");
            });
        });
    }

    public Portal(string[] args) {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        Size = new Size(190, BaseH);
        DoubleBuffered = true;
        AllowDrop = true;
        Cursor = Cursors.Hand;
        Reshape();
        var h = Handle;                      // create the window handle now so BeginInvoke works while hidden

        foreach (var a in args) AddPath(a);  // files passed on the command line are sent once the phone appears

        var menu = new ContextMenuStrip();
        var startup = new ToolStripMenuItem("Start with Windows") { CheckOnClick = true };
        using (var k = Registry.CurrentUser.CreateSubKey(AppKey)) {
            if (k.GetValue("init") == null) { k.SetValue("init", 1); SetStartup(true); }
        }
        startup.Checked = GetStartup();
        startup.CheckedChanged += delegate { SetStartup(startup.Checked); };
        menu.Items.Add(startup);
        menu.Items.Add("Save received files to…", null, delegate { ChooseSaveDir(); });
        menu.Items.Add("Open received files folder", null, delegate {
            try { Directory.CreateDirectory(SaveDir()); Process.Start("explorer.exe", "\"" + SaveDir() + "\""); } catch { }
        });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Check for updates" + (BuildInfo.Version > 0 ? " (v" + BuildInfo.Version + ")" : ""), null, delegate { CheckUpdate(true); });
        menu.Items.Add("Exit", null, delegate { tray.Visible = false; Application.Exit(); });
        trayMenu = menu;

        // Look for a newer portal on GitHub shortly after start, then every 6 hours.
        var upd = new System.Windows.Forms.Timer { Interval = 20000 };
        upd.Tick += delegate { upd.Interval = 6 * 3600 * 1000; CheckUpdate(false); };
        upd.Start();
        tray.Icon = MakeIcon();
        tray.Text = "Drop: waiting for phone";
        tray.ContextMenuStrip = menu;
        tray.Visible = true;
        try { tray.ShowBalloonTip(4000, "Drop is running", "The portal appears when you open Drop on your phone.", ToolTipIcon.Info); } catch { }

        try { string s = (string)Registry.CurrentUser.CreateSubKey(AppKey).GetValue("lastIp"); if (s != null) lastIp = IPAddress.Parse(s); } catch { }
        udp.EnableBroadcast = true;
        try { udp.Client.IOControl(-1744830452, new byte[] { 0 }, null); } catch { }  // SIO_UDP_CONNRESET off: ignore ICMP "port closed"
        Listen();
        timer.Interval = 2000;
        timer.Tick += delegate { Tick(); };
        timer.Start();
        Tick();

        // Show the panel for a few seconds on launch so it is obvious the app is running.
        SetStatus("Open Drop on your phone");
        Place();
        allowShow = true;
        Show();
        var intro = new System.Windows.Forms.Timer { Interval = 8000 };
        intro.Tick += delegate { intro.Stop(); if (phone == null) Hide(); };
        intro.Start();
    }

    void Place() {
        var wa = Screen.PrimaryScreen.WorkingArea;
        Location = new Point(wa.Right - Width - 12, wa.Top + (wa.Height - Height) / 2);
    }

    void Reshape() {
        using (var path = Rounded(new Rectangle(0, 0, Width, Height), 18)) Region = new Region(path);
    }

    // Grow or shrink the panel to fit the rows of items from the phone.
    void Relayout() {
        int rows = Math.Min(items.Count, MaxRows);
        int h = BaseH + (rows > 0 ? 22 + rows * RowH + 6 : 0);
        if (Height != h) { Height = h; Reshape(); Place(); }
        Invalidate();
    }

    int RowAt(Point p) {
        int rows = Math.Min(items.Count, MaxRows);
        if (p.Y < BaseH + 22 || p.X < 8 || p.X > Width - 8) return -1;
        int i = (p.Y - BaseH - 22) / RowH;
        return i < rows ? i : -1;
    }

    // ---- stay hidden until a phone is found; never steal keyboard focus ----
    protected override void SetVisibleCore(bool value) { base.SetVisibleCore(allowShow && value); }
    protected override bool ShowWithoutActivation { get { return true; } }
    protected override CreateParams CreateParams {
        get { var p = base.CreateParams; p.ExStyle |= 0x08000000 | 0x80; return p; }  // WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW
    }

    // ---- discovery ----
    void Tick() {
        byte[] probe = Encoding.UTF8.GetBytes("DROP?");
        var targets = new List<IPAddress> { IPAddress.Broadcast };
        if (lastIp != null) targets.Add(lastIp);
        try {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces()) {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses) {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                    byte[] ip = ua.Address.GetAddressBytes(), mask = ua.IPv4Mask.GetAddressBytes();
                    if (mask[0] == 0) continue;
                    uint self = ToUint(ip), m = ToUint(mask), net = self & m, bcast = net | ~m;
                    targets.Add(FromUint(bcast));            // directed broadcast for this network
                    // Many routers and phones drop broadcasts, so while no phone is known, also probe
                    // every address on small networks directly (a /24 is 254 tiny packets).
                    if (phone == null && bcast - net <= 1024)
                        for (uint a = net + 1; a < bcast; a++) if (a != self) targets.Add(FromUint(a));
                }
            }
        } catch { }
        foreach (var t in targets) { try { udp.Send(probe, probe.Length, new IPEndPoint(t, DiscoveryPort)); } catch { } }

        if (phone != null && !busy && (DateTime.UtcNow - lastSeen).TotalSeconds > 7) {
            phone = null; approved = false;
            tray.Text = "Drop: waiting for phone";
            items.Clear(); hoverRow = -1; Relayout();
            Hide();
        }
        if (phone != null && !approved && !checking) CheckApproval();
        if (phone != null && approved && !listing) PollList();
    }

    // ---- receiving: what the phone has shared with computers ----
    void PollList() {
        string target = phone;
        listing = true;
        ThreadPool.QueueUserWorkItem(delegate {
            List<PhoneItem> list = null;
            int code = 0;
            try {
                var r = Request(target, "/list", "GET");
                using (var resp = (HttpWebResponse)r.GetResponse())
                using (var rd = new StreamReader(resp.GetResponseStream(), Encoding.UTF8)) {
                    code = 200;
                    list = new List<PhoneItem>();
                    var raw = new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject(rd.ReadToEnd()) as object[];
                    if (raw != null) foreach (var o in raw) {
                        var d = o as Dictionary<string, object>;
                        if (d == null) continue;
                        var it = new PhoneItem { Id = Convert.ToInt64(d["id"]), Size = -1 };
                        object v;
                        if (d.TryGetValue("text", out v)) it.Text = v as string;
                        if (d.TryGetValue("name", out v)) it.Name = v as string;
                        if (d.TryGetValue("size", out v) && v != null) it.Size = Convert.ToInt64(v);
                        if ((it.Text != null || it.Name != null) && !IsIgnored(it)) list.Add(it);
                    }
                }
            } catch (WebException e) { var resp = e.Response as HttpWebResponse; if (resp != null) code = (int)resp.StatusCode; }
            catch { }
            BeginInvoke((MethodInvoker)delegate {
                listing = false;
                if (target != phone) return;
                if (code == 403) { approved = false; SetStatus("Tap Allow on your phone"); return; }
                if (list == null) return;
                bool changed = list.Count != items.Count;
                for (int i = 0; !changed && i < list.Count; i++) changed = list[i].Id != items[i].Id;
                if (!changed) return;
                bool grew = list.Count > 0 && (items.Count == 0 || list[0].Id != items[0].Id);
                items = list;
                hoverRow = -1;
                Relayout();
                if (grew && !busy && !downloading) SetStatus("From phone: click to open,\nright-click to ignore or delete");
            });
        });
    }

    // ---- self-update from GitHub Releases (tagged v<number>, carrying DropPortal.exe) ----
    bool updating;

    void CheckUpdate(bool manual) {
        if (updating) return;
        ThreadPool.QueueUserWorkItem(delegate {
            int latest = 0; string url = null, err = null;
            try { FindUpdate(out latest, out url); } catch (Exception e) { err = e.Message; }
            BeginInvoke((MethodInvoker)delegate {
                if (url != null && latest > BuildInfo.Version) {
                    int declined = 0;
                    try { declined = Convert.ToInt32(Registry.CurrentUser.CreateSubKey(AppKey).GetValue("declined", 0)); } catch { }
                    if (manual || latest != declined) OfferUpdate(latest, url);
                } else if (manual) {
                    MessageBox.Show(err != null ? "Could not check for updates:\n" + err
                        : "Drop Portal is up to date" + (BuildInfo.Version > 0 ? " (v" + BuildInfo.Version + ")." : "."), "Drop Portal");
                }
            });
        });
    }

    static void FindUpdate(out int latest, out string url) {
        latest = 0; url = null;
        ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072;   // TLS 1.2, required by GitHub
        var r = (HttpWebRequest)WebRequest.Create("https://api.github.com/repos/" + BuildInfo.Repo + "/releases?per_page=20");
        r.UserAgent = "Drop-Portal";
        r.Accept = "application/vnd.github+json";
        r.Timeout = 15000;
        string json;
        using (var resp = r.GetResponse()) using (var rd = new StreamReader(resp.GetResponseStream(), Encoding.UTF8)) json = rd.ReadToEnd();
        var rels = new System.Web.Script.Serialization.JavaScriptSerializer { MaxJsonLength = 1 << 24 }.DeserializeObject(json) as object[];
        if (rels == null) return;
        string prefix = "https://github.com/" + BuildInfo.Repo + "/";
        foreach (var o in rels) {
            var rel = o as Dictionary<string, object>;
            if (rel == null || true.Equals(rel["draft"]) || true.Equals(rel["prerelease"])) continue;
            int v;
            if (!int.TryParse(System.Text.RegularExpressions.Regex.Replace(rel["tag_name"] as string ?? "", "\\D", ""), out v) || v <= latest) continue;
            var assets = rel["assets"] as object[];
            if (assets == null) continue;
            foreach (var a in assets) {
                var asset = a as Dictionary<string, object>;
                string link = asset == null ? null : asset["browser_download_url"] as string;
                if (asset != null && "DropPortal.exe".Equals(asset["name"]) && link != null && link.StartsWith(prefix)) { latest = v; url = link; }
            }
        }
    }

    void OfferUpdate(int version, string url) {
        var answer = MessageBox.Show("A new version of Drop Portal (v" + version + ") is available.\n\nUpdate now? The portal restarts by itself.",
            "Drop Portal update", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
        if (answer != DialogResult.Yes) {
            try { Registry.CurrentUser.CreateSubKey(AppKey).SetValue("declined", version); } catch { }   // ask again for the next version
            return;
        }
        updating = true;
        SetStatus("Downloading update…");
        string exe = Application.ExecutablePath, fresh = exe + ".new";
        ThreadPool.QueueUserWorkItem(delegate {
            string err = null;
            try {
                var r = (HttpWebRequest)WebRequest.Create(url);
                r.UserAgent = "Drop-Portal";
                r.Timeout = 30000;
                using (var resp = r.GetResponse()) using (var src = resp.GetResponseStream()) using (var dst = File.Create(fresh)) src.CopyTo(dst);
                // Refuse anything that is not a DropPortal .NET program.
                if (System.Reflection.AssemblyName.GetAssemblyName(fresh).Name != "DropPortal") throw new IOException("download is not Drop Portal");
            } catch (Exception e) { err = e.Message; try { File.Delete(fresh); } catch { } }
            BeginInvoke((MethodInvoker)delegate {
                if (err != null) { updating = false; SetStatus("Update failed"); MessageBox.Show("Update failed:\n" + err, "Drop Portal"); return; }
                try {
                    try { File.Delete(exe + ".old"); } catch { }
                    File.Move(exe, exe + ".old");           // a running exe can be renamed, not overwritten
                    File.Move(fresh, exe);
                    Process.Start(exe, "--after " + Process.GetCurrentProcess().Id);
                    tray.Visible = false;
                    Application.Exit();
                } catch (Exception e) {
                    try { if (!File.Exists(exe)) File.Move(exe + ".old", exe); } catch { }
                    updating = false;
                    MessageBox.Show("Update failed:\n" + e.Message, "Drop Portal");
                }
            });
        });
    }

    bool IsIgnored(PhoneItem it) { lock (ignored) return ignored.Contains(Key(it)); }

    static readonly string[] Risky = { ".exe", ".msi", ".bat", ".cmd", ".com", ".scr", ".ps1", ".vbs", ".vbe", ".js", ".jse", ".wsf", ".hta", ".lnk", ".jar", ".reg", ".cpl", ".pif" };

    void OpenItem(PhoneItem it) {
        if (it.Text != null) {
            try { Clipboard.SetText(it.Text); SetStatus("Text copied ✓"); } catch { SetStatus("Could not copy text"); }
            return;
        }
        string key = phone + "/" + Key(it), have;
        if (fetched.TryGetValue(key, out have) && File.Exists(have)) { Launch(have); return; }
        if (downloading) return;
        downloading = true;
        string target = phone;
        ThreadPool.QueueUserWorkItem(delegate {
            string path = null, why = null;
            try { path = Download(target, it); }
            catch (Exception e) { why = Reason(e); }
            BeginInvoke((MethodInvoker)delegate {
                downloading = false;
                if (path == null) { SetStatus("Could not get " + it.Name + "\n" + why); return; }
                fetched[key] = path;
                SetStatus("Saved to " + Path.GetFileName(Path.GetDirectoryName(path)));
                Launch(path);
            });
        });
    }

    string Download(string target, PhoneItem it) {
        string dir = SaveDir();
        Directory.CreateDirectory(dir);
        string name = it.Name ?? "file";
        foreach (char c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
        string path = Path.Combine(dir, name);
        for (int n = 2; File.Exists(path); n++)
            path = Path.Combine(dir, Path.GetFileNameWithoutExtension(name) + " (" + n + ")" + Path.GetExtension(name));
        string part = path + ".part";
        var r = Request(target, "/f/" + it.Id, "GET");
        r.Timeout = 15000;
        try {
            using (var resp = (HttpWebResponse)r.GetResponse())
            using (var src = resp.GetResponseStream())
            using (var dst = File.Create(part)) {
                long total = it.Size > 0 ? it.Size : resp.ContentLength, done = 0;
                byte[] buf = new byte[1 << 16];
                int k, lastPct = -1;
                while ((k = src.Read(buf, 0, buf.Length)) > 0) {
                    dst.Write(buf, 0, k);
                    done += k;
                    if (total > 0) {
                        int pct = (int)(done * 100 / total);
                        if (pct != lastPct) { lastPct = pct; string s = "Receiving " + pct + "%"; BeginInvoke((MethodInvoker)delegate { SetStatus(s); }); }
                    }
                }
            }
            File.Move(part, path);
        } catch { try { File.Delete(part); } catch { } throw; }
        return path;
    }

    // Open with the default app; for program-like files only show them in Explorer.
    static void Launch(string path) {
        try {
            if (Array.IndexOf(Risky, Path.GetExtension(path).ToLowerInvariant()) >= 0)
                Process.Start("explorer.exe", "/select,\"" + path + "\"");
            else Process.Start(path);
        } catch { try { Process.Start("explorer.exe", "/select,\"" + path + "\""); } catch { } }
    }

    static uint ToUint(byte[] b) { return (uint)b[0] << 24 | (uint)b[1] << 16 | (uint)b[2] << 8 | b[3]; }
    static IPAddress FromUint(uint v) { return new IPAddress(new[] { (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v }); }

    void Listen() {
        try {
            udp.BeginReceive(delegate(IAsyncResult ar) {
                try {
                    var ep = new IPEndPoint(IPAddress.Any, 0);
                    string msg = Encoding.UTF8.GetString(udp.EndReceive(ar, ref ep));
                    int port;
                    if (msg.StartsWith("DROP!") && int.TryParse(msg.Substring(5), out port)) {
                        var ip = ep.Address;
                        BeginInvoke((MethodInvoker)delegate { Found(ip, port); });
                    }
                } catch { }
                Listen();
            }, null);
        } catch { }
    }

    void Found(IPAddress ip, int port) {
        lastSeen = DateTime.UtcNow;
        string addr = ip + ":" + port;
        if (addr == phone) return;
        phone = addr; approved = false;
        if (!ip.Equals(lastIp)) { lastIp = ip; try { Registry.CurrentUser.CreateSubKey(AppKey).SetValue("lastIp", ip.ToString()); } catch { } }
        tray.Text = "Drop: connected to " + ip;
        SetStatus("Connecting…");
        Place();
        allowShow = true;
        Show();
        CheckApproval();
    }

    // The phone asks its owner to allow each new computer once. Poll until that has happened.
    void CheckApproval() {
        string target = phone;
        checking = true;
        ThreadPool.QueueUserWorkItem(delegate {
            int code = 0;
            try {
                var r = Request(target, "/list", "GET");
                using (var resp = (HttpWebResponse)r.GetResponse()) code = (int)resp.StatusCode;
            } catch (WebException e) { var resp = e.Response as HttpWebResponse; if (resp != null) code = (int)resp.StatusCode; }
            catch { }
            BeginInvoke((MethodInvoker)delegate {
                checking = false;
                if (target != phone) return;
                if (code == 200) { approved = true; if (!busy) SetStatus("Ready"); Pump(); }
                else if (code == 403) SetStatus("Tap Allow on your phone");
            });
        });
    }

    // ---- sending ----
    void AddPath(string path) {
        try {
            if (Directory.Exists(path)) { foreach (var f in Directory.GetFiles(path, "*", SearchOption.AllDirectories)) queue.Enqueue(f); }
            else if (File.Exists(path)) queue.Enqueue(path);
        } catch { }
    }

    void Pump() {
        if (busy || phone == null || !approved || queue.Count == 0) return;
        busy = true;
        string target = phone;
        var files = new List<string>(queue);
        queue.Clear();
        ThreadPool.QueueUserWorkItem(delegate {
            int ok = 0;
            string why = null;
            for (int i = 0; i < files.Count; i++) {
                string prefix = files.Count > 1 ? (i + 1) + "/" + files.Count + "  " : "";
                try { Upload(target, files[i], prefix); ok++; }
                catch (Exception e) { if (why == null) why = Path.GetFileName(files[i]) + ": " + Reason(e); }
            }
            int sent = ok;
            BeginInvoke((MethodInvoker)delegate {
                busy = false;
                lastSeen = DateTime.UtcNow;
                if (sent == files.Count) SetStatus("Sent ✓");
                else {
                    SetStatus((sent == 0 ? "Not sent" : "Sent " + sent + " of " + files.Count) + "\n" + why);
                    try { tray.ShowBalloonTip(6000, "Drop: could not send", why, ToolTipIcon.Warning); } catch { }
                }
                Pump();
            });
        });
    }

    void Upload(string target, string file, string prefix) {
        var info = new FileInfo(file);
        var r = Request(target, "/up?name=" + Uri.EscapeDataString(info.Name), "PUT");
        r.ContentLength = info.Length;
        r.AllowWriteStreamBuffering = false;     // stream big files instead of loading them into memory
        r.Timeout = Timeout.Infinite;
        // Share read/write/delete so files that are open in Word, Excel etc. can still be read.
        using (var src = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
        using (var dst = r.GetRequestStream()) {
            byte[] buf = new byte[1 << 16];
            long done = 0; int n, lastPct = -1;
            while ((n = src.Read(buf, 0, buf.Length)) > 0) {
                dst.Write(buf, 0, n);
                done += n;
                int pct = info.Length == 0 ? 100 : (int)(done * 100 / info.Length);
                if (pct != lastPct) { lastPct = pct; string s = prefix + pct + "%"; BeginInvoke((MethodInvoker)delegate { SetStatus(s); }); }
            }
        }
        using (var resp = (HttpWebResponse)r.GetResponse()) { if (resp.StatusCode != HttpStatusCode.OK) throw new IOException(); }
    }

    static string Reason(Exception e) {
        if (e is UnauthorizedAccessException) return "no permission to read it";
        if (e is FileNotFoundException) return "file not found";
        var we = e as WebException;
        if (we != null) {
            var resp = we.Response as HttpWebResponse;
            if (resp != null) return "phone said " + (int)resp.StatusCode;
            return "phone did not answer";
        }
        if (e is IOException) return "could not read the file";
        return e.GetType().Name;
    }

    void SendText(string text) {
        string target = phone;
        if (target == null) return;
        SetStatus("Sending text…");
        ThreadPool.QueueUserWorkItem(delegate {
            bool ok = false;
            try {
                var r = Request(target, "/text", "POST");
                byte[] b = Encoding.UTF8.GetBytes(text);
                r.ContentLength = b.Length;
                using (var s = r.GetRequestStream()) s.Write(b, 0, b.Length);
                using (var resp = (HttpWebResponse)r.GetResponse()) ok = resp.StatusCode == HttpStatusCode.OK;
            } catch { }
            BeginInvoke((MethodInvoker)delegate { SetStatus(ok ? "Text sent ✓" : "Could not send text"); });
        });
    }

    static HttpWebRequest Request(string target, string path, string method) {
        var r = (HttpWebRequest)WebRequest.Create("http://" + target + path);
        r.Method = method;
        r.Proxy = null;                          // always talk to the phone directly
        r.KeepAlive = false;
        r.ServicePoint.Expect100Continue = false;
        r.ReadWriteTimeout = 30000;
        r.Timeout = 8000;
        if (method != "GET") r.Headers["X-Drop"] = "1";
        return r;
    }

    // ---- window behaviour ----
    protected override void OnDragEnter(DragEventArgs e) {
        bool can = e.Data.GetDataPresent(DataFormats.FileDrop) || e.Data.GetDataPresent(DataFormats.UnicodeText);
        e.Effect = can ? DragDropEffects.Copy : DragDropEffects.None;
        hot = can; Invalidate();
    }
    protected override void OnDragLeave(EventArgs e) { hot = false; Invalidate(); }
    protected override void OnDragDrop(DragEventArgs e) {
        hot = false; Invalidate();
        if (e.Data.GetDataPresent(DataFormats.FileDrop)) {
            foreach (var p in (string[])e.Data.GetData(DataFormats.FileDrop)) AddPath(p);
            if (!approved) SetStatus("Tap Allow on your phone");
            Pump();
        } else if (e.Data.GetDataPresent(DataFormats.UnicodeText)) {
            SendText((string)e.Data.GetData(DataFormats.UnicodeText));
        }
    }
    protected override void OnMouseClick(MouseEventArgs e) {
        int row = RowAt(e.Location);
        if (e.Button == MouseButtons.Right) {
            if (row >= 0 && phone != null) RowMenu(items[row], e.Location);
            else trayMenu.Show(this, e.Location);
            return;
        }
        if (e.Button != MouseButtons.Left || phone == null) return;
        if (row >= 0) { OpenItem(items[row]); return; }
        if (e.Y < BaseH) { try { Process.Start("http://" + phone + "/"); } catch { } }
    }
    protected override void OnMouseMove(MouseEventArgs e) {
        int row = RowAt(e.Location);
        if (row != hoverRow) { hoverRow = row; Invalidate(); }
    }
    protected override void OnMouseLeave(EventArgs e) { if (hoverRow != -1) { hoverRow = -1; Invalidate(); } }

    void SetStatus(string s) { status = s; Invalidate(); }

    protected override void OnPaint(PaintEventArgs e) {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
        g.Clear(hot ? Color.FromArgb(31, 111, 235) : Color.FromArgb(24, 27, 33));
        using (var pen = new Pen(hot ? Color.White : Color.FromArgb(31, 111, 235), 2) { DashStyle = DashStyle.Dash })
        using (var path = Rounded(new Rectangle(10, 10, Width - 21, BaseH - 21), 12)) g.DrawPath(pen, path);
        using (var pen = new Pen(Color.White, 4) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round }) {
            int cx = Width / 2;
            g.DrawLine(pen, cx, 42, cx, 80);
            g.DrawLines(pen, new[] { new Point(cx - 14, 66), new Point(cx, 80), new Point(cx + 14, 66) });
            g.DrawLine(pen, cx - 22, 94, cx + 22, 94);
        }
        var center = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        using (var big = new Font("Segoe UI", 12, FontStyle.Bold))
        using (var small = new Font("Segoe UI", 9))
        using (var dim = new SolidBrush(Color.FromArgb(170, 255, 255, 255))) {
            g.DrawString("Drop to phone", big, Brushes.White, new RectangleF(0, 108, Width, 28), center);
            g.DrawString(status, small, Brushes.White, new RectangleF(14, 138, Width - 28, 40), center);
            g.DrawString("Click to open full page", small, dim, new RectangleF(0, 186, Width, 22), center);

            int rows = Math.Min(items.Count, MaxRows);
            if (rows > 0) {
                var left = new StringFormat { LineAlignment = StringAlignment.Center, Trimming = StringTrimming.EllipsisCharacter, FormatFlags = StringFormatFlags.NoWrap };
                var right = new StringFormat { Alignment = StringAlignment.Far, LineAlignment = StringAlignment.Center };
                string more = items.Count > rows ? "  (+" + (items.Count - rows) + " more on page)" : "";
                g.DrawString("From phone" + more, small, dim, new RectangleF(14, BaseH, Width - 28, 20), left);
                for (int i = 0; i < rows; i++) {
                    var it = items[i];
                    var rc = new Rectangle(8, BaseH + 22 + i * RowH, Width - 16, RowH - 4);
                    using (var bg = new SolidBrush(i == hoverRow ? Color.FromArgb(31, 111, 235) : Color.FromArgb(38, 43, 52)))
                    using (var p = Rounded(rc, 8)) g.FillPath(bg, p);
                    string label = it.Text != null ? "“" + it.Text.Replace("\r", " ").Replace("\n", " ") + "”" : it.Name;
                    string action = it.Text != null ? "Copy" : "Open";
                    g.DrawString(label, small, Brushes.White, new RectangleF(rc.X + 8, rc.Y, rc.Width - 52, rc.Height), left);
                    g.DrawString(action, small, i == hoverRow ? Brushes.White : dim, new RectangleF(rc.X, rc.Y, rc.Width - 8, rc.Height), right);
                }
            }
        }
    }

    static GraphicsPath Rounded(Rectangle r, int d) {
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    static Icon MakeIcon() {
        try {
            var bmp = new Bitmap(32, 32);
            using (var g = Graphics.FromImage(bmp)) {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                using (var b = new SolidBrush(Color.FromArgb(31, 111, 235))) g.FillEllipse(b, 0, 0, 31, 31);
                using (var pen = new Pen(Color.White, 3)) {
                    g.DrawLine(pen, 16, 7, 16, 19);
                    g.DrawLines(pen, new[] { new Point(11, 15), new Point(16, 20), new Point(21, 15) });
                    g.DrawLine(pen, 10, 24, 22, 24);
                }
            }
            return Icon.FromHandle(bmp.GetHicon());
        } catch { return SystemIcons.Application; }
    }

    static bool GetStartup() {
        try { using (var k = Registry.CurrentUser.OpenSubKey(RunKey)) return k != null && k.GetValue("DropPortal") != null; } catch { return false; }
    }
    static void SetStartup(bool on) {
        try {
            using (var k = Registry.CurrentUser.CreateSubKey(RunKey)) {
                if (on) k.SetValue("DropPortal", "\"" + Application.ExecutablePath + "\"");
                else k.DeleteValue("DropPortal", false);
            }
        } catch { }
    }
}
