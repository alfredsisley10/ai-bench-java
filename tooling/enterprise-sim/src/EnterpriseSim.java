import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enterprise-environment simulator for ai-bench-java build verification.
 *
 *   --proxy-port=3128    auth-required forward HTTP/HTTPS proxy (CONNECT + plain GET)
 *   --mirror-port=8081   caching Maven mirror — fronts Maven Central + Gradle Plugin Portal
 *
 * Logs every request as JSON-Lines to {logDir}/proxy.log and {logDir}/mirror.log.
 *
 * Failure-injection flags (used by failure-scenario tests):
 *   --reject-auth           proxy always returns 407 regardless of credentials
 *   --proxy-blackhole=HOST  proxy returns 502 / hangs CONNECT for this host
 *   --mirror-404=REGEX      mirror returns 404 for any path matching REGEX
 *   --mirror-down           mirror returns 503 to all requests
 *
 * Single-file launch (JDK 17+, no third-party deps):
 *   java tooling/enterprise-sim/src/EnterpriseSim.java [flags]
 */
public class EnterpriseSim {

    static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    static Config cfg;
    static PrintWriter proxyLog;
    static PrintWriter mirrorLog;

    public static void main(String[] args) throws Exception {
        cfg = Config.parse(args);
        Files.createDirectories(cfg.cacheDir);
        Files.createDirectories(cfg.logDir);
        proxyLog = openLog(cfg.logDir.resolve("proxy.log"));
        mirrorLog = openLog(cfg.logDir.resolve("mirror.log"));

        startProxy();
        startMirror();

        System.out.printf("[enterprise-sim] proxy:  http://localhost:%d  (auth: %s/%s)%n",
                cfg.proxyPort, cfg.user, cfg.pass);
        System.out.printf("[enterprise-sim] mirror: http://localhost:%d/maven-central/   http://localhost:%d/gradle-plugins/%n",
                cfg.mirrorPort, cfg.mirrorPort);
        System.out.printf("[enterprise-sim] cache:  %s%n", cfg.cacheDir.toAbsolutePath());
        System.out.printf("[enterprise-sim] logs:   %s%n", cfg.logDir.toAbsolutePath());
        if (cfg.rejectAuth)        System.out.println("[enterprise-sim] FAULT MODE: --reject-auth (proxy returns 407 to all)");
        if (cfg.proxyBlackhole != null) System.out.println("[enterprise-sim] FAULT MODE: --proxy-blackhole=" + cfg.proxyBlackhole);
        if (cfg.mirror404 != null) System.out.println("[enterprise-sim] FAULT MODE: --mirror-404=" + cfg.mirror404);
        if (cfg.mirrorDown)        System.out.println("[enterprise-sim] FAULT MODE: --mirror-down (mirror 503s)");

        // Park forever
        Thread.currentThread().join();
    }

    // ---------- config ----------

    static final class Config {
        int proxyPort = 3128;
        int mirrorPort = 8081;
        Path cacheDir = Path.of("enterprise-sim-cache");
        Path logDir = Path.of("enterprise-sim-logs");
        String user = "bench-user";
        String pass = "bench-pass";
        boolean rejectAuth;
        String proxyBlackhole;
        String mirror404;
        boolean mirrorDown;

        static Config parse(String[] args) {
            Config c = new Config();
            for (String a : args) {
                if      (a.startsWith("--proxy-port=")) c.proxyPort = Integer.parseInt(v(a));
                else if (a.startsWith("--mirror-port=")) c.mirrorPort = Integer.parseInt(v(a));
                else if (a.startsWith("--cache-dir="))  c.cacheDir = Path.of(v(a));
                else if (a.startsWith("--log-dir="))    c.logDir = Path.of(v(a));
                else if (a.startsWith("--user="))       c.user = v(a);
                else if (a.startsWith("--pass="))       c.pass = v(a);
                else if (a.equals("--reject-auth"))     c.rejectAuth = true;
                else if (a.startsWith("--proxy-blackhole=")) c.proxyBlackhole = v(a);
                else if (a.startsWith("--mirror-404=")) c.mirror404 = v(a);
                else if (a.equals("--mirror-down"))     c.mirrorDown = true;
                else if (a.equals("-h") || a.equals("--help")) { printHelp(); System.exit(0); }
                else throw new IllegalArgumentException("unknown flag: " + a);
            }
            return c;
        }
        static String v(String a) { return a.substring(a.indexOf('=') + 1); }
        static void printHelp() {
            System.out.println("Usage: java EnterpriseSim.java [flags]");
            System.out.println("  --proxy-port=N         (default 3128)");
            System.out.println("  --mirror-port=N        (default 8081)");
            System.out.println("  --cache-dir=PATH       (default ./enterprise-sim-cache)");
            System.out.println("  --log-dir=PATH         (default ./enterprise-sim-logs)");
            System.out.println("  --user=USER            (default bench-user)");
            System.out.println("  --pass=PASS            (default bench-pass)");
            System.out.println("Fault injection:");
            System.out.println("  --reject-auth          proxy returns 407 regardless of credentials");
            System.out.println("  --proxy-blackhole=H    proxy 502s any CONNECT to H (e.g. api.foojay.io)");
            System.out.println("  --mirror-404=REGEX     mirror 404s any request path matching REGEX");
            System.out.println("  --mirror-down          mirror 503s all requests");
        }
    }

