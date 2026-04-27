import java.io.File;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Mimics bench-webui's Platform.defaultCopilotSocket() resolution and connection
 * attempt — prints exactly what bench-webui sees, plus tries a direct connect
 * (which works even when File.exists() reports false on Windows AF_UNIX files).
 *
 * Run:  java tooling/enterprise-sim/src/ProbeCopilotSock.java
 */
public class ProbeCopilotSock {
    public static void main(String[] args) {
        String override = System.getenv("AI_BENCH_COPILOT_SOCK");
        String temp = System.getenv("TEMP");
        String javaTmp = System.getProperty("java.io.tmpdir", "C:\\Temp");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        System.out.println("env AI_BENCH_COPILOT_SOCK = " + override);
        System.out.println("env TEMP                  = " + temp);
        System.out.println("sys java.io.tmpdir        = " + javaTmp);
        System.out.println("isWindows                 = " + isWindows);

        String resolved;
        if (override != null && !override.isBlank()) {
            resolved = override;
        } else if (isWindows) {
            String t = (temp != null) ? temp : javaTmp;
            resolved = new File(t, "ai-bench-copilot.sock").getAbsolutePath();
        } else {
            resolved = "/tmp/ai-bench-copilot.sock";
        }
        System.out.println("bench-webui would check    = " + resolved);

        // Also probe the long-form path as a sanity check
        String[] candidates = isWindows
            ? new String[] { resolved,
                             "C:\\Users\\ValuedUser\\AppData\\Local\\Temp\\ai-bench-copilot.sock",
                             "C:\\Users\\VALUED~1\\AppData\\Local\\Temp\\ai-bench-copilot.sock" }
            : new String[] { resolved };

        for (String c : candidates) {
            File f = new File(c);
            System.out.println();
            System.out.println("--- " + c + " ---");
            System.out.println("  exists=" + f.exists() + "  isFile=" + f.isFile()
                    + "  canRead=" + f.canRead() + "  length=" + safeLen(f));
            try {
                UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(c);
                try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    ch.connect(addr);
                    System.out.println("  CONNECT: ok  (something is listening here)");
                    ByteBuffer req = ByteBuffer.wrap(("{\"op\":\"list-models\",\"id\":\"probe\"}\n").getBytes(StandardCharsets.UTF_8));
                    ch.write(req);
                    ByteBuffer resp = ByteBuffer.allocate(2048);
                    int n = ch.read(resp);
                    if (n > 0) {
                        resp.flip();
                        byte[] b = new byte[Math.min(n, 200)];
                        resp.get(b);
                        System.out.println("  response head: " + new String(b, StandardCharsets.UTF_8));
                    } else {
                        System.out.println("  CONNECT: connected but server closed before sending bytes");
                    }
                }
            } catch (Exception e) {
                System.out.println("  CONNECT: failed — " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
    static String safeLen(File f) { try { return String.valueOf(f.length()); } catch (Exception e) { return "?"; } }
}
