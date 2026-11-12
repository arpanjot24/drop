package app.drop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity implements Server.Host {
    private static Server server;
    private static final StringBuilder log = new StringBuilder();
    private static final Set<String> allowed = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> denied = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> asking = Collections.synchronizedSet(new HashSet<String>());

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView urlView, logView;
    private byte[] page;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int p = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView hint = new TextView(this);
        hint.setText("On your computer (same Wi‑Fi), open:");
        root.addView(hint);

        urlView = new TextView(this);
        urlView.setTextSize(26);
        urlView.setTextIsSelectable(true);
        urlView.setPadding(0, p / 3, 0, p);
        root.addView(urlView);

        root.addView(button("Send files", new View.OnClickListener() { public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, 1);
        } }));
        root.addView(button("Send clipboard", new View.OnClickListener() { public void onClick(View v) {
            ClipData c = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();
            CharSequence t = c != null && c.getItemCount() > 0 ? c.getItemAt(0).coerceToText(MainActivity.this) : null;
            if (t == null || t.length() == 0) toast("Clipboard is empty"); else shareText(t.toString());
        } }));
        root.addView(button("Clear", new View.OnClickListener() { public void onClick(View v) {
            server.clear(); log.setLength(0); logView.setText("");
        } }));

        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, p, 0, 0);
        logView.setText(log);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        root.addView(sv);
        setContentView(root);

        allowed.addAll(prefs().getStringSet("allowed", new HashSet<String>()));
        if (server == null) {
            Server s = new Server();
            s.host = this;
            try { s.start(8080); server = s; } catch (IOException e) { urlView.setText("Could not start: " + e.getMessage()); }
        } else server.host = this;
        handle(getIntent());
    }

    @Override protected void onResume() { super.onResume(); showUrl(); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && server != null) { server.stop(); server = null; }
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (res != RESULT_OK || d == null) return;
        if (d.getClipData() != null) {
            for (int i = 0; i < d.getClipData().getItemCount(); i++) shareFile(d.getClipData().getItemAt(i).getUri());
        } else if (d.getData() != null) shareFile(d.getData());
    }

    /** Things shared to Drop from other apps. */
    private void handle(Intent i) {
        if (i == null || i.getAction() == null || server == null) return;
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
        line("↑ " + it.name);
    }

    private void shareText(String t) {
        Server.Item it = new Server.Item();
        it.text = t;
        server.add(it);
        line("↑ " + t);
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

    public void onFile(final String name) {
        main.post(new Runnable() { public void run() { line("↓ " + name + "  (Downloads/Drop)"); } });
    }

    public void onText(final String text) {
        main.post(new Runnable() { public void run() {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Drop", text));
            line("↓ " + text + "  (copied)");
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

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void line(String s) {
        if (s.length() > 300) s = s.substring(0, 300) + "…";
        log.insert(0, s + "\n\n");
        logView.setText(log);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private SharedPreferences prefs() { return getSharedPreferences("drop", MODE_PRIVATE); }
}
