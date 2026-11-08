package id.helux.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard rails for QR validation.
 *
 * These cases decide which URL the WebView is allowed to load, so a look-alike
 * host or a smuggled second token must be rejected rather than sanitized.
 *
 * The app binds its hostname on the first successful scan (trust on first use),
 * so every case is checked in both modes: unpaired (any valid host is accepted)
 * and paired (only the recorded host is).
 */
class LaunchUrlValidatorTest {

    private val token = "7Hg3kLm2Qp9sT4vW6xY8zA1bC5dE0fJ2nM4oP6rS8t"
    private val host = "helux.web.id"

    /**
     * Assert that a payload is accepted and yields the canonical URL.
     * @param payload scanned text.
     * @param expectedHost hostname this device is paired with, or null.
     * @param expectedAuthority hostname the payload should resolve to.
     */
    private fun assertLaunch(payload: String, expectedHost: String? = host, expectedAuthority: String = host) {
        val result = LaunchUrlValidator.validate(payload, expectedHost)
        assertTrue("expected a launch result for: $payload", result is ScanResult.Launch)
        val launch = result as ScanResult.Launch
        assertEquals("https://$expectedAuthority/?token=$token", launch.url)
        assertEquals(expectedAuthority, launch.authority)
    }

    /**
     * Assert that a payload is rejected, optionally matching part of the reason.
     * @param payload scanned text.
     * @param reasonFragment expected substring of the reason.
     * @param expectedHost hostname this device is paired with, or null.
     */
    private fun assertRejected(payload: String, reasonFragment: String? = null, expectedHost: String? = host) {
        val result = LaunchUrlValidator.validate(payload, expectedHost)
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
    fun firstPairingAcceptsAnyHostnameSoOneApkServesEveryDeployment() {
        assertLaunch("https://dsh.example.com/?token=$token", expectedHost = null, expectedAuthority = "dsh.example.com")
        assertLaunch("https://tunnel.example.org/?token=$token", expectedHost = null, expectedAuthority = "tunnel.example.org")
    }

    @Test
    fun firstPairingStillRejectsAnUnsafePayload() {
        assertRejected("http://dsh.example.com/?token=$token", "https", expectedHost = null)
        assertRejected("https://dsh.example.com/some/path?token=$token", "root", expectedHost = null)
        assertRejected("https://dsh.example.com/?token=", "empty", expectedHost = null)
        assertRejected("https://dsh.example.com:8443/?token=$token", "port", expectedHost = null)
    }

    @Test
    fun aPairedDeviceRefusesToBeMovedToAnotherServer() {
        assertRejected("https://attacker.example.com/?token=$token", "paired with helux.web.id")
        assertRejected("https://attacker.example.com/?token=$token", "Pair again")
    }

    @Test
    fun aPairedDeviceAcceptsItsOwnHostOnly() {
        assertLaunch("https://helux.web.id/?token=$token", expectedHost = "helux.web.id")
        assertLaunch("https://HELUX.WEB.ID/?token=$token", expectedHost = "helux.web.id")
        // The stored authority is lowercase, so a mixed-case record still matches.
        assertLaunch("https://helux.web.id/?token=$token", expectedHost = "HeLuX.Web.Id")
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
    fun rejectsLookAlikeHostsOnAPairedDevice() {
        assertRejected("https://helux.web.id.evil.com/?token=$token", "paired with helux.web.id")
        assertRejected("https://evil-helux.web.id/?token=$token", "paired with helux.web.id")
        assertRejected("https://helux.web.id@evil.com/?token=$token")
        assertRejected("https://sub.helux.web.id/?token=$token", "paired with helux.web.id")
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
        val result = LaunchUrlValidator.validate("https://helux.web.id/?token=a%2Bb%20c", host)
        assertTrue(result is ScanResult.Launch)
        val url = (result as ScanResult.Launch).url
        assertTrue("plus signs must survive encoding: $url", url.contains("a%2Bb%20c"))
    }
}
