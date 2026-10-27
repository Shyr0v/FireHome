package com.axel.homeboard

import android.content.Context

/**
 * Deux profils indépendants : chacun a ses réglages, son ordre d'apps,
 * ses apps masquées et ses apps récentes.
 * Les prénoms viennent de local.properties (firehome.profiles=Prénom1,Prénom2).
 * Le profil 0 garde les clés d'origine (compatibilité avec la config existante).
 */
object Profiles {
    fun names(ctx: Context): List<String> =
        ctx.getString(R.string.profile_names).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("Profil 1", "Profil 2") }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("firehome", Context.MODE_PRIVATE)

    fun count(ctx: Context) = names(ctx).size

    fun active(ctx: Context): Int = sp(ctx).getInt("profile", 0).coerceIn(0, count(ctx) - 1)

    fun setActive(ctx: Context, index: Int) {
        sp(ctx).edit().putInt("profile", index.coerceIn(0, count(ctx) - 1)).apply()
    }

    fun name(ctx: Context) = names(ctx)[active(ctx)]

    /** Clé de préférence propre au profil actif. */
    fun key(ctx: Context, base: String): String {
        val p = active(ctx)
        return if (p == 0) base else "p${p}_$base"
    }
}
