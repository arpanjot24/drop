package app.drop;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from GitHub Releases. The repo ("owner/name") is baked into assets/repo.txt by CI;
 * releases are tagged v<versionCode> and carry Drop.apk. Android only accepts an APK signed with
 * the same key as the installed app, so a tampered download cannot install.
 */
class Updater {
    static final String ACTION = "app.drop.INSTALL";

    static void check(final MainActivity a) {
        new Thread(new Runnable() { public void run() {
            try {
                String repo;
                try (InputStream in = a.getAssets().open("repo.txt")) { repo = new String(read(in), "UTF-8").trim(); }
                if (!repo.matches("[\\w.-]+/[\\w.-]+")) return;
                long current = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).getLongVersionCode();

                // A release may carry only the Windows portal, so find the newest one that has an APK.
                JSONArray rels;
                try (InputStream in = get("https://api.github.com/repos/" + repo + "/releases?per_page=20")) {
                    rels = new JSONArray(new String(read(in), "UTF-8"));
                }
                int best = 0;
                String url = null;
                for (int r = 0; r < rels.length(); r++) {
                    JSONObject rel = rels.getJSONObject(r);
                    if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue;
                    int v;
                    try { v = Integer.parseInt(rel.getString("tag_name").replaceAll("\\D", "")); } catch (NumberFormatException e) { continue; }
                    if (v <= best) continue;
                    JSONArray assets = rel.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject as = assets.getJSONObject(i);
                        if (as.getString("name").endsWith(".apk")) { best = v; url = as.getString("browser_download_url"); }
                    }
                }
                final int latest = best;
                if (latest <= current) return;
                if (url == null || !url.startsWith("https://github.com/" + repo + "/")) return;

                final File apk = new File(a.getCacheDir(), "update-" + latest + ".apk");
                if (!apk.exists()) {
                    File tmp = new File(a.getCacheDir(), "update.tmp");
                    try (InputStream in = get(url); OutputStream out = new FileOutputStream(tmp)) { copy(in, out); }
                    if (!tmp.renameTo(apk)) return;
                }
                a.onMain(new Runnable() { public void run() { a.offerUpdate(latest, apk); } });
            } catch (Exception e) { /* offline, no release yet, rate-limited: try again next launch */ }
        } }, "drop-update").start();
    }

    static void install(MainActivity a, File apk) throws Exception {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(a.getPackageName());
        // Android 12+: after the first approved update, later ones install without the system prompt.
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("drop", 0, apk.length())) {
                copy(in, out);
                s.fsync(out);
            }
            Intent cb = new Intent(a, MainActivity.class).setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getActivity(a, 0, cb, flags).getIntentSender());
        }
    }

    private static InputStream get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Drop-Android");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        return c.getInputStream();
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        copy(in, o);
        return o.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
