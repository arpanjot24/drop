// Drop Portal: Windows companion for the Drop Android app.
// Sits in the tray, probes the local network for a phone running Drop, and while one is found
// shows a small always-on-top drop target on the right edge of the screen.
// Build: csc /target:winexe /r:System.Windows.Forms.dll /r:System.Drawing.dll DropPortal.cs   (or mcs with the same flags)
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
        bool first;
        using (new Mutex(true, "DropPortal-single-instance", out first)) {
            if (!first) return;
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

    public Portal(string[] args) {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        Size = new Size(190, 230);
        DoubleBuffered = true;
        AllowDrop = true;
        Cursor = Cursors.Hand;
        using (var path = Rounded(new Rectangle(0, 0, Width, Height), 18)) Region = new Region(path);
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
        menu.Items.Add("Exit", null, delegate { tray.Visible = false; Application.Exit(); });
        tray.Icon = MakeIcon();
        tray.Text = "Drop: waiting for phone";
        tray.ContextMenuStrip = menu;
        tray.Visible = true;
        ContextMenuStrip = menu;
        try { tray.ShowBalloonTip(4000, "Drop is running", "The portal appears when you open Drop on your phone.", ToolTipIcon.Info); } catch { }

        try { string s = (string)Registry.CurrentUser.CreateSubKey(AppKey).GetValue("lastIp"); if (s != null) lastIp = IPAddress.Parse(s); } catch { }
        udp.EnableBroadcast = true;
        try { udp.Client.IOControl(-1744830452, new byte[] { 0 }, null); } catch { }  // SIO_UDP_CONNRESET off: ignore ICMP "port closed"
        Listen();
        timer.Interval = 2000;
        timer.Tick += delegate { Tick(); };
        timer.Start();
        Tick();
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
                    for (int i = 0; i < 4; i++) ip[i] |= (byte)~mask[i];
                    targets.Add(new IPAddress(ip));          // directed broadcast for this network
                }
            }
        } catch { }
        foreach (var t in targets) { try { udp.Send(probe, probe.Length, new IPEndPoint(t, DiscoveryPort)); } catch { } }

        if (phone != null && !busy && (DateTime.UtcNow - lastSeen).TotalSeconds > 7) {
            phone = null; approved = false;
            tray.Text = "Drop: waiting for phone";
            Hide();
        }
        if (phone != null && !approved && !checking) CheckApproval();
    }

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
        var wa = Screen.PrimaryScreen.WorkingArea;
        Location = new Point(wa.Right - Width - 12, wa.Top + (wa.Height - Height) / 2);
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
            for (int i = 0; i < files.Count; i++) {
                string prefix = files.Count > 1 ? (i + 1) + "/" + files.Count + "  " : "";
                try { Upload(target, files[i], prefix); ok++; } catch { }
            }
            int sent = ok;
            BeginInvoke((MethodInvoker)delegate {
                busy = false;
                lastSeen = DateTime.UtcNow;
                SetStatus(sent == files.Count ? "Sent ✓" : "Sent " + sent + " of " + files.Count + ", " + (files.Count - sent) + " failed");
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
        using (var src = info.OpenRead())
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
        if (e.Button == MouseButtons.Left && phone != null) { try { Process.Start("http://" + phone + "/"); } catch { } }
    }

    void SetStatus(string s) { status = s; Invalidate(); }

    protected override void OnPaint(PaintEventArgs e) {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
        g.Clear(hot ? Color.FromArgb(31, 111, 235) : Color.FromArgb(24, 27, 33));
        using (var pen = new Pen(hot ? Color.White : Color.FromArgb(31, 111, 235), 2) { DashStyle = DashStyle.Dash })
        using (var path = Rounded(new Rectangle(10, 10, Width - 21, Height - 21), 12)) g.DrawPath(pen, path);
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
