package com.allan.nottoday

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Navegadores instalados: lista conhecida + todo app que se declara navegador (CATEGORY_APP_BROWSER). */
object Browsers {
    private val known = listOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.duckduckgo.mobile.android",
        "com.google.android.apps.chrome",
        "com.android.browser",
    )

    fun installed(context: Context): List<String> {
        val pm = context.packageManager
        val declared = pm.queryIntentActivities(
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        ).map { it.activityInfo.packageName }
        val installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .map { it.packageName }
            .toSet()
        return (known + declared).distinct()
            .filter { it in installed && it != context.packageName }
            .sorted()
    }
}
