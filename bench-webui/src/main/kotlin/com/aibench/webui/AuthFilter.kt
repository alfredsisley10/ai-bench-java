package com.aibench.webui

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class AuthFilter(
    @Value("\${aibench.admin-users:}") private val adminUsersCsv: String,
    @Value("\${aibench.enable-read-only:true}") private val enableReadOnly: Boolean,
    @Value("\${aibench.auth-enabled:false}") private val authEnabled: Boolean
) : OncePerRequestFilter() {

    private val adminUsers: Set<String> by lazy {
        adminUsersCsv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    private val readOnlyPaths = setOf("/", "/results", "/results/")
    private val staticPrefixes = listOf(
        "/static/", "/actuator/",
        // Springdoc-served OpenAPI spec + Swagger UI assets. Public so
        // the embedded "Demo API" iframe on /demo loads without
        // tripping the auth gate when authEnabled=true.
        "/v3/api-docs", "/swagger-ui", "/swagger-resources", "/webjars/"
    )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI

        if (staticPrefixes.any { path.startsWith(it) }) {
            filterChain.doFilter(request, response)
            return
        }

        if (!authEnabled) {
            request.setAttribute("userRole", UserRole.ADMIN)
            filterChain.doFilter(request, response)
            return
        }

        val userEmail = request.getHeader("X-Forwarded-Email")
            ?: request.getHeader("X-Auth-Email")
            ?: request.session.getAttribute("userEmail") as? String

        val role = when {
            userEmail != null && adminUsers.contains(userEmail.lowercase()) -> UserRole.ADMIN
            enableReadOnly -> UserRole.VIEWER
            else -> {
                response.sendError(403, "Access denied")
                return
            }
        }

        request.setAttribute("userRole", role)
        request.setAttribute("userEmail", userEmail ?: "anonymous")

        if (role == UserRole.VIEWER && !isReadOnlyPath(path, request.method)) {
            response.sendError(403, "Read-only access: this action requires manager privileges")
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun isReadOnlyPath(path: String, method: String): Boolean {
        if (method != "GET") return false
        return readOnlyPaths.contains(path) || path.startsWith("/results") ||
            path.startsWith("/demo") || path.startsWith("/api/demo") || path.startsWith("/copilot-guide")
    }
}
