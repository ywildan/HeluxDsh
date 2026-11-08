package id.helux.dsh

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers which hostname this device paired with.
 *
 * The launch token and the DSH cookie are never stored here: the cookie lives
 * in the WebView's own cookie jar (the same place a browser keeps it) and the
 * token is used once and discarded. This file holds no secret.
 */
class PairingStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(HeluxConfig.PREFS, Context.MODE_PRIVATE)

    /**
     * The hostname this device is paired with.
     * @return the authority, or null when the device has never paired.
     */
    fun authority(): String? =
        prefs.getString(HeluxConfig.KEY_PAIRED_AUTHORITY, null)?.takeIf { it.isNotEmpty() }

    /**
     * Whether a pairing attempt succeeded before, so the app can go straight to
     * the clean URL on the next start.
     * @return true when a previous pairing was recorded.
     */
    fun isPaired(): Boolean = authority() != null

    /**
     * Record a successful pairing for this hostname.
     * @param authority hostname the QR belonged to.
     */
    fun markPaired(authority: String) {
        prefs.edit().putString(HeluxConfig.KEY_PAIRED_AUTHORITY, authority.lowercase()).apply()
    }

    /**
     * Forget the pairing so the next start asks for a QR again.
     */
    fun clear() {
        prefs.edit().remove(HeluxConfig.KEY_PAIRED_AUTHORITY).apply()
    }
}
