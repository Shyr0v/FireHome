package com.axel.homeboard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Fire OS ne permet pas de changer le launcher par défaut. Ce service :
 *  1. écoute l'appui sur Home (message système "homekey") et ouvre FireHome ;
 *  2. détecte quand on retombe sur l'écran d'accueil Amazon en quittant une app
 *     (touche Retour) et ouvre FireHome.
 * Les autres écrans Amazon (paramètres, Appstore…) ne sont pas touchés.
 */
class HomeRedirectService : AccessibilityService() {

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
        private const val APPSTORE = "com.amazon.venezia"

        /** Paquets qui s'affichent par-dessus sans être une vraie app au premier plan. */
        private val IGNORED = setOf(
            "com.android.systemui",
            "com.amazon.tv.ime",
            "com.amazon.tv.quicksettings.ui",
            "com.amazon.tv.notificationcenter",
            "android",
        )

        /** Mis à true par les raccourcis « Appstore » / « Accueil Amazon ». Annulé par Home. */
        @Volatile
        var bypassAmazonHome = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastRedirect = 0L

    /** Dernier écran (Activity) de l'interface Amazon réellement affiché. */
    private var amazonActivity: String? = null

    private val homeKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = intent.getStringExtra("reason") ?: return
            if (reason == "homekey") {
                bypassAmazonHome = false
                // petit délai pour passer après l'ouverture de l'accueil Amazon
                handler.postDelayed({ openFireHome(force = true) }, 250)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this, homeKeyReceiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Le service démarre avec le Fire TV : on ouvre FireHome à la place de l'accueil Amazon
        if (SystemClock.elapsedRealtime() < 5 * 60_000L) {
            handler.postDelayed({ openFireHome(force = true) }, 3000)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(homeKeyReceiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORED) return
        val cls = event.className?.toString()

        if (pkg == AMAZON_LAUNCHER) {
            // Certains événements portent le nom d'une vue (android.widget.*) : on garde le dernier écran connu
            if (cls != null && cls.startsWith("com.amazon.")) amazonActivity = cls
            if (amazonActivity == AMAZON_HOME && !bypassAmazonHome) openFireHome()
        } else if (pkg != packageName && pkg != APPSTORE) {
            if (!pkg.startsWith("com.amazon.tv.")) Prefs.pushRecent(this, pkg)
            // Une autre app a été ouverte : le prochain retour à l'accueil revient sur FireHome
            bypassAmazonHome = false
        }
    }

    private fun openFireHome(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRedirect < 800) return
        lastRedirect = now
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        )
    }

    override fun onInterrupt() = Unit
}
