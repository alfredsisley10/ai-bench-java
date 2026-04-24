package com.omnibank;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Landing page for the Omnibank demo. Serves an interactive HTML page
 * to browsers (so the "Open banking app" button on the WebUI lands on
 * something useful) and a JSON service-info doc to API clients —
 * picked via the Accept header.
 *
 * <p>The HTML page exposes:
 * <ul>
 *   <li>Auth status — confirms whether the visitor is logged in (the
 *       autologin bridge sets this on the redirect from the WebUI).</li>
 *   <li>Account-balance lookup — single text field + "Look up" button
 *       that calls {@code /api/v1/accounts/{n}/balance} and renders
 *       the response inline.</li>
 *   <li>Endpoint catalog — every public surface annotated with method
 *       + auth requirement + a link / curl snippet.</li>
 * </ul>
 *
 * <p>The JSON form preserves the previous shape so existing callers
 * (the WebUI status probe, integration tests, harness scripts) keep
 * working.
 */
@RestController
public class HomeController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> rootHtml(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean loggedIn = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getName());
        String who = loggedIn ? auth.getName() : "anonymous";
        String html = renderHtml(loggedIn, who);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> rootJson() {
        return Map.of(
            "service", "Omnibank demo banking app",
            "purpose", "Synthetic enterprise Java target for ai-bench solver evaluation",
            "endpoints", List.of(
                Map.of("path", "/actuator/health", "auth", "none",
                       "purpose", "Liveness/readiness probe used by the WebUI status panel"),
                Map.of("path", "/api/v1/accounts/{accountNumber}/balance", "auth", "basic",
                       "purpose", "Look up an account's current balance"),
                Map.of("path", "/api/v1/payments", "auth", "basic",
                       "purpose", "Customer-facing payment APIs")
            ),
            "docs", Map.of(
                "operationsGuide", "http://localhost:7777/docs/operations-guide",
                "appmapViewer",    "http://localhost:7777/demo/appmap",
                "demoIssues",      "http://localhost:7777/demo"
            ),
            "note", "Authenticated endpoints use Spring Security's auto-generated " +
                    "in-memory user. The password is logged to banking-app/tmp/bootRun.log " +
                    "on startup ('Using generated security password: …')."
        );
    }

    private String renderHtml(boolean loggedIn, String who) {
        String authBadge = loggedIn
                ? "<span class=\"badge ok\">Logged in as <code>" + escape(who) + "</code></span>"
                : "<span class=\"badge warn\">Not logged in — most /api/* calls will return 401. "
                  + "Use the WebUI's auto-login link to get a session cookie.</span>";
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <title>Omnibank — demo banking app</title>
            <style>
                * { box-sizing: border-box; }
                body { font-family: -apple-system, "system-ui", sans-serif; margin: 0; background: #f4f6fa; color: #1a1a1a; }
                header { background: #0b2545; color: white; padding: 1.2em 1.5em; }
                header h1 { margin: 0; font-size: 1.4em; }
                header .sub { opacity: 0.85; font-size: 0.9em; margin-top: 0.2em; }
                main { max-width: 1100px; margin: 0 auto; padding: 1.5em; display: grid; gap: 1em; grid-template-columns: 1fr 1fr; }
                section { background: white; padding: 1em 1.2em; border-radius: 6px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
                section.full { grid-column: 1 / -1; }
                section h2 { margin: 0 0 0.5em 0; font-size: 1.05em; color: #0b2545; }
                .badge { display: inline-block; padding: 0.2em 0.6em; border-radius: 3px; font-size: 0.85em; }
                .badge.ok { background: #d4edda; color: #155724; }
                .badge.warn { background: #fff3cd; color: #856404; }
                .badge.method { background: #e9ecef; color: #495057; font-family: ui-monospace, "SF Mono", monospace; min-width: 3em; text-align: center; }
                table { border-collapse: collapse; width: 100%; font-size: 0.9em; }
                th, td { border-bottom: 1px solid #e0e2e8; padding: 0.4em 0.5em; text-align: left; vertical-align: top; }
                th { background: #f4f6fa; font-weight: 600; }
                code { background: #f4f6fa; padding: 0.05em 0.3em; border-radius: 3px; font-family: ui-monospace, "SF Mono", monospace; font-size: 0.92em; }
                button, .btn { background: #0b2545; color: white; border: none; padding: 0.4em 0.9em; border-radius: 3px; cursor: pointer; font-size: 0.9em; text-decoration: none; display: inline-block; }
                button:hover, .btn:hover { background: #163d6f; }
                input[type="text"] { padding: 0.4em 0.6em; border: 1px solid #d6d8db; border-radius: 3px; font-family: ui-monospace, monospace; }
                pre { background: #f4f6fa; padding: 0.5em 0.7em; border-radius: 3px; font-size: 0.85em; overflow-x: auto; margin: 0.3em 0 0 0; }
                .help { color: #6c7080; font-size: 0.85em; margin-top: 0.4em; }
                .row { display: flex; gap: 0.5em; align-items: center; flex-wrap: wrap; }
                .footer-links { padding: 1em 1.5em; text-align: center; font-size: 0.85em; color: #6c7080; }
                .footer-links a { color: #0b2545; margin: 0 0.5em; }
            </style>
            </head>
            <body>
            <header>
                <h1>Omnibank</h1>
                <div class="sub">Synthetic enterprise Java banking app — ai-bench solver target</div>
            </header>

            <main>
                <section class="full">
                    <h2>Authentication status</h2>
                    """ + authBadge + """
                    <p class="help">
                        Authenticated endpoints (everything under
                        <code>/api/**</code>) require either the auto-login
                        session cookie minted by the WebUI's
                        <em>Open app (auto-login)</em> button, or HTTP
                        Basic with the credentials in
                        <code>banking-app/tmp/bootRun.log</code>.
                    </p>
                </section>

                <section>
                    <h2>Account balance lookup</h2>
                    <p class="help">Calls <code>GET /api/v1/accounts/{accountNumber}/balance</code> with your current session.</p>
                    <div class="row" style="margin-top:0.5em">
                        <input type="text" id="acctNum" placeholder="OB-C-ABCD1234" size="22">
                        <button onclick="lookupBalance()">Look up</button>
                    </div>
                    <pre id="balanceResult" style="display:none"></pre>
                </section>

                <section>
                    <h2>Health probe</h2>
                    <p class="help">Public — no auth required.</p>
                    <div class="row" style="margin-top:0.5em">
                        <button onclick="checkHealth()">Check /actuator/health</button>
                    </div>
                    <pre id="healthResult" style="display:none"></pre>
                </section>

                <section class="full">
                    <h2>Public endpoints</h2>
                    <table>
                        <thead><tr><th>Method</th><th>Path</th><th>Auth</th><th>Purpose</th><th></th></tr></thead>
                        <tbody>
                            <tr>
                                <td><span class="badge method">GET</span></td>
                                <td><code>/actuator/health</code></td>
                                <td>none</td>
                                <td>Liveness/readiness probe</td>
                                <td><a class="btn" href="/actuator/health" target="_blank">open</a></td>
                            </tr>
                            <tr>
                                <td><span class="badge method">GET</span></td>
                                <td><code>/api/v1/accounts/{n}/balance</code></td>
                                <td>basic / session</td>
                                <td>Look up an account's current balance</td>
                                <td><span class="help">use form above</span></td>
                            </tr>
                            <tr>
                                <td><span class="badge method">GET</span></td>
                                <td><code>/api/v1/payments</code></td>
                                <td>basic / session</td>
                                <td>Customer-facing payment APIs (list)</td>
                                <td><a class="btn" href="/api/v1/payments" target="_blank">open</a></td>
                            </tr>
                            <tr>
                                <td><span class="badge method">GET</span></td>
                                <td><code>/</code> with <code>Accept: application/json</code></td>
                                <td>none</td>
                                <td>Service info as JSON (this page in machine-readable form)</td>
                                <td><a class="btn" href="javascript:fetchJsonRoot()">show JSON</a></td>
                            </tr>
                        </tbody>
                    </table>
                    <pre id="jsonRoot" style="display:none; margin-top:0.5em"></pre>
                </section>

                <section class="full">
                    <h2>Quick curl recipes</h2>
                    <p class="help">For programmatic / scripted access. Replace <code>$USER:$PASS</code> with the credentials from <code>banking-app/tmp/bootRun.log</code>.</p>
                    <pre>
# Health (no auth)
curl http://localhost:8080/actuator/health

# Balance lookup
curl -u $USER:$PASS http://localhost:8080/api/v1/accounts/OB-C-ABCD1234/balance

# Service info as JSON
curl -H 'Accept: application/json' http://localhost:8080/</pre>
                </section>
            </main>

            <div class="footer-links">
                <a href="http://localhost:7777/demo">← back to ai-bench WebUI</a> ·
                <a href="http://localhost:7777/demo/appmap">AppMap traces</a> ·
                <a href="http://localhost:7777/docs/operations-guide">Ops guide</a>
            </div>

            <script>
                function lookupBalance() {
                    var acct = document.getElementById('acctNum').value.trim();
                    if (!acct) { return; }
                    var pre = document.getElementById('balanceResult');
                    pre.style.display = 'block';
                    pre.textContent = 'Loading...';
                    fetch('/api/v1/accounts/' + encodeURIComponent(acct) + '/balance', {
                        credentials: 'include',
                        headers: { 'Accept': 'application/json' }
                    }).then(function(r) {
                        return r.text().then(function(text) {
                            pre.textContent = 'HTTP ' + r.status + '\\n\\n' + text;
                        });
                    }).catch(function(e) {
                        pre.textContent = 'Error: ' + (e && e.message ? e.message : e);
                    });
                }
                function checkHealth() {
                    var pre = document.getElementById('healthResult');
                    pre.style.display = 'block';
                    pre.textContent = 'Loading...';
                    fetch('/actuator/health').then(function(r) {
                        return r.text().then(function(text) {
                            pre.textContent = 'HTTP ' + r.status + '\\n\\n' + text;
                        });
                    });
                }
                function fetchJsonRoot() {
                    var pre = document.getElementById('jsonRoot');
                    pre.style.display = 'block';
                    pre.textContent = 'Loading...';
                    fetch('/', { headers: { 'Accept': 'application/json' } }).then(function(r) {
                        return r.text().then(function(text) {
                            try { pre.textContent = JSON.stringify(JSON.parse(text), null, 2); }
                            catch (_) { pre.textContent = text; }
                        });
                    });
                }
            </script>
            </body>
            </html>
            """;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
