package com.sourzap.app.data.model

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val isBypassed: Boolean = false
)