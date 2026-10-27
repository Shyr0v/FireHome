package com.axel.homeboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export / import de toute la config FireHome (les 2 profils), sans fichier :
 * le JSON transite en base64 dans la réponse / les extras du broadcast.
 * Déclenché uniquement via ADB (permission DUMP, que les apps normales n'ont pas) :
 *   adb shell am broadcast -a com.axel.homeboard.EXPORT -n com.axel.homeboard/.BackupReceiver
 *   adb shell am broadcast -a com.axel.homeboard.IMPORT -n com.axel.homeboard/.BackupReceiver --es data <base64>
 */
class BackupReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val sp = ctx.getSharedPreferences("firehome", Context.MODE_PRIVATE)
        resultData = try {
            when (intent.action) {
                ACTION_EXPORT -> {
                    val json = export(sp.all)
                    "B64:" + Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
                }
                ACTION_IMPORT -> {
                    val data = intent.getStringExtra("data") ?: return run { resultData = "ERREUR: pas de donnees" }
                    val json = String(Base64.decode(data, Base64.DEFAULT))
                    val n = import(json, sp)
                    "OK import $n reglages"
                }
                else -> "ERREUR: action inconnue"
            }
        } catch (e: Exception) {
            "ERREUR: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
        }
    }

    private fun export(all: Map<String, *>): String {
        val root = JSONObject()
        for ((k, v) in all) {
            val entry = JSONObject()
            when (v) {
                is String -> entry.put("t", "s").put("v", v)
                is Int -> entry.put("t", "i").put("v", v)
                is Long -> entry.put("t", "l").put("v", v)
                is Boolean -> entry.put("t", "b").put("v", v)
                is Float -> entry.put("t", "f").put("v", v.toDouble())
                is Set<*> -> entry.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            root.put(k, entry)
        }
        return root.toString()
    }

    private fun import(json: String, sp: android.content.SharedPreferences): Int {
        val root = JSONObject(json)
        val ed = sp.edit().clear()
        for (k in root.keys()) {
            val e = root.getJSONObject(k)
            when (e.getString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "ss" -> {
                    val arr = e.getJSONArray("v")
                    ed.putStringSet(k, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        ed.commit()
        return root.length()
    }

    companion object {
        const val ACTION_EXPORT = "com.axel.homeboard.EXPORT"
        const val ACTION_IMPORT = "com.axel.homeboard.IMPORT"
    }
}
