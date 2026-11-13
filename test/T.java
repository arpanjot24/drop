import app.drop.Server;
import java.io.*;
import java.nio.file.*;
public class T implements Server.Host {
    public byte[] page() { try { return Files.readAllBytes(Paths.get("assets/index.html")); } catch (IOException e) { return null; } }
    public String save(String name, InputStream in, long len) throws IOException {
        try (OutputStream o = new FileOutputStream("build/t/in/" + name)) { byte[] b = new byte[65536]; long l = len;
            while (l > 0) { int n = in.read(b, 0, (int) Math.min(b.length, l)); if (n < 0) throw new IOException(); o.write(b, 0, n); l -= n; } }
        return name; }
    public InputStream open(Server.Item it) throws IOException { return new FileInputStream(it.uri); }
    public int allowed(String ip) { return new File("build/t/allow").exists() ? 1 : 0; }
    public void onFile(String n) { System.out.println("FILE " + n); }
    public void onText(String t) { System.out.println("TEXT " + t); }
    public void onRemoved(int id) { System.out.println("REMOVED " + id); }
    public static void main(String[] a) throws Exception {
        Server s = new Server(); s.host = new T(); s.start(8080);
        Server.Item f = new Server.Item(); f.name = "from phone é.bin"; f.uri = "build/t/phone.bin"; f.size = new File(f.uri).length(); s.add(f);
        Server.Item t = new Server.Item(); t.text = "hello \"<b>\"\nline2"; s.add(t);
        System.out.println("PORT " + s.port);
    }
}
