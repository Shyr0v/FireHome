package com.axel.homeboard

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DayForecast(val date: LocalDate, val min: Int, val max: Int, val code: Int)

data class Weather(
    val temp: Int,
    val feels: Int,
    val wind: Int,
    val code: Int,
    val days: List<DayForecast>,
)

object WeatherRepo {
    private fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=Europe%2FParis&forecast_days=4"

    suspend fun fetch(lat: Double, lon: Double): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url(lat, lon)).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val cur = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val maxs = daily.getJSONArray("temperature_2m_max")
            val mins = daily.getJSONArray("temperature_2m_min")

            val days = (1 until dates.length()).map { i ->
                DayForecast(
                    date = LocalDate.parse(dates.getString(i)),
                    min = mins.getDouble(i).roundToInt(),
                    max = maxs.getDouble(i).roundToInt(),
                    code = codes.getInt(i),
                )
            }

            Weather(
                temp = cur.getDouble("temperature_2m").roundToInt(),
                feels = cur.getDouble("apparent_temperature").roundToInt(),
                wind = cur.getDouble("wind_speed_10m").roundToInt(),
                code = cur.getInt("weather_code"),
                days = days,
            )
        }.getOrNull()
    }

    /** Code météo WMO -> (emoji, libellé). */
    fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Ciel dégagé"
        1 -> "🌤️" to "Peu nuageux"
        2 -> "⛅" to "Partiellement nuageux"
        3 -> "☁️" to "Couvert"
        45, 48 -> "☁️" to "Brouillard"
        51, 53, 55 -> "🌦️" to "Bruine"
        56, 57 -> "🌧️" to "Bruine verglaçante"
        61, 63, 65 -> "🌧️" to "Pluie"
        66, 67 -> "🌧️" to "Pluie verglaçante"
        71, 73, 75, 77 -> "❄️" to "Neige"
        80, 81, 82 -> "🌦️" to "Averses"
        85, 86 -> "🌨️" to "Averses de neige"
        95 -> "⛈️" to "Orage"
        96, 99 -> "⛈️" to "Orage et grêle"
        else -> "🌡️" to "—"
    }
}
