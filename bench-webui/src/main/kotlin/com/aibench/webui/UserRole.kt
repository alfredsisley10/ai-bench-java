package com.aibench.webui

enum class UserRole {
    ADMIN, VIEWER;

    val isAdmin: Boolean get() = this == ADMIN
}
