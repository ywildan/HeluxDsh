package id.helux.dsh

import java.net.URI

/**
 * Result of inspecting a scanned QR payload.
 */
sealed class ScanResult {
    /** A launch URL for this app's own hostname, ready to load once. */
    data class Launch(val url: String) : ScanResult()

    /** The payload is not an acceptable launch URL; [reason] is user-facing. */
    data class Rejected(val reason: String) : ScanResult()
}

/**
 * Validates a scanned string before it is ever handed to a WebView.
 *
 * Rules, in order: absolute `https`, exact hostname match, no userinfo, no
 * non-default port, path `/`, and exactly one non-empty `token` parameter.
 * Everything else is rejected, so a QR that points at another site cannot
 * silently pair the app with an attacker.
 */
object LaunchUrlValidator {

    /**
     * Inspect a scanned payload.
     * @param raw the decoded QR text.
     * @return [ScanResult.Launch] when acceptable, otherwise a rejection reason.
     */
    fun validate(raw: String): ScanResult {
        val text = raw.trim()
        if (text.isEmpty()) return ScanResult.Rejected("The QR code is empty.")

        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return ScanResult.Rejected("This is not a valid URL.")
        }

        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return ScanResult.Rejected("The pairing code must use https.")
        }
        if (uri.userInfo != null) {
            return ScanResult.Rejected("The pairing code contains credentials and was rejected.")
        }
        if (!uri.host.equals(HeluxConfig.HOST, ignoreCase = true)) {
            return ScanResult.Rejected("This QR code is not for ${HeluxConfig.HOST}.")
        }
        if (uri.port != -1 && uri.port != 443) {
            return ScanResult.Rejected("The pairing code uses an unexpected port.")
        }

        val path = uri.rawPath ?: ""
        if (path.isNotEmpty() && path != "/") {
            return ScanResult.Rejected("The pairing code must point at the application root.")
        }

        val tokens = queryParameters(uri.rawQuery).filter { it.first == HeluxConfig.TOKEN_PARAM }
        if (tokens.isEmpty()) {
            return ScanResult.Rejected("The pairing code has no launch token.")
        }
        if (tokens.size > 1) {
            return ScanResult.Rejected("The pairing code contains more than one launch token.")
        }
        val token = tokens.first().second
        if (token.isEmpty()) {
            return ScanResult.Rejected("The pairing code has an empty launch token.")
        }

        // Rebuild from scratch so nothing but host + token reaches the WebView.
        val url = "https://${HeluxConfig.HOST}/?${HeluxConfig.TOKEN_PARAM}=${encodeQueryValue(token)}"
        return ScanResult.Launch(url)
    }

    /**
     * Split a raw query string into decoded key/value pairs.
     * @param rawQuery the part after `?`, possibly null.
     * @return decoded pairs in order.
     */
    private fun queryParameters(rawQuery: String?): List<Pair<String, String>> {
        if (rawQuery.isNullOrEmpty()) return emptyList()
        return rawQuery.split('&').mapNotNull { segment ->
            if (segment.isEmpty()) return@mapNotNull null
            val index = segment.indexOf('=')
            val rawKey = if (index == -1) segment else segment.substring(0, index)
            val rawValue = if (index == -1) "" else segment.substring(index + 1)
            decode(rawKey) to decode(rawValue)
        }
    }

    /**
     * Percent-decode a query component.
     * @param value encoded text.
     * @return decoded text.
     */
    private fun decode(value: String): String =
        try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

    /**
     * Percent-encode a token so it survives a query round trip unchanged.
     * @param value raw token text.
     * @return encoded text.
     */
    private fun encodeQueryValue(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