    // ---------- logging ----------

    static PrintWriter openLog(Path p) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(p,
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND), true);
    }

    static synchronized void emit(PrintWriter w, Map<String, Object> rec) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : rec.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(jsonEscape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null)               sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else                          sb.append('"').append(jsonEscape(String.valueOf(v))).append('"');
        }
        sb.append('}');
        w.println(sb);
    }

    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- proxy ----------

    static void startProxy() throws IOException {
        ServerSocket ss = new ServerSocket(cfg.proxyPort);
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket client = ss.accept();
                    POOL.submit(() -> handleProxy(client));
                } catch (IOException e) {
                    if (!ss.isClosed()) System.err.println("[proxy] accept error: " + e);
                }
            }
        }, "proxy-accept");
        t.setDaemon(true);
        t.start();
    }

    static void handleProxy(Socket client) {
        String method = "?", dest = "?";
        boolean authOk = false;
        int status = 0;
        long bytesUp = 0, bytesDown = 0;
        try {
            client.setSoTimeout(60_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { return; }
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 3) { return; }
            method = parts[0];
            String target = parts[1];
            dest = target; // record before auth so failed attempts still show what they tried to reach
            Map<String, String> headers = readHeaders(in);
            authOk = checkAuth(headers);

            if (cfg.rejectAuth || !authOk) {
                status = 407;
                writeStatus(out, 407, "Proxy Authentication Required",
                        "Proxy-Authenticate: Basic realm=\"enterprise-sim\"\r\n");
                return;
            }

            if ("CONNECT".equalsIgnoreCase(method)) {
                if (cfg.proxyBlackhole != null && target.startsWith(cfg.proxyBlackhole)) {
                    status = 502;
                    writeStatus(out, 502, "Bad Gateway", "");
                    return;
                }
                String[] hp = target.split(":", 2);
                String host = hp[0];
                int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 443;
                Socket upstream;
                try {
                    upstream = new Socket();
                    upstream.connect(new InetSocketAddress(host, port), 15_000);
                } catch (IOException ioe) {
                    status = 502;
                    writeStatus(out, 502, "Bad Gateway", "");
                    return;
                }
                status = 200;
                writeStatus(out, 200, "Connection Established", "");
                long[] counts = tunnel(client, upstream);
                bytesUp = counts[0];
                bytesDown = counts[1];
            } else {
                // Plain HTTP forward (method GET http://host/path HTTP/1.1)
                URI u;
                try { u = URI.create(target); } catch (Exception e) { writeStatus(out, 400, "Bad Request", ""); status = 400; return; }
                if (u.getHost() == null) { writeStatus(out, 400, "Bad Request", ""); status = 400; return; }
                dest = u.getHost() + (u.getPort() < 0 ? "" : (":" + u.getPort()));
                int port = u.getPort() < 0 ? 80 : u.getPort();
                try (Socket up = new Socket()) {
                    up.connect(new InetSocketAddress(u.getHost(), port), 15_000);
                    OutputStream uo = up.getOutputStream();
                    String fwd = method + " " + (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                            + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "") + " HTTP/1.1\r\n";
                    uo.write(fwd.getBytes());
                    headers.remove("proxy-authorization");
                    headers.remove("proxy-connection");
                    headers.put("host", u.getHost() + (u.getPort() < 0 ? "" : (":" + u.getPort())));
                    headers.put("connection", "close");
                    for (var e : headers.entrySet()) {
                        uo.write((e.getKey() + ": " + e.getValue() + "\r\n").getBytes());
                    }
                    uo.write("\r\n".getBytes());
                    uo.flush();
                    bytesDown = pipe(up.getInputStream(), out);
                    status = 200; // status of forward — not parsed; we just relayed
                }
            }
        } catch (Exception e) {
            // swallow per-connection
        } finally {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("ts", Instant.now().toString());
            rec.put("kind", "proxy");
            rec.put("method", method);
            rec.put("dest", dest);
            rec.put("authOk", authOk);
            rec.put("status", status);
            rec.put("bytesUp", bytesUp);
            rec.put("bytesDown", bytesDown);
            emit(proxyLog, rec);
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    static boolean checkAuth(Map<String, String> headers) {
        String h = headers.get("proxy-authorization");
        if (h == null || !h.toLowerCase().startsWith("basic ")) return false;
        try {
            byte[] dec = Base64.getDecoder().decode(h.substring(6).trim());
            String creds = new String(dec, java.nio.charset.StandardCharsets.UTF_8);
            int colon = creds.indexOf(':');
            if (colon < 0) return false;
            return creds.substring(0, colon).equals(cfg.user) && creds.substring(colon + 1).equals(cfg.pass);
        } catch (Exception e) {
            return false;
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                byte[] arr = b.toByteArray();
                return new String(arr, 0, arr.length - 1, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            b.write(c);
            prev = c;
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) m.put(line.substring(0, colon).trim().toLowerCase(),
                                  line.substring(colon + 1).trim());
        }
        return m;
    }

    static void writeStatus(OutputStream out, int code, String reason, String extraHeaders) throws IOException {
        String s = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Connection: close\r\n"
                + "Content-Length: 0\r\n"
                + extraHeaders
                + "\r\n";
        out.write(s.getBytes());
        out.flush();
    }

    /** Bidirectional pipe between client and upstream. Returns [upstreamBytes, downstreamBytes]. */
    static long[] tunnel(Socket client, Socket upstream) {
        long[] counts = new long[2];
        Thread up = new Thread(() -> {
            try { counts[0] = pipe(client.getInputStream(), upstream.getOutputStream()); }
            catch (IOException ignored) {}
            finally { try { upstream.shutdownOutput(); } catch (IOException ignored) {} }
        }, "proxy-tunnel-up");
        up.setDaemon(true);
        up.start();
        try { counts[1] = pipe(upstream.getInputStream(), client.getOutputStream()); }
        catch (IOException ignored) {}
        finally {
            try { client.shutdownOutput(); } catch (IOException ignored) {}
            try { up.join(2_000); } catch (InterruptedException ignored) {}
            try { upstream.close(); } catch (IOException ignored) {}
        }
        return counts;
    }

    static long pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        out.flush();
        return total;
    }

    // ---------- mirror ----------

    static void startMirror() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.mirrorPort), 0);
        server.createContext("/maven-central/",
                ex -> handleMirror(ex, "https://repo.maven.apache.org/maven2/"));
        server.createContext("/gradle-plugins/",
                ex -> handleMirror(ex, "https://plugins.gradle.org/m2/"));
        server.createContext("/health", ex -> {
            byte[] b = "ok\n".getBytes();
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.setExecutor(POOL);
        server.start();
    }

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();

    static void handleMirror(HttpExchange ex, String upstreamBase) throws IOException {
        String requestPath = ex.getRequestURI().getPath();
        String prefix = ex.getHttpContext().getPath();          // e.g. "/maven-central/"
        String sub = requestPath.startsWith(prefix) ? requestPath.substring(prefix.length()) : requestPath;
        String upstreamUrl = upstreamBase + sub;
        Path cachePath = cfg.cacheDir.resolve(prefix.replace("/", "")).resolve(sub.replace('/', java.io.File.separatorChar));
        boolean cacheHit = false;
        boolean upstreamFetched = false;
        int status = 0;
        long bytes = 0;
        try {
            if (cfg.mirrorDown) {
                status = 503;
                ex.sendResponseHeaders(503, -1);
                return;
            }
            if (cfg.mirror404 != null && sub.matches(cfg.mirror404)) {
                status = 404;
                ex.sendResponseHeaders(404, -1);
                return;
            }
            if (Files.exists(cachePath)) {
                cacheHit = true;
                byte[] body = Files.readAllBytes(cachePath);
                bytes = body.length;
                if (ex.getRequestMethod().equalsIgnoreCase("HEAD")) {
                    ex.getResponseHeaders().add("Content-Length", Long.toString(bytes));
                    ex.sendResponseHeaders(200, -1);
                    status = 200;
                    return;
                }
                ex.sendResponseHeaders(200, bytes);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                status = 200;
                return;
            }

            // Cache miss → fetch upstream (direct from sim — this models internal egress)
            Files.createDirectories(cachePath.getParent());
            HttpRequest req = HttpRequest.newBuilder(URI.create(upstreamUrl))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("User-Agent", "enterprise-sim-mirror/1.0")
                    .GET().build();
            HttpResponse<byte[]> resp;
            try {
                resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (Exception e) {
                status = 502;
                ex.sendResponseHeaders(502, -1);
                return;
            }
            upstreamFetched = true;
            status = resp.statusCode();
            byte[] body = resp.body();
            if (status == 200) {
                Path tmp = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
                Files.write(tmp, body);
                Files.move(tmp, cachePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                bytes = body.length;
                ex.sendResponseHeaders(200, bytes);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            } else {
                ex.sendResponseHeaders(status, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                bytes = body.length;
            }
        } finally {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("ts", Instant.now().toString());
            rec.put("kind", "mirror");
            rec.put("repo", prefix.replaceAll("/", ""));
            rec.put("path", sub);
            rec.put("method", ex.getRequestMethod());
            rec.put("cacheHit", cacheHit);
            rec.put("upstreamFetched", upstreamFetched);
            rec.put("status", status);
            rec.put("bytes", bytes);
            emit(mirrorLog, rec);
            ex.close();
        }
    }
}
