package com.aria.assistant.setup

data class PermissionUiItem(
    val title: String,
    val description: String,
    val granted: Boolean,
    val blocker: Boolean
)
