import java.io.File;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Diagnostic — binds a stub AF_UNIX listener at exactly the path bench-webui
 * checks, so we can prove the OS/path/perm layer works independently of the
 * VSCode extension. Speaks the bridge wire format minimally so a probe gets
 * a successful list-models response.
 *
 *   java tooling/enterprise-sim/src/BindCopilotSock.java
 *
 * Stop with Ctrl-C (or kill the background task).
 */
public class BindCopilotSock {
    public static void main(String[] args) throws Exception {
        String tmp = System.getenv("TEMP");
        if (tmp == null) tmp = System.getProperty("java.io.tmpdir");
        String path = (args.length > 0) ? args[0] : new File(tmp, "ai-bench-copilot.sock").getAbsolutePath();

        File f = new File(path);
        if (f.exists()) {
            System.out.println("[bind] stale file at " + path + " — deleting");
            if (!f.delete()) System.out.println("[bind] WARN: delete failed");
        }
        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(path);
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(addr);
            System.out.println("[bind] listening on " + path);
            System.out.println("[bind] file.exists() = " + new File(path).exists());
            while (true) {
                SocketChannel client = server.accept();
                Thread t = new Thread(() -> handle(client), "bind-handler");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    static void handle(SocketChannel c) {
        try (c) {
            ByteBuffer rd = ByteBuffer.allocate(8192);
            int n = c.read(rd);
            rd.flip();
            byte[] req = new byte[n > 0 ? n : 0];
            if (n > 0) rd.get(req);
            System.out.println("[bind] req=" + new String(req, StandardCharsets.UTF_8).trim());
            String resp = "{\"ok\":true,\"models\":[{\"id\":\"stub-model\",\"name\":\"stub\"}]}\n";
            c.write(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            System.out.println("[bind] handle error: " + e);
        }
    }
}
