package id.helux.dsh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the first-pairing handoff bug: DSH's 303 redirect from
 * the tokenized URL to "./" used to be treated as off-host because no paired
 * authority existed yet, so the app opened the system browser with the URL
 * stripped of its token and the user saw "dsh web authentication required".
 */
class NavigationPolicyTest {

    @Test
    fun `first pairing keeps the token redirect inside the WebView`() {
        assertTrue(
            NavigationPolicy.staysInWebView(
                targetHost = "helux.web.id",
                pendingAuthority = "helux.web.id",
                pairedAuthority = null, // nothing stored yet: this was the bug
            ),
        )
    }

    @Test
    fun `a paired device keeps navigating inside its own host`() {
        assertTrue(
            NavigationPolicy.staysInWebView(
                targetHost = "helux.web.id",
                pendingAuthority = null,
                pairedAuthority = "helux.web.id",
            ),
        )
    }

    @Test
    fun `host matching is case insensitive`() {
        assertTrue(
            NavigationPolicy.staysInWebView(
                targetHost = "HELUX.Web.ID",
                pendingAuthority = null,
                pairedAuthority = "helux.web.id",
            ),
        )
    }

    @Test
    fun `pairing again to a new host follows the freshly scanned authority`() {
        assertTrue(
            NavigationPolicy.staysInWebView(
                targetHost = "new.example.com",
                pendingAuthority = "new.example.com",
                pairedAuthority = "helux.web.id",
            ),
        )
    }

    @Test
    fun `an off-host link during pairing goes to the browser`() {
        assertFalse(
            NavigationPolicy.staysInWebView(
                targetHost = "evil.example.com",
                pendingAuthority = "helux.web.id",
                pairedAuthority = null,
            ),
        )
    }

    @Test
    fun `an off-host link on a paired device goes to the browser`() {
        assertFalse(
            NavigationPolicy.staysInWebView(
                targetHost = "evil.example.com",
                pendingAuthority = null,
                pairedAuthority = "helux.web.id",
            ),
        )
    }

    @Test
    fun `an unpaired device sends everything to the browser`() {
        assertFalse(
            NavigationPolicy.staysInWebView(
                targetHost = "helux.web.id",
                pendingAuthority = null,
                pairedAuthority = null,
            ),
        )
    }

    @Test
    fun `a hostless URL is never trusted`() {
        assertFalse(
            NavigationPolicy.staysInWebView(
                targetHost = null,
                pendingAuthority = "helux.web.id",
                pairedAuthority = "helux.web.id",
            ),
        )
        assertFalse(
            NavigationPolicy.staysInWebView(
                targetHost = "",
                pendingAuthority = "helux.web.id",
                pairedAuthority = "helux.web.id",
            ),
        )
    }
}
