package com.axel.homeboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {

    /** Incrémenté à chaque retour sur l'accueil pour rafraîchir la liste des apps. */
    private val resumeTick = mutableIntStateOf(0)

    /** Heure de la dernière touche pressée (pour l'écran de veille). */
    private val lastInput = mutableLongStateOf(SystemClock.uptimeMillis())
    private val saverOn = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Un launcher ne se ferme pas avec Retour
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent { HomeScreen(resumeTick = resumeTick.intValue, lastInput = lastInput, saverOn = saverOn) }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        lastInput.longValue = SystemClock.uptimeMillis()
        saverOn.value = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput.longValue = SystemClock.uptimeMillis()
        // Écran de veille affiché : n'importe quelle touche le ferme (sans autre action)
        if (saverOn.value) {
            if (event.action == KeyEvent.ACTION_UP) saverOn.value = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/* ---------- Lancement d'apps / raccourcis ---------- */

fun Context.launchFirst(vararg intents: Intent?) {
    for (i in intents) {
        if (i == null) continue
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            i.component?.packageName?.let { Prefs.pushRecent(this, it) }
            return
        } catch (e: ActivityNotFoundException) {
            // on essaie le suivant
        } catch (e: SecurityException) {
            // on essaie le suivant
        }
    }
    Toast.makeText(this, "Impossible d'ouvrir ce raccourci", Toast.LENGTH_SHORT).show()
}

fun Context.openAppDetails(pkg: String) = launchFirst(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
)

fun Context.leanbackOrLaunch(pkg: String): Intent? =
    packageManager.getLeanbackLaunchIntentForPackage(pkg)
        ?: packageManager.getLaunchIntentForPackage(pkg)
        // dernier recours : n'importe quel écran principal de l'app
        ?: packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)
            .firstOrNull { it.activityInfo.exported }
            ?.let { Intent(Intent.ACTION_MAIN).setClassName(pkg, it.activityInfo.name) }

data class Shortcut(val emoji: String, val label: String, val action: (Context) -> Unit)

/* Écrans de réglages propres à Fire OS */
private const val FTV_SETTINGS = "com.amazon.tv.settings.v2"

private fun component(pkg: String, cls: String) = Intent().setClassName(pkg, cls)

/** Paramètres Amazon (action vue dans le journal du Fire TV). */
private fun amazonSettings() = Intent("amazon.intent.action.MAINSETTINGS")
    .addCategory("android.intent.category.MAINSETTINGS")

private fun amazonSettingsExplicit() =
    component(HomeRedirectService.AMAZON_LAUNCHER, "com.amazon.tv.launcher.ui.MainSettingsActivity")

val SHORTCUTS = listOf(
    Shortcut("⚙️", "Paramètres") {
        it.launchFirst(amazonSettings(), amazonSettingsExplicit(), Intent(Settings.ACTION_SETTINGS))
    },
    Shortcut("📶", "Wi-Fi") {
        it.launchFirst(
            component(FTV_SETTINGS, "$FTV_SETTINGS.tv.network.NetworkActivity"),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            amazonSettings(), amazonSettingsExplicit(),
        )
    },
    Shortcut("🎮", "Manettes") {
        it.launchFirst(
            component(FTV_SETTINGS, "$FTV_SETTINGS.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            amazonSettings(), amazonSettingsExplicit(),
        )
    },
    Shortcut("🌐", "Internet") {
        it.launchFirst(
            it.leanbackOrLaunch("com.amazon.cloud9"),          // Silk
            it.leanbackOrLaunch("org.mozilla.tv.firefox"),
            it.leanbackOrLaunch("com.phlox.tvwebbrowser"),     // TV Bro
        )
    },
    Shortcut("🛍️", "Appstore") {
        // Sur Fire OS 8 l'Appstore s'affiche dans l'accueil Amazon : on suspend la redirection
        HomeRedirectService.bypassAmazonHome = true
        it.launchFirst(
            it.leanbackOrLaunch("com.amazon.venezia"),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.amazon.venezia"),
        )
    },
    Shortcut("🏠", "Accueil Amazon") {
        HomeRedirectService.bypassAmazonHome = true
        it.launchFirst(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeRedirectService.AMAZON_LAUNCHER),
            it.leanbackOrLaunch(HomeRedirectService.AMAZON_LAUNCHER),
        )
    },
)
