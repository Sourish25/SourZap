package com.sourzap.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.sourzap.app.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppListHelper {

    suspend fun getInstalledLaunchableApps(context: Context, disallowedPackages: Set<String>): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolved = pm.queryIntentActivities(mainIntent, 0)
        val selfPackage = context.packageName

        resolved.mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo.applicationInfo
            val pkg = appInfo.packageName
            if (pkg == selfPackage) return@mapNotNull null

            val name = resolveInfo.loadLabel(pm).toString()
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            AppInfo(
                packageName = pkg,
                appName = name,
                isSystemApp = isSystem,
                isBypassed = disallowedPackages.contains(pkg)
            )
        }.distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }
    }
}