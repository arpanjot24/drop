package app.drop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.pm.PackageInstaller;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLConnection;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements Server.Host {
    private static Server server;
    /** One row in the activity list. Received rows (in=true) survive restarts. */
    static class Entry { boolean in; String label, uri, mime, text; int sid; }  // sid: server item id of a shared row
    private static final ArrayList<Entry> entries = new ArrayList<Entry>();
    private static boolean loaded;
    private static long lastUpdateCheck;
    private static final Set<String> allowed = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> denied = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> asking = Collections.synchronizedSet(new HashSet<String>());

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView urlView;
    private LinearLayout listView;
    private int pad;
    private byte[] page;
    private WifiManager.MulticastLock lock;  // lets the PC companion's broadcast probe reach us

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int p = pad = dp(20);
        getWindow().setStatusBarColor(col(BG));
        getWindow().setNavigationBarColor(col(BG));
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = column();
        root.setPadding(p, p, p, p);

        // Header: app name and version
        LinearLayout head = row();
        TextView title = text("Drop", 30, col(FG), true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        String ver = "";
        try { ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { }
        head.addView(text(ver, 13, col(MUTED), false));
        root.addView(head);

        // Connection card: the address to open on the computer
        LinearLayout conn = column();
        conn.setBackground(ripple(rounded(ACCENT, 0, 20)));
        conn.setPadding(p, dp(16), p, dp(16));
        conn.addView(text("On your computer, open", 14, 0xD9FFFFFF, false));
        urlView = text("", 28, Color.WHITE, true);
        urlView.setPadding(0, dp(4), 0, dp(6));
        conn.addView(urlView);
        conn.addView(text("Same Wi‑Fi · tap to copy · new computers must be allowed", 12, 0xB3FFFFFF, false));
        conn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (urlView.getText().toString().indexOf(':') < 0) return;   // "Not connected to Wi‑Fi"
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Drop", "http://" + urlView.getText()));
            toast("Address copied");
        } });
        root.addView(conn, margins(-1, 16));

        // Main actions
        LinearLayout btns = row();
        btns.addView(pill("Send files", true, new View.OnClickListener() { public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, 1);
        } }), weighted(0));
        btns.addView(pill("Send clipboard", false, new View.OnClickListener() { public void onClick(View v) {
            ClipData c = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();
            CharSequence t = c != null && c.getItemCount() > 0 ? c.getItemAt(0).coerceToText(MainActivity.this) : null;
            if (t == null || t.length() == 0) toast("Clipboard is empty"); else shareText(t.toString());
        } }), weighted(12));
        root.addView(btns, margins(-1, 16));

        // Activity list
        LinearLayout sec = row();
        sec.addView(text("Activity", 18, col(FG), true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = text("Clear list", 14, ACCENT, true);
        clear.setPadding(dp(10), dp(6), dp(4), dp(6));
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (entries.isEmpty()) return;
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Clear the list?")
                .setMessage("Received files stay on the phone in Downloads/Drop. Shared items stop being offered to computers.")
                .setPositiveButton("Clear", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    if (server != null) server.clear();
                    entries.clear(); persist(); render();
                } })
                .setNegativeButton("Cancel", null)
                .show();
        } });
        sec.addView(clear);
        root.addView(sec, margins(-1, 28));

        listView = column();
        root.addView(listView);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(col(BG));
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);
        load();
        render();

        allowed.addAll(prefs().getStringSet("allowed", new HashSet<String>()));
        if (server == null) {
            Server s = new Server();
            s.host = this;
            try { s.start(8080); server = s; } catch (IOException e) { urlView.setText("Could not start: " + e.getMessage()); }
        } else server.host = this;
        handle(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        showUrl();
        // Check for a new release on launch and whenever Drop comes back to the front (at most every 15 minutes).
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheck > 15 * 60 * 1000L) { lastUpdateCheck = now; Updater.check(this); }
        try {
            if (lock == null) lock = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).createMulticastLock("drop");
            lock.acquire();
        } catch (Exception e) { }
    }

    @Override protected void onPause() {
        super.onPause();
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception e) { }
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    // The manifest handles uiMode changes itself, so rebuild the screen when light/dark mode flips.
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        boolean nowDark = (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (nowDark != dark) recreate();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && server != null) { server.stop(); server = null; }
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == REQ_DELETE) {                 // answer to Android's "allow Drop to delete this file?" prompt
            Entry e = pendingDelete;
            pendingDelete = null;
            if (res == RESULT_OK && e != null) removeEntry(e);
            return;
        }
        if (res != RESULT_OK || d == null) return;
        if (d.getClipData() != null) {
            for (int i = 0; i < d.getClipData().getItemCount(); i++) shareFile(d.getClipData().getItemAt(i).getUri());
        } else if (d.getData() != null) shareFile(d.getData());
    }

    /** Things shared to Drop from other apps. */
    private void handle(Intent i) {
        if (i == null || i.getAction() == null) return;
        if (Updater.ACTION.equals(i.getAction())) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION && confirm != null) startActivity(confirm);
            else if (st != PackageInstaller.STATUS_SUCCESS) toast("Update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            i.setAction(null);
            return;
        }
        if (server == null) return;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) shareFile(u);
            else if (i.getStringExtra(Intent.EXTRA_TEXT) != null) shareText(i.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> us = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) for (Uri u : us) shareFile(u);
        }
        i.setAction(null);
    }

    private void shareFile(Uri u) {
        Server.Item it = new Server.Item();
        it.uri = u.toString();
        it.name = u.getLastPathSegment();
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && c.getString(n) != null) it.name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) it.size = c.getLong(s);
            }
        } catch (Exception e) { }
        if (it.name == null) it.name = "file";
        server.add(it);
        add(false, "↑ " + it.name, null, null, null).sid = it.id;
    }

    private void shareText(String t) {
        Server.Item it = new Server.Item();
        it.text = t;
        server.add(it);
        add(false, "↑ " + t, null, null, null).sid = it.id;
    }

    // ---- Server.Host (called on server threads) ----

    public byte[] page() {
        if (page == null) {
            try (InputStream in = getAssets().open("index.html")) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                page = o.toByteArray();
            } catch (IOException e) { return new byte[0]; }
        }
        return page;
    }

    /** Saves into Downloads/Drop. MediaStore needs no permission and de-duplicates names. */
    public String save(String name, InputStream in, long len) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        String mime = URLConnection.guessContentTypeFromName(name);
        if (mime != null) v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Drop");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("cannot create file");
        try (OutputStream out = cr.openOutputStream(u)) {
            byte[] buf = new byte[1 << 16];
            long left = len;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("upload interrupted");
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            cr.delete(u, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, v, null, null);
        final String uri = u.toString(), type = cr.getType(u), shown = name;
        main.post(new Runnable() { public void run() { add(true, shown, uri, type == null ? "*/*" : type, null); } });
        return name;
    }

    public InputStream open(Server.Item it) throws IOException {
        InputStream in = getContentResolver().openInputStream(Uri.parse(it.uri));
        if (in == null) throw new IOException("cannot open");
        return in;
    }

    public int allowed(final String ip) {
        if (allowed.contains(ip)) return 1;
        if (denied.contains(ip)) return -1;
        if (asking.add(ip)) main.post(new Runnable() { public void run() { ask(ip); } });
        return 0;
    }

    public void onFile(String name) { }  // the row is added in save(), where the file's Uri is known

    /** A computer deleted a shared item: drop its row. The original file on the phone is left alone. */
    public void onRemoved(final int id) {
        main.post(new Runnable() { public void run() {
            for (int i = entries.size() - 1; i >= 0; i--) if (!entries.get(i).in && entries.get(i).sid == id) entries.remove(i);
            render();
        } });
    }

    public void onText(final String text) {
        main.post(new Runnable() { public void run() {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Drop", text));
            add(true, text, null, null, text);
        } });
    }

    // ---- helpers ----

    private void ask(final String ip) {
        if (isFinishing()) { asking.remove(ip); return; }
        new AlertDialog.Builder(this)
            .setTitle("Allow " + ip + "?")
            .setMessage("This device wants to send and receive files. It will be remembered.")
            .setCancelable(false)
            .setPositiveButton("Allow", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                allowed.add(ip); asking.remove(ip);
                prefs().edit().putStringSet("allowed", new HashSet<String>(allowed)).apply();
            } })
            .setNegativeButton("Deny", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                denied.add(ip); asking.remove(ip);
            } })
            .show();
    }

    private void showUrl() {
        if (server == null) return;
        String ip = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (ip == null || ni.getName().startsWith("wlan")) ip = a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) { }
        urlView.setText(ip == null ? "Not connected to Wi‑Fi" : ip + ":" + server.port);
    }

    // ---- look: GitHub-like palette, light and dark ----
    private boolean dark;
    private static final int ACCENT = 0xFF1F6FEB;
    private static final int BG = 0, CARD = 1, BORDER = 2, FG = 3, MUTED = 4, GREEN = 5, RED = 6;
    private static final int[] LIGHT = { 0xFFF6F8FA, 0xFFFFFFFF, 0xFFD0D7DE, 0xFF1F2328, 0xFF656D76, 0xFF1A7F37, 0xFFCF222E };
    private static final int[] DARK  = { 0xFF0D1117, 0xFF161B22, 0xFF30363D, 0xFFE6EDF3, 0xFF8B949E, 0xFF3FB950, 0xFFF85149 };

    private int col(int which) { return (dark ? DARK : LIGHT)[which]; }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private RippleDrawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33808080), content, null);
    }

    private LinearLayout.LayoutParams margins(int width, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -2);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams weighted(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
        lp.leftMargin = dp(leftDp);
        return lp;
    }

    /** Big rounded button: filled (accent) or tonal (card with border). */
    private TextView pill(String s, boolean filled, View.OnClickListener l) {
        TextView b = text(s, 16, filled ? Color.WHITE : col(FG), true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(filled ? rounded(ACCENT, 0, 26) : rounded(col(CARD), col(BORDER), 26)));
        b.setOnClickListener(l);
        return b;
    }

    /** Small outlined action under an item. */
    private TextView chip(String s, int color, View.OnClickListener l) {
        TextView c = text(s, 14, color, true);
        c.setPadding(dp(14), dp(7), dp(14), dp(7));
        c.setBackground(ripple(rounded(Color.TRANSPARENT, col(BORDER), 18)));
        c.setOnClickListener(l);
        return c;
    }

    private Entry add(boolean in, String label, String uri, String mime, String text) {
        Entry e = new Entry();
        e.in = in; e.label = label.length() > 300 ? label.substring(0, 300) + "…" : label;
        e.uri = uri; e.mime = mime; e.text = text;
        entries.add(0, e);
        while (entries.size() > 50) entries.remove(entries.size() - 1);
        if (in) persist();
        render();
        return e;
    }

    /** Received files and text get Open / Share / Copy right here, so there is no trip to the file manager. */
    private void render() {
        listView.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = text("Nothing here yet.\nFiles and text you send or receive show up here.", 14, col(MUTED), false);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(dp(3), 1);
            empty.setPadding(pad, dp(28), pad, dp(28));
            empty.setBackground(rounded(col(CARD), col(BORDER), 16));
            listView.addView(empty, margins(-1, 12));
            return;
        }
        for (Entry e : entries) listView.addView(card(e), margins(-1, 10));
    }

    /** One item: icon, name, what it is, and its actions. */
    private View card(final Entry e) {
        LinearLayout card = column();
        card.setBackground(rounded(col(CARD), col(BORDER), 16));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout top = row();
        TextView icon = text(e.in ? "↓" : "↑", 18, Color.WHITE, true);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(e.in ? col(GREEN) : ACCENT);
        icon.setBackground(dot);
        top.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout texts = column();
        String label = !e.in && e.label.startsWith("↑ ") ? e.label.substring(2) : e.label;
        boolean isText = e.in && e.uri == null;
        TextView name = text(isText ? "“" + label + "”" : label, 16, col(FG), true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        String what = e.in ? (e.uri != null ? "Received · saved in Downloads/Drop" : "Received text · copied to clipboard")
                           : "Shared · computers can download it";
        texts.addView(text(what, 12, col(MUTED), false));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = dp(12);
        top.addView(texts, tlp);
        card.addView(top);

        LinearLayout acts = row();
        if (e.in) {
            if (e.uri != null) {
                acts.addView(chip("Open", ACCENT, new View.OnClickListener() { public void onClick(View v) {
                    launch(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.uri), e.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } }));
            } else {
                acts.addView(chip("Copy", ACCENT, new View.OnClickListener() { public void onClick(View v) {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Drop", e.text));
                    toast("Copied");
                } }));
            }
            acts.addView(chip("Share", col(FG), new View.OnClickListener() { public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_SEND);
                if (e.uri != null) {
                    Uri u = Uri.parse(e.uri);
                    i.setType(e.mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setClipData(ClipData.newRawUri(e.label, u));
                } else i.setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.text);
                launch(Intent.createChooser(i, null));
            } }), gap());
            acts.addView(chip("Delete", col(RED), new View.OnClickListener() { public void onClick(View v) { confirmDelete(e); } }), gap());
        } else if (e.sid > 0) {
            acts.addView(chip("Stop sharing", col(MUTED), new View.OnClickListener() { public void onClick(View v) {
                if (server != null) server.remove(String.valueOf(e.sid));
                entries.remove(e);
                render();
            } }));
        }
        if (acts.getChildCount() > 0) {
            LinearLayout.LayoutParams alp = margins(-2, 12);
            alp.leftMargin = dp(50);
            card.addView(acts, alp);
        }
        return card;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        return lp;
    }

    private static final int REQ_DELETE = 2;
    private Entry pendingDelete;

    private void confirmDelete(final Entry e) {
        boolean file = e.uri != null;
        new AlertDialog.Builder(this)
            .setTitle(file ? "Delete file?" : "Remove text?")
            .setMessage(file ? "\"" + e.label + "\" will be deleted from this phone (Downloads/Drop)."
                             : "It will be removed from this list.")
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { delete(e); } })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Deletes a received file from Downloads/Drop and drops its row. */
    private void delete(Entry e) {
        if (e.uri != null) {
            Uri u = Uri.parse(e.uri);
            try {
                getContentResolver().delete(u, null, null);  // 0 rows = already gone, which is fine too
            } catch (SecurityException ex) {
                // Files saved before a reinstall belong to the old copy of the app: Android 11+ can ask the user instead.
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        pendingDelete = e;
                        startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(u)).getIntentSender(),
                            REQ_DELETE, null, 0, 0, 0);
                        return;
                    } catch (Exception x) { pendingDelete = null; }
                }
                toast("Could not delete the file");
                return;
            }
        }
        removeEntry(e);
    }

    private void removeEntry(Entry e) {
        entries.remove(e);
        persist();
        render();
        toast(e.uri != null ? "Deleted" : "Removed");
    }

    private void launch(Intent i) {
        try { startActivity(i); }
        catch (ActivityNotFoundException e) { toast("No app can open this"); }
        catch (SecurityException e) { toast("File is no longer available"); }
    }

    private void persist() {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) if (e.in)
                a.put(new JSONObject().put("label", e.label).put("uri", e.uri).put("mime", e.mime).put("text", e.text));
        } catch (Exception ex) { }
        prefs().edit().putString("received", a.toString()).apply();
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            JSONArray a = new JSONArray(prefs().getString("received", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.in = true; e.label = o.getString("label");
                e.uri = o.optString("uri", null); e.mime = o.optString("mime", null); e.text = o.optString("text", null);
                entries.add(e);
            }
        } catch (Exception ex) { }
    }

    /** Called by Updater on the main thread once a newer APK has been downloaded. */
    void offerUpdate(final int version, final File apk) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
            .setTitle("Update ready")
            .setMessage("Version " + version + " has been downloaded. Drop will close while it installs.")
            .setPositiveButton("Install", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                try { Updater.install(MainActivity.this, apk); } catch (Exception e) { toast("Update failed: " + e.getMessage()); }
            } })
            .setNegativeButton("Later", null)
            .show();
    }

    void onMain(Runnable r) { main.post(r); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private SharedPreferences prefs() { return getSharedPreferences("drop", MODE_PRIVATE); }
}
