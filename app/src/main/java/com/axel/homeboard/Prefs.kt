package com.axel.homeboard

import android.content.Context

/** Réglages persistants de FireHome (ordre, apps masquées, récents). */
object Prefs {
    private const val FILE = "firehome"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun getList(ctx: Context, key: String): List<String> =
        sp(ctx).getString(Profiles.key(ctx, key), "").orEmpty().split(',').filter { it.isNotBlank() }

    private fun putList(ctx: Context, key: String, list: List<String>) =
        sp(ctx).edit().putString(Profiles.key(ctx, key), list.joinToString(",")).apply()

    /* Apps récentes (la plus récente en premier) */
    fun recents(ctx: Context) = getList(ctx, "recents")

    fun pushRecent(ctx: Context, pkg: String) {
        val list = (listOf(pkg) + recents(ctx).filter { it != pkg }).take(12)
        putList(ctx, "recents", list)
    }

    /* Ordre personnalisé (liste complète de paquets) */
    fun order(ctx: Context) = getList(ctx, "order")
    fun setOrder(ctx: Context, order: List<String>) = putList(ctx, "order", order)

    /* Apps masquées */
    fun hidden(ctx: Context) = getList(ctx, "hidden").toSet()
    fun setHidden(ctx: Context, pkg: String, hide: Boolean) {
        val set = hidden(ctx).toMutableSet()
        if (hide) set += pkg else set -= pkg
        putList(ctx, "hidden", set.toList())
    }
}
