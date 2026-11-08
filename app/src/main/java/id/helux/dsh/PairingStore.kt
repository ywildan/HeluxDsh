package id.helux.dsh

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers only that this device paired, and with which authority.
 *
 * The launch token and the DSH cookie are never stored here: the cookie lives
 * in the WebView's own cookie jar (the same place a browser keeps it) and the
 * token is used once and discarded. This file holds no secret.
 */
class PairingStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(HeluxConfig.PREFS, Context.MODE_PRIVATE)

    /**
     * Whether a pairing attempt succeeded before, so the app can go straight to
     * the clean URL on the next start.
     * @return true when a previous pairing was recorded.
     */
    fun isPaired(): Boolean =
        prefs.getString(HeluxConfig.KEY_PAIRED_AUTHORITY, null) == HeluxConfig.HOST

    /**
     * Record a successful pairing for this hostname.
     */
    fun markPaired() {
        prefs.edit().putString(HeluxConfig.KEY_PAIRED_AUTHORITY, HeluxConfig.HOST).apply()
    }

    /**
     * Forget the pairing so the next start asks for a QR again.
     */
    fun clear() {
        prefs.edit().remove(HeluxConfig.KEY_PAIRED_AUTHORITY).apply()
    }
}
