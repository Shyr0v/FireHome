package com.axel.homeboard

import android.content.Context

data class City(val name: String, val lat: Double, val lon: Double)

val CITIES = listOf(
    City("Bordeaux", 44.8378, -0.5792),
    City("Grenoble", 45.1885, 5.7245),
    City("Lille", 50.6292, 3.0573),
    City("Lyon", 45.7640, 4.8357),
    City("Marseille", 43.2965, 5.3698),
    City("Montpellier", 43.6108, 3.8767),
    City("Nantes", 47.2184, -1.5536),
    City("Nice", 43.7102, 7.2620),
    City("Paris", 48.8566, 2.3522),
    City("Rennes", 48.1173, -1.6778),
    City("Saint-Étienne", 45.4397, 4.3872),
    City("Strasbourg", 48.5734, 7.7521),
    City("Toulouse", 43.6047, 1.4442),
    City("Villeurbanne", 45.7719, 4.8902),
)

/** Index d'une ville par son nom (ville par défaut : firehome.city dans local.properties). */
fun cityIndex(name: String?): Int = CITIES.indexOfFirst { it.name.equals(name, ignoreCase = true) }

/** Thèmes du fond animé : 4 couleurs de taches lumineuses. */
enum class BgTheme(val label: String, val colors: List<Long>) {
    AURORE("Aurore", listOf(0xFF7C3AED, 0xFF2563EB, 0xFFDB2777, 0xFF0891B2)),
    OCEAN("Océan", listOf(0xFF0369A1, 0xFF0D9488, 0xFF1D4ED8, 0xFF06B6D4)),
    SUNSET("Coucher de soleil", listOf(0xFFEA580C, 0xFFDB2777, 0xFF7C3AED, 0xFFF59E0B)),
    FORET("Forêt", listOf(0xFF15803D, 0xFF0F766E, 0xFF65A30D, 0xFF047857)),
    NUIT("Nuit sobre", listOf(0xFF334155, 0xFF1E293B, 0xFF475569, 0xFF3F3F46)),
}

val COLUMN_OPTIONS = listOf(4, 5, 6)
val SAVER_OPTIONS = listOf(0, 1, 3, 5, 10, 15) // minutes, 0 = désactivé

data class HomeSettings(
    val city: Int = 0,
    val theme: BgTheme = BgTheme.AURORE,
    val columns: Int = 5,
    val showRecents: Boolean = true,
    val saverMinutes: Int = 5,
    val showGuide: Boolean = true,
)

object SettingsStore {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("firehome", Context.MODE_PRIVATE)

    fun load(ctx: Context): HomeSettings {
        val p = sp(ctx)
        fun k(base: String) = Profiles.key(ctx, base)
        return HomeSettings(
            city = cityIndex(p.getString(k("cityName"), null)).takeIf { it >= 0 }
                ?: cityIndex(ctx.getString(R.string.default_city)).takeIf { it >= 0 }
                ?: cityIndex("Paris"),
            theme = runCatching { BgTheme.valueOf(p.getString(k("theme"), "AURORE")!!) }.getOrDefault(BgTheme.AURORE),
            columns = p.getInt(k("columns"), 5).takeIf { it in COLUMN_OPTIONS } ?: 5,
            showRecents = p.getBoolean(k("showRecents"), true),
            saverMinutes = p.getInt(k("saverMinutes"), 5).takeIf { it in SAVER_OPTIONS } ?: 5,
            showGuide = p.getBoolean(k("showGuide"), true),
        )
    }

    fun save(ctx: Context, s: HomeSettings) {
        fun k(base: String) = Profiles.key(ctx, base)
        sp(ctx).edit()
            .putString(k("cityName"), CITIES[s.city].name)
            .putString(k("theme"), s.theme.name)
            .putInt(k("columns"), s.columns)
            .putBoolean(k("showRecents"), s.showRecents)
            .putInt(k("saverMinutes"), s.saverMinutes)
            .putBoolean(k("showGuide"), s.showGuide)
            .apply()
    }
}

/** Valeur suivante / précédente dans une liste, en boucle. */
fun <T> List<T>.cycle(current: T, step: Int): T {
    val i = indexOf(current).coerceAtLeast(0)
    return this[(i + step + size) % size]
}
