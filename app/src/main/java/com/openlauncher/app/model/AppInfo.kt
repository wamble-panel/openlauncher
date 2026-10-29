package com.openlauncher.app.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val isSystemApp: Boolean = false,
    /** Has a launcher icon — what the MMI Apps page shows. */
    val launchable: Boolean = true,
)
