package com.aibench.webui

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

@ControllerAdvice
class GlobalModelAdvice {

    @ModelAttribute("userRole")
    fun userRole(request: HttpServletRequest): UserRole =
        request.getAttribute("userRole") as? UserRole ?: UserRole.VIEWER

    @ModelAttribute("isAdmin")
    fun isAdmin(request: HttpServletRequest): Boolean =
        (request.getAttribute("userRole") as? UserRole)?.isAdmin ?: false

    @ModelAttribute("userEmail")
    fun userEmail(request: HttpServletRequest): String =
        request.getAttribute("userEmail") as? String ?: "anonymous"
}
