package com.omnibank.customerportal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API versioning filter that supports three negotiation strategies,
 * evaluated in priority order:
 *
 * <ol>
 *   <li><b>URL path</b> — {@code /api/v1/accounts}, {@code /api/v2/accounts}</li>
 *   <li><b>Accept-Version header</b> — {@code Accept-Version: v2}</li>
 *   <li><b>Content-Type vendor media type</b> —
 *       {@code Accept: application/vnd.omnibank.v2+json}</li>
 * </ol>
 *
 * <p>When the requested version differs from the latest, the filter can
 * apply registered response transformers to convert the latest-version
 * response into the format expected by older clients.
 *
 * <p>This filter runs after {@link RequestCorrelation} but before business
 * controllers, so that controllers always operate on the latest version
 * and transformation is a cross-cutting concern.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiVersionRouter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionRouter.class);

    // -------------------------------------------------------------------
    //  Version model
    // -------------------------------------------------------------------

    /**
     * Represents an API version with major and optional minor parts.
     *
     * @param major the major version number (e.g. 1, 2, 3)
     * @param minor the minor version number (0 if unspecified)
     */
    public record ApiVersion(int major, int minor) implements Comparable<ApiVersion> {

        public ApiVersion {
            if (major < 1) throw new IllegalArgumentException("major must be >= 1");
            if (minor < 0) throw new IllegalArgumentException("minor must be >= 0");
        }

        public static ApiVersion of(int major) {
            return new ApiVersion(major, 0);
        }

        public static ApiVersion of(int major, int minor) {
            return new ApiVersion(major, minor);
        }

        /**
         * Parses a version string. Accepts: "v1", "v2", "v1.1", "1", "2.1".
         */
        public static Optional<ApiVersion> parse(String raw) {
            if (raw == null || raw.isBlank()) return Optional.empty();
            String normalized = raw.strip().toLowerCase();
            if (normalized.startsWith("v")) {
                normalized = normalized.substring(1);
            }
            String[] parts = normalized.split("\\.");
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                return Optional.of(new ApiVersion(major, minor));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        @Override
        public int compareTo(ApiVersion other) {
            int c = Integer.compare(this.major, other.major);
            return c != 0 ? c : Integer.compare(this.minor, other.minor);
        }

        @Override
        public String toString() {
            return minor == 0 ? "v" + major : "v" + major + "." + minor;
        }
    }

    // -------------------------------------------------------------------
    //  Version negotiation result
    // -------------------------------------------------------------------

    /**
     * The outcome of version negotiation for a request.
     *
     * @param resolved           the version that will serve this request
     * @param negotiationMethod  how the version was determined
     * @param transformRequired  whether response transformation is needed
     */
    public record NegotiationResult(
            ApiVersion resolved,
            String negotiationMethod,
            boolean transformRequired
    ) {}

    // -------------------------------------------------------------------
    //  Response transformer
    // -------------------------------------------------------------------

    /**
     * Transforms a response body from one API version to another.
     *
     * @param fromVersion source version
     * @param toVersion   target version
     * @param path        the API path (for path-specific transformations)
     * @param transformer function that takes the source JSON and returns the target JSON
     */
    public record VersionTransformer(
            ApiVersion fromVersion,
            ApiVersion toVersion,
            String pathPattern,
            UnaryOperator<String> transformer
    ) {
        public VersionTransformer {
            Objects.requireNonNull(fromVersion, "fromVersion");
            Objects.requireNonNull(toVersion, "toVersion");
            Objects.requireNonNull(pathPattern, "pathPattern");
            Objects.requireNonNull(transformer, "transformer");
        }

        boolean matchesPath(String requestPath) {
            return requestPath.matches(pathPattern);
        }
    }

    // -------------------------------------------------------------------
    //  Configuration and state
    // -------------------------------------------------------------------

    private static final Pattern URL_VERSION_PATTERN = Pattern.compile("/api/(v\\d+(?:\\.\\d+)?)/");
    private static final Pattern VENDOR_MEDIA_TYPE_PATTERN =
            Pattern.compile("application/vnd\\.omnibank\\.(v\\d+(?:\\.\\d+)?)\\+json");
    private static final String ACCEPT_VERSION_HEADER = "Accept-Version";
    private static final String RESPONSE_VERSION_HEADER = "X-Api-Version";
    private static final String RESPONSE_DEPRECATED_HEADER = "X-Api-Deprecated";

    private final ApiVersion latestVersion;
    private final Set<ApiVersion> supportedVersions;
    private final Set<ApiVersion> deprecatedVersions;
    private final Map<String, VersionTransformer> transformers;

    public ApiVersionRouter() {
        this(ApiVersion.of(2),
             Set.of(ApiVersion.of(1), ApiVersion.of(2)),
             Set.of(ApiVersion.of(1)),
             List.of());
    }

    public ApiVersionRouter(ApiVersion latestVersion,
                            Set<ApiVersion> supportedVersions,
                            Set<ApiVersion> deprecatedVersions,
                            List<VersionTransformer> transformers) {
        this.latestVersion = Objects.requireNonNull(latestVersion, "latestVersion");
        this.supportedVersions = new TreeSet<>(supportedVersions);
        this.deprecatedVersions = Set.copyOf(deprecatedVersions);
        this.transformers = indexTransformers(transformers);
    }

    // -------------------------------------------------------------------
    //  Filter logic
    // -------------------------------------------------------------------

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        NegotiationResult negotiation = negotiate(request);
        ApiVersion resolved = negotiation.resolved();

        // Check if the resolved version is supported
        if (!supportedVersions.contains(resolved)) {
            sendVersionNotSupported(response, resolved);
            return;
        }

        // Add version metadata headers
        response.setHeader(RESPONSE_VERSION_HEADER, resolved.toString());
        if (deprecatedVersions.contains(resolved)) {
            response.setHeader(RESPONSE_DEPRECATED_HEADER, "true");
            response.setHeader("Sunset", computeSunsetDate(resolved));
            log.info("Request using deprecated API version {}", resolved);
        }

        // If the request targets a version older than latest via URL path,
        // rewrite the URL to point to the latest controller and mark for
        // response transformation
        HttpServletRequest effectiveRequest = request;
        if (negotiation.transformRequired()) {
            effectiveRequest = rewriteUrlVersion(request, resolved, latestVersion);
        }

        chain.doFilter(effectiveRequest, response);
    }

    // -------------------------------------------------------------------
    //  Version negotiation
    // -------------------------------------------------------------------

    /**
     * Negotiates the API version from the request using the priority:
     * URL path > Accept-Version header > vendor media type > default.
     */
    NegotiationResult negotiate(HttpServletRequest request) {
        // 1. URL path version
        Optional<ApiVersion> urlVersion = extractUrlVersion(request.getRequestURI());
        if (urlVersion.isPresent()) {
            boolean transform = urlVersion.get().compareTo(latestVersion) < 0;
            return new NegotiationResult(urlVersion.get(), "url-path", transform);
        }

        // 2. Accept-Version header
        String acceptVersionHeader = request.getHeader(ACCEPT_VERSION_HEADER);
        Optional<ApiVersion> headerVersion = ApiVersion.parse(acceptVersionHeader);
        if (headerVersion.isPresent()) {
            boolean transform = headerVersion.get().compareTo(latestVersion) < 0;
            return new NegotiationResult(headerVersion.get(), "accept-version-header", transform);
        }

        // 3. Vendor media type in Accept header
        String accept = request.getHeader("Accept");
        if (accept != null) {
            Optional<ApiVersion> mediaTypeVersion = extractVendorMediaTypeVersion(accept);
            if (mediaTypeVersion.isPresent()) {
                boolean transform = mediaTypeVersion.get().compareTo(latestVersion) < 0;
                return new NegotiationResult(mediaTypeVersion.get(), "vendor-media-type", transform);
            }
        }

        // 4. Default to latest
        return new NegotiationResult(latestVersion, "default", false);
    }

    // -------------------------------------------------------------------
    //  URL rewriting
    // -------------------------------------------------------------------

    /**
     * Wraps the request so the servlet container sees a URL targeting the
     * latest version's controller.
     */
    private HttpServletRequest rewriteUrlVersion(HttpServletRequest original,
                                                  ApiVersion from,
                                                  ApiVersion to) {
        String originalUri = original.getRequestURI();
        String rewrittenUri = originalUri.replace(
                "/api/" + from + "/",
                "/api/" + to + "/");

        log.debug("Rewriting URL from {} to {}", originalUri, rewrittenUri);

        return new HttpServletRequestWrapper(original) {
            @Override
            public String getRequestURI() {
                return rewrittenUri;
            }

            @Override
            public StringBuffer getRequestURL() {
                StringBuffer url = new StringBuffer();
                url.append(original.getScheme()).append("://")
                   .append(original.getServerName());
                if (original.getServerPort() != 80 && original.getServerPort() != 443) {
                    url.append(':').append(original.getServerPort());
                }
                url.append(rewrittenUri);
                return url;
            }
        };
    }

    // -------------------------------------------------------------------
    //  Parsing helpers
    // -------------------------------------------------------------------

    private Optional<ApiVersion> extractUrlVersion(String uri) {
        Matcher m = URL_VERSION_PATTERN.matcher(uri);
        if (m.find()) {
            return ApiVersion.parse(m.group(1));
        }
        return Optional.empty();
    }

    private Optional<ApiVersion> extractVendorMediaTypeVersion(String accept) {
        Matcher m = VENDOR_MEDIA_TYPE_PATTERN.matcher(accept);
        if (m.find()) {
            return ApiVersion.parse(m.group(1));
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------
    //  Error responses
    // -------------------------------------------------------------------

    private void sendVersionNotSupported(HttpServletResponse response,
                                         ApiVersion requested) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String supported = supportedVersions.stream()
                .sorted(Comparator.reverseOrder())
                .map(ApiVersion::toString)
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        response.getWriter().write("""
                {
                  "error": "UNSUPPORTED_API_VERSION",
                  "message": "API version %s is not supported. Supported versions: %s",
                  "supportedVersions": "%s",
                  "latestVersion": "%s"
                }
                """.formatted(requested, supported, supported, latestVersion));
    }

    // -------------------------------------------------------------------
    //  Internals
    // -------------------------------------------------------------------

    private static Map<String, VersionTransformer> indexTransformers(
            List<VersionTransformer> transformers) {
        Map<String, VersionTransformer> map = new LinkedHashMap<>();
        for (VersionTransformer t : transformers) {
            String key = t.fromVersion() + "->" + t.toVersion() + ":" + t.pathPattern();
            map.put(key, t);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Returns a placeholder sunset date for deprecated versions. In production
     * this would be loaded from configuration.
     */
    private static String computeSunsetDate(ApiVersion version) {
        // RFC 8594 Sunset header format
        return switch (version.major()) {
            case 1 -> "Sat, 31 Dec 2025 23:59:59 GMT";
            default -> ""; // no sunset date for non-deprecated versions
        };
    }

    /** Visible-for-testing: latest version. */
    ApiVersion latestVersion() {
        return latestVersion;
    }

    /** Visible-for-testing: supported versions. */
    Set<ApiVersion> supportedVersions() {
        return Collections.unmodifiableSet(supportedVersions);
    }
}
