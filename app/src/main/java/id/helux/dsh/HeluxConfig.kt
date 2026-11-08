package id.helux.dsh

/**
 * Fixed values shared by the pairing, scan-validation, and WebView code.
 *
 * The app is deliberately single-server: it only ever pairs with the hostname
 * below. A scan that points anywhere else is rejected, so a malicious QR can
 * never redirect the app to a look-alike origin.
 */
object HeluxConfig {
    /** Public hostname served by the user's named Cloudflare Tunnel. */
    const val HOST = "helux.web.id"

    /** Clean application root the app opens once the cookie is minted. */
    const val HOME_URL = "https://$HOST/"

    /** Query parameter DSH uses for the one-time launch token. */
    const val TOKEN_PARAM = "token"

    /** Android preference file holding the non-secret pairing marker. */
    const val PREFS = "helux-dsh-pairing"

    /** Key storing the authority that was paired (never the token). */
    const val KEY_PAIRED_AUTHORITY = "paired-authority"
}
