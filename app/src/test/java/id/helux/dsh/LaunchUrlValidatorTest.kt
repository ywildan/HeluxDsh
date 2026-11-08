package id.helux.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard rails for QR validation.
 *
 * These cases matter more than they look: any acceptance here decides which URL
 * the WebView is allowed to load, so a look-alike host or a smuggled second
 * token must be rejected rather than sanitized.
 */
class LaunchUrlValidatorTest {

    private val token = "7Hg3kLm2QpR9sT4vW6xY8zA1bC5dE0fJ2nM4oP6rS8t"

    /**
     * Assert that a payload is accepted and yields the canonical URL.
     * @param payload scanned text.
     */
    private fun assertLaunch(payload: String) {
        val result = LaunchUrlValidator.validate(payload)
        assertTrue("expected a launch result for: $payload", result is ScanResult.Launch)
        assertEquals("https://helux.web.id/?token=$token", (result as ScanResult.Launch).url)
    }

    /**
     * Assert that a payload is rejected, optionally matching part of the reason.
     * @param payload scanned text.
     * @param reasonFragment expected substring of the reason.
     */
    private fun assertRejected(payload: String, reasonFragment: String? = null) {
        val result = LaunchUrlValidator.validate(payload)
        assertTrue("expected a rejection for: $payload", result is ScanResult.Rejected)
        if (reasonFragment != null) {
            assertTrue(
                "reason for $payload should mention $reasonFragment, was ${(result as ScanResult.Rejected).reason}",
                result.reason.contains(reasonFragment),
            )
        }
    }

    @Test
    fun acceptsThePanelLaunchUrl() {
        assertLaunch("https://helux.web.id/?token=$token")
    }

    @Test
    fun acceptsTrailingRootPathAndSurroundingWhitespace() {
        assertLaunch("  https://helux.web.id/?token=$token\n")
        assertLaunch("https://helux.web.id/?token=$token")
    }

    @Test
    fun acceptsUppercaseHostAndDefaultPort() {
        assertLaunch("https://HELUX.WEB.ID/?token=$token")
        assertLaunch("https://helux.web.id:443/?token=$token")
    }

    @Test
    fun acceptsExtraQueryParametersAndKeepsOnlyTheToken() {
        assertLaunch("https://helux.web.id/?from=qr&token=$token&ref=panel")
    }

    @Test
    fun rejectsEmptyPayload() {
        assertRejected("")
        assertRejected("   ")
    }

    @Test
    fun rejectsNonHttpsSchemes() {
        assertRejected("http://helux.web.id/?token=$token", "https")
        assertRejected("javascript:alert(1)//helux.web.id/?token=$token")
        assertRejected("file:///data/local/tmp/x.html")
    }

    @Test
    fun rejectsLookAlikeHosts() {
        assertRejected("https://helux.web.id.evil.com/?token=$token", "not for helux.web.id")
        assertRejected("https://evil-helux.web.id/?token=$token", "not for helux.web.id")
        assertRejected("https://helux.web.id@evil.com/?token=$token")
        assertRejected("https://sub.helux.web.id/?token=$token", "not for helux.web.id")
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        assertRejected("https://user:pass@helux.web.id/?token=$token", "credentials")
    }

    @Test
    fun rejectsUnexpectedPorts() {
        assertRejected("https://helux.web.id:8443/?token=$token", "port")
    }

    @Test
    fun rejectsNonRootPaths() {
        assertRejected("https://helux.web.id/some/deep/path?token=$token", "root")
    }

    @Test
    fun rejectsMissingOrEmptyToken() {
        assertRejected("https://helux.web.id/", "no launch token")
        assertRejected("https://helux.web.id/?token=", "empty")
        assertRejected("https://helux.web.id/?other=$token", "no launch token")
    }

    @Test
    fun rejectsDuplicateTokens() {
        // Request smuggling: two tokens would let the host see a different value
        // than the panel intends, so the whole payload is refused.
        assertRejected("https://helux.web.id/?token=$token&token=other", "more than one")
    }

    @Test
    fun rejectsGarbage() {
        // A space makes it a URI syntax error; a scheme-less host fails the
        // https check. Both must be refused.
        assertRejected("not a url", "valid URL")
        assertRejected("helux.web.id/?token=$token", "https")
    }

    @Test
    fun percentEncodesTheTokenRoundTrip() {
        val result = LaunchUrlValidator.validate("https://helux.web.id/?token=a%2Bb%20c")
        assertTrue(result is ScanResult.Launch)
        val url = (result as ScanResult.Launch).url
        assertTrue("plus signs must survive encoding: $url", url.contains("a%2Bb%20c"))
    }
}
