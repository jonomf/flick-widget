package com.jono.flick

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * The apps you used last, for the top of the app picker. With usage access (a setting you turn on
 * for Flick) these are the phone's real last-used apps; without it, Flick's own recent launches.
 */
object RecentApps {

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Opens Android's usage access page (Flick's own entry where the phone supports it). */
    fun settingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings(ctx: Context) {
        runCatching { ctx.startActivity(settingsIntent(ctx)) }.onFailure {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Up to [n] apps, most recent first, only ones with a launcher icon (not Flick or the home screen). */
    fun get(ctx: Context, apps: List<Slot.App>, n: Int = 3): List<Slot.App> {
        val byPkg = apps.associateBy { it.pkg }
        val skip = setOf(ctx.packageName) + homePackages(ctx)
        val fromPhone = if (hasAccess(ctx)) {
            runCatching {
                val usm = ctx.getSystemService(UsageStatsManager::class.java)
                val now = System.currentTimeMillis()
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3 * 24 * 3600_000L, now)
                    .filter { it.lastTimeUsed > 0 && it.packageName !in skip }
                    .groupBy { it.packageName }
                    .map { (pkg, list) -> pkg to list.maxOf { it.lastTimeUsed } }
                    .sortedByDescending { it.second }
                    .mapNotNull { byPkg[it.first] }
            }.getOrDefault(emptyList())
        } else emptyList()
        val fromFlick = Store.recents(ctx).mapNotNull { byPkg[it.pkg] }
        return (fromPhone + fromFlick).distinctBy { it.pkg }.filter { it.pkg !in skip }.take(n)
    }

    private fun homePackages(ctx: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }
}
