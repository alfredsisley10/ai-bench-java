package com.omnibank;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-use auto-login bridge between the WebUI manager and this
 * banking app. The WebUI launches the banking app subprocess with
 * {@code OMNIBANK_AUTOLOGIN_TOKEN=<random-256-bit>} on the
 * environment; that value is held in memory by both processes and
 * never written to disk.
 *
 * <p>When the user clicks "Open banking app" in the WebUI, the WebUI
 * generates a redirect URL of the form
 * {@code /_demo/autologin?token=<token>&redirect=<path>}. This filter
 * intercepts that URL, validates the token, and then:
 *   <ul>
 *     <li>If the token matches the expected value, mints a session for
 *         the {@code demo-user} principal, atomically swaps the
 *         expected-token reference to a new random value (so the URL
 *         can never be replayed), and 302s the browser to the
 *         {@code redirect} parameter.</li>
 *     <li>If the token doesn't match, returns 403 with no body.</li>
 *   </ul>
 *
 * <p>Constant-time comparison is used to prevent timing leaks. The
 * filter is a no-op (passthrough) if the launching process didn't set
 * {@code OMNIBANK_AUTOLOGIN_TOKEN} — i.e. when the banking app was
 * started outside the WebUI manager.
 */
@Component
public class OneTimeAutologinFilter extends OncePerRequestFilter {

    /** Path the WebUI redirects to. */
    public static final String AUTOLOGIN_PATH = "/_demo/autologin";

    /**
     * Holds the currently-valid expected token. Atomically swapped on
     * successful exchange so a captured URL can't be reused, and
     * cleared on bean destruction.
     */
    private final AtomicReference<String> expectedToken = new AtomicReference<>();

    public OneTimeAutologinFilter(@Value("${OMNIBANK_AUTOLOGIN_TOKEN:}") String tokenFromEnv) {
        if (tokenFromEnv != null && !tokenFromEnv.isBlank()) {
            expectedToken.set(tokenFromEnv);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!AUTOLOGIN_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String expected = expectedToken.get();
        String supplied = request.getParameter("token");
        if (expected == null || supplied == null || !constantTimeEquals(expected, supplied)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid or expired autologin token");
            return;
        }

        // Token matched — burn it so the URL can't be replayed. Replace
        // with a fresh random value (not null) so a subsequent valid
        // exchange would need a freshly-minted token from the WebUI.
        if (!expectedToken.compareAndSet(expected, java.util.UUID.randomUUID().toString())) {
            // Lost the race — another concurrent request already
            // consumed the token. Reject this one too.
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "token already consumed");
            return;
        }

        // Mint a Spring Security session for the demo user.
        var auth = new UsernamePasswordAuthenticationToken(
                "demo-user", null,
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        // Persist the SecurityContext into the HTTP session so subsequent
        // requests are authenticated.
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());

        String redirect = request.getParameter("redirect");
        if (redirect == null || redirect.isBlank() || !redirect.startsWith("/")) {
            redirect = "/";
        }
        response.sendRedirect(redirect);
    }

    /**
     * Constant-time string equality so token validation doesn't leak
     * bytes via response timing.
     */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] aa = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aa, bb);
    }
}
