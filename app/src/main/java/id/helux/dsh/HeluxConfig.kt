package id.helux.dsh

/**
 * Fixed values shared by the pairing, scan-validation, and WebView code.
 *
 * The app is deliberately single-server *per install*: the first QR you scan
 * chooses the hostname, and every later scan must match it. That keeps one APK
 * usable on anyone's domain without letting a hostile QR redirect a device that
 * is already paired.
 */
object HeluxConfig {
    /** Query parameter DSH uses for the one-time launch token. */
    const val TOKEN_PARAM = "token"

    /** Android preference file holding the non-secret pairing marker. */
    const val PREFS = "helux-dsh-pairing"

    /** Key storing the authority that was paired (never the token). */
    const val KEY_PAIRED_AUTHORITY = "paired-authority"

    /**
     * Build the clean application root for a paired authority.
     * @param authority hostname that was paired.
     * @return the https root the WebView opens once a cookie exists.
     */
    fun homeUrl(authority: String): String = "https://$authority/"
}
