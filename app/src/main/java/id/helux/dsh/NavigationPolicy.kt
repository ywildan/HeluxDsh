package id.helux.dsh

/**
 * Decide whether a navigation stays inside the WebView.
 *
 * This exists because of a pairing bug: DSH answers the tokenized URL with a
 * 303 redirect to `./`, and [MainActivity][MainActivity]'s WebView client asked
 * the *stored* paired authority whether that redirect belonged to us. During
 * the very first pairing nothing is stored yet, so the redirect looked like an
 * off-host link and was handed to the system browser - which then loaded the
 * clean URL without its token and showed "dsh web authentication required".
 *
 * The authority just scanned therefore has to count as trusted before the
 * redirect is evaluated, not only after [PairingStore.markPaired] runs.
 */
object NavigationPolicy {

    /**
     * Whether [targetHost] belongs to this app's server.
     *
     * @param targetHost host of the navigation being evaluated.
     * @param pendingAuthority authority from the scan that is still in flight,
     *   or null when no pairing is in progress.
     * @param pairedAuthority authority stored from an earlier pairing, or null
     *   on a device that has never paired.
     * @return true when the navigation must stay in the WebView.
     */
    fun staysInWebView(
        targetHost: String?,
        pendingAuthority: String?,
        pairedAuthority: String?,
    ): Boolean {
        if (targetHost.isNullOrEmpty()) return false
        // The in-flight scan wins: during pairing it is the only authority that
        // exists, and after "Pair again" it is the one the user just chose.
        val expected = pendingAuthority ?: pairedAuthority ?: return false
        return targetHost.equals(expected, ignoreCase = true)
    }
}
