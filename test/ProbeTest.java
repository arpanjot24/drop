import app.drop.Server;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

/** Self-check for phone-to-phone discovery. Run: java -ea -cp build/classes:build/t ProbeTest */
public class ProbeTest {
    public static void main(String[] a) throws Exception {
        Server s = new Server();
        s.host = new T();
        s.start(8080);
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket d = new DatagramSocket()) {
            List<String> found = Server.probe(d, lo, "10.9.9.9", 800);
            assert found.size() == 1 && found.get(0).equals("127.0.0.1:" + s.port) : found;
        }
        try (DatagramSocket d = new DatagramSocket()) {
            assert Server.probe(d, lo, "127.0.0.1", 800).isEmpty() : "should skip itself";
        }
        System.out.println("PROBE_OK");
        System.exit(0);
    }
}
