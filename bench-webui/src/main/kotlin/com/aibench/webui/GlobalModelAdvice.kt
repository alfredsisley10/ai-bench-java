package com.aibench.webui

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import java.time.format.DateTimeFormatter

@ControllerAdvice
class GlobalModelAdvice(
    // Nullable because META-INF/build-info.properties only exists when
    // the bootBuildInfo Gradle task ran. Unit tests / IDE plain-`main`
    // launches don't generate it, and we don't want @ControllerAdvice
    // to fail wiring just because the footer would be incomplete.
    @Autowired(required = false) private val buildProperties: BuildProperties? = null
) {

    @ModelAttribute("userRole")
    fun userRole(request: HttpServletRequest): UserRole =
        request.getAttribute("userRole") as? UserRole ?: UserRole.VIEWER

    @ModelAttribute("isAdmin")
    fun isAdmin(request: HttpServletRequest): Boolean =
        (request.getAttribute("userRole") as? UserRole)?.isAdmin ?: false

    @ModelAttribute("userEmail")
    fun userEmail(request: HttpServletRequest): String =
        request.getAttribute("userEmail") as? String ?: "anonymous"

    /**
     * Build / git provenance for the running JAR — surfaced in the
     * layout footer so an operator can confirm "am I on the latest
     * version?" without grepping logs. Falls back to safe defaults
     * when build-info.properties wasn't generated (dev IDE run).
     */
    @ModelAttribute("buildInfo")
    fun buildInfo(): Map<String, String> {
        val b = buildProperties
        val time = b?.time?.let {
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(java.time.ZoneId.systemDefault()).format(it)
        } ?: "(dev build)"
        return mapOf(
            "version"        to (b?.version ?: "dev"),
            "buildTime"      to time,
            "gitCommit"      to (b?.get("git.commit") ?: "unknown"),
            "gitCommitFull"  to (b?.get("git.commit.full") ?: "unknown"),
            "gitCommitDate"  to (b?.get("git.commitDate") ?: ""),
            "gitBranch"      to (b?.get("git.branch") ?: "unknown")
        )
    }
}
