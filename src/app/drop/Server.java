package app.drop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Tiny HTTP server. No Android imports, so it can be tested on a plain JVM. */
public class Server {
    public interface Host {
        byte[] page();
        /** Store exactly len bytes from in; returns the name it was saved as. */
        String save(String name, InputStream in, long len) throws IOException;
        InputStream open(Item it) throws IOException;
        /** 1 = allowed, 0 = waiting for approval on the phone, -1 = denied. */
        int allowed(String ip);
        void onFile(String name);
        void onText(String text);
    }

    public static class Item {
        public int id;
        public String name, uri, text;
        public long size = -1;
    }

    public volatile Host host;
    public int port;
    /** UDP port the PC companion probes to find the phone ("DROP?" -> "DROP!<http port>"). */
    public static final int DISCOVERY_PORT = 8079;
    private ServerSocket ss;
    private DatagramSocket ds;
    private final List<Item> items = new ArrayList<Item>();
    private int nextId = 1;

    public void start(int firstPort) throws IOException {
        IOException last = null;
        for (int p = firstPort; p < firstPort + 10; p++) {
            try { ss = new ServerSocket(p); port = p; break; } catch (IOException e) { last = e; }
        }
        if (ss == null) throw last;
        new Thread(new Runnable() { public void run() { acceptLoop(); } }, "drop-accept").start();
        try {
            ds = new DatagramSocket(DISCOVERY_PORT);
            new Thread(new Runnable() { public void run() { discoveryLoop(); } }, "drop-discovery").start();
        } catch (IOException e) { /* port taken: the browser page still works, only auto-discovery is off */ }
    }

    private void discoveryLoop() {
        byte[] buf = new byte[64];
        while (true) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ds.receive(p);
                if (!new String(p.getData(), 0, p.getLength(), "UTF-8").equals("DROP?")) continue;
                byte[] r = ("DROP!" + port).getBytes("UTF-8");
                ds.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
            } catch (IOException e) { if (ds.isClosed()) return; }
        }
    }

    public void stop() {
        try { ss.close(); } catch (Exception e) { }
        if (ds != null) ds.close();
    }

    public void add(Item it) { synchronized (items) { it.id = nextId++; items.add(0, it); } }
    public void clear() { synchronized (items) { items.clear(); } }

    private void acceptLoop() {
        while (true) {
            final Socket s;
            try { s = ss.accept(); } catch (IOException e) { return; }
            new Thread(new Runnable() { public void run() {
                try { handle(s); } catch (Exception e) { } finally { try { s.close(); } catch (Exception e) { } }
            } }).start();
        }
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(30000);
        InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
        OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);
        String head = readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] rl = lines[0].split(" ");
        if (rl.length < 2) return;
        String method = rl[0], target = rl[1], query = "";
        int q = target.indexOf('?');
        if (q >= 0) { query = target.substring(q + 1); target = target.substring(0, q); }
        long len = 0;
        boolean marked = false;
        String hostHdr = "";
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c < 0) continue;
            String k = lines[i].substring(0, c).trim().toLowerCase(), v = lines[i].substring(c + 1).trim();
            if (k.equals("content-length")) len = Long.parseLong(v);
            else if (k.equals("x-drop")) marked = true;
            else if (k.equals("host")) hostHdr = v;
        }
        // Only answer when addressed by IP: blocks DNS-rebinding from web pages.
        if (hostHdr.isEmpty() || !(Character.isDigit(hostHdr.charAt(0)) || hostHdr.charAt(0) == '[')) {
            send(out, "403 Forbidden", "text/plain", "forbidden".getBytes("UTF-8")); return;
        }
        int ok = host.allowed(s.getInetAddress().getHostAddress());
        if (ok != 1) {
            String body = ok == 0
                ? "<meta http-equiv=refresh content=2><meta name=viewport content='width=device-width'>"
                  + "<body style='font:18px system-ui;padding:2em'>Tap <b>Allow</b> on your phone…"
                : "Denied on the phone. Restart Drop to try again.";
            send(out, "403 Forbidden", "text/html; charset=utf-8", body.getBytes("UTF-8")); return;
        }
        boolean write = method.equals("PUT") || method.equals("POST");
        if (write && !marked) { send(out, "403 Forbidden", "text/plain", "forbidden".getBytes("UTF-8")); return; }

        if (method.equals("GET") && target.equals("/")) {
            send(out, "200 OK", "text/html; charset=utf-8", host.page());
        } else if (method.equals("GET") && target.equals("/list")) {
            send(out, "200 OK", "application/json", listJson().getBytes("UTF-8"));
        } else if (method.equals("GET") && target.startsWith("/f/")) {
            Item it = find(target.substring(3));
            if (it == null || it.uri == null) { send(out, "404 Not Found", "text/plain", "gone".getBytes("UTF-8")); return; }
            InputStream f = host.open(it);
            try {
                StringBuilder h = new StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n");
                h.append("Content-Disposition: attachment; filename=\"").append(it.name.replaceAll("[^\\x20-\\x7e]|[\"\\\\]", "_"))
                    .append("\"; filename*=UTF-8''").append(enc(it.name)).append("\r\n");
                if (it.size >= 0) h.append("Content-Length: ").append(it.size).append("\r\n");
                h.append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes("UTF-8"));
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = f.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally { f.close(); }
        } else if (method.equals("PUT") && target.equals("/up")) {
            String name = "file";
            for (String kv : query.split("&")) if (kv.startsWith("name=")) name = URLDecoder.decode(kv.substring(5), "UTF-8");
            name = name.replace('/', '_').replace('\\', '_');
            if (name.isEmpty() || name.startsWith(".")) name = "file" + name;
            String saved = host.save(name, in, len);
            host.onFile(saved);
            send(out, "200 OK", "text/plain; charset=utf-8", saved.getBytes("UTF-8"));
        } else if (method.equals("POST") && target.equals("/text")) {
            if (len > (1 << 20)) { send(out, "413 Too Large", "text/plain", "too long".getBytes("UTF-8")); return; }
            byte[] b = new byte[(int) len];
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) return; off += n; }
            host.onText(new String(b, "UTF-8"));
            send(out, "200 OK", "text/plain", "ok".getBytes("UTF-8"));
        } else {
            send(out, "404 Not Found", "text/plain", "not found".getBytes("UTF-8"));
        }
    }

    private Item find(String id) {
        synchronized (items) { for (Item it : items) if (String.valueOf(it.id).equals(id)) return it; }
        return null;
    }

    private String listJson() {
        StringBuilder sb = new StringBuilder("[");
        synchronized (items) {
            for (Item it : items) {
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"id\":").append(it.id);
                if (it.text != null) sb.append(",\"text\":").append(js(it.text));
                else sb.append(",\"name\":").append(js(it.name)).append(",\"size\":").append(it.size);
                sb.append('}');
            }
        }
        return sb.append(']').toString();
    }

    private static String js(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20 || c == '<' || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, c;
        while (state < 4 && (c = in.read()) >= 0) {
            b.write(c);
            if (c == (state % 2 == 0 ? '\r' : '\n')) state++; else state = c == '\r' ? 1 : 0;
            if (b.size() > 16384) return null;
        }
        return state == 4 ? b.toString("ISO-8859-1") : null;
    }

    private static void send(OutputStream out, String status, String type, byte[] body) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }
}
