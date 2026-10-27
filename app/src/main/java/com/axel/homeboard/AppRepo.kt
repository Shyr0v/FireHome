package com.axel.homeboard

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(
    val label: String,
    val pkg: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val intent: Intent,
)

object AppRepo {
    /** Apps qu'on ne veut pas voir dans la grille. */
    private val HIDDEN = setOf(
        "com.axel.homeboard",
        "com.amazon.tv.launcher",
        "com.amazon.tv.settings.v2",
        "com.amazon.firehomestarter",
        "com.amazon.venezia", // déjà dans les raccourcis
    )

    /** Ordre d'affichage prioritaire (le reste suit par ordre alphabétique). */
    private val FAVORITES = listOf(
        "com.netflix.ninja",
        "com.amazon.firebat",
        "com.crunchyroll.crunchyroid",
        "com.disney.disneyplus",
        "org.smarttube.stable",            // SmartTube
        "org.smarttube.beta",
        "com.amazon.firetv.youtube",
        "com.google.android.youtube.tv",
        "tv.twitch.android.viewer",
        "tv.twitch.android.app",
        "com.spotify.tv.android",
    )

    private var cache: List<AppEntry> = emptyList()
    private var cacheSignature = ""

    /** Liste des apps, recalculée seulement si quelque chose a été installé/mis à jour/supprimé. */
    fun load(ctx: Context): List<AppEntry> {
        val signature = runCatching {
            ctx.packageManager.getInstalledPackages(0)
                .joinToString(",") { it.packageName + ":" + it.lastUpdateTime }
                .hashCode().toString()
        }.getOrDefault("")
        if (signature.isNotEmpty() && signature == cacheSignature && cache.isNotEmpty()) return cache
        return loadFresh(ctx).also { cache = it; cacheSignature = signature }
    }

    private fun loadFresh(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val byPkg = LinkedHashMap<String, AppEntry>()

        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo
                val pkg = ai.packageName
                if (pkg in byPkg || pkg in HIDDEN) continue

                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

                val banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo?.loadBanner(pm) }.getOrNull()
                val icon = runCatching { ai.loadIcon(pm) }.getOrNull()

                // Apps système sans vraie bannière (outils internes Android/Amazon) : on cache
                if (banner == null && (pkg.startsWith("com.android.") || pkg.startsWith("com.amazon.") || pkg.startsWith("android"))) continue

                byPkg[pkg] = AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    pkg = pkg,
                    banner = banner?.toImage(320, 180),
                    icon = icon?.toImage(160, 160),
                    intent = launch,
                )
            }
        }

        return byPkg.values.sortedWith(
            compareBy<AppEntry> { FAVORITES.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun Drawable.toImage(w: Int, h: Int): ImageBitmap? =
        runCatching { toBitmap(w, h).asImageBitmap() }.getOrNull()
}
