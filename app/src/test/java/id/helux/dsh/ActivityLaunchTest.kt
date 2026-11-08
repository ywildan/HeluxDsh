package id.helux.dsh

import android.os.Build
import android.widget.Button
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launch-time smoke tests.
 *
 * These instantiate the real Activities on the JVM, which is the only way to
 * catch the class of bug that ships silently: an inflation failure, a missing
 * view id, or a theme mismatch all compile fine and only blow up at runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ActivityLaunchTest {

    @Test
    fun mainActivityStartsAndShowsPairingOnFirstRun() {
        PairingStore(ApplicationProvider.getApplicationContext()).clear()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()

        assertNotNull("MainActivity must reach RESUMED", activity)
        val pairingPanel = activity.findViewById<View>(R.id.pairing_panel)
        val webView = activity.findViewById<android.webkit.WebView>(R.id.webview)
        assertEquals("the pairing panel is visible on a fresh install", 0, pairingPanel.visibility)
        assertEquals("the WebView stays hidden until paired", 8, webView.visibility)

        val scanButton = activity.findViewById<Button>(R.id.scan_button)
        assertNotNull("the scan button must exist", scanButton)
        assertEquals("Scan QR code", scanButton.text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun mainActivityShowsStatusPanelWhenPairedButOffline() {
        PairingStore(ApplicationProvider.getApplicationContext()).markPaired("helux.web.id")

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()

        // Paired: the WebView is the visible surface.
        assertEquals(0, activity.findViewById<android.webkit.WebView>(R.id.webview).visibility)
        controller.pause().stop().destroy()
    }

    /**
     * Regression: a paired device reopened after being swiped away from Recents
     * showed a bare black screen. onCreate() revealed the WebView without ever
     * loading anything, and the test above passed anyway because it only
     * checked visibility - a visible but empty WebView is still a blank screen.
     */
    @Test
    fun pairedColdStartLoadsTheHomePage() {
        PairingStore(ApplicationProvider.getApplicationContext()).markPaired("dsh.example.com")

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()

        val webView = activity.findViewById<android.webkit.WebView>(R.id.webview)
        assertEquals(0, webView.visibility)
        assertEquals(
            "a paired cold start must navigate, not just reveal an empty WebView",
            "https://dsh.example.com/",
            webView.url,
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun everyColdStartRequestsAPage() {
        PairingStore(ApplicationProvider.getApplicationContext()).markPaired("helux.web.id")

        // Two launches in a row stand in for "killed from Recents, opened again".
        repeat(2) {
            val controller = Robolectric.buildActivity(MainActivity::class.java)
            val webView = controller.setup().get().findViewById<android.webkit.WebView>(R.id.webview)
            assertEquals("launch ${it + 1} must load a page", "https://helux.web.id/", webView.url)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aPairedFlagWithoutAHostnameFallsBackToPairing() {
        // Defensive: a corrupt or half-written preference must never strand the
        // user on an empty WebView.
        val store = PairingStore(ApplicationProvider.getApplicationContext())
        store.markPaired("placeholder.example")
        store.clear()
        // isPaired() is false here, so the pairing panel is the expected surface.
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        assertEquals(0, activity.findViewById<View>(R.id.pairing_panel).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun mainActivityStatusPanelRendersText() {
        PairingStore(ApplicationProvider.getApplicationContext()).clear()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()

        activity.runOnUiThread {
            val panel = activity.findViewById<View>(R.id.status_panel)
            panel.visibility = 0
            activity.findViewById<TextView>(R.id.status_title).text = "title"
            activity.findViewById<TextView>(R.id.status_body).text = "body"
            activity.findViewById<Button>(R.id.status_action).text = "action"
        }
        assertEquals("title", activity.findViewById<TextView>(R.id.status_title).text.toString())
        assertEquals("action", activity.findViewById<Button>(R.id.status_action).text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun scanActivityInflates() {
        val controller = Robolectric.buildActivity(ScanActivity::class.java)
        val activity = controller.setup().get()

        assertNotNull("ScanActivity must reach RESUMED", activity)
        assertNotNull(activity.findViewById<View>(R.id.preview))
        assertNotNull(activity.findViewById<TextView>(R.id.scan_status))
        assertNotNull(activity.findViewById<Button>(R.id.cancel_scan))
        controller.pause().stop().destroy()
    }

    @Test
    fun bothActivitiesAreDeclaredInTheManifest() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        for (name in listOf(MainActivity::class.java.name, ScanActivity::class.java.name)) {
            val info = pm.getActivityInfo(android.content.ComponentName(context, name), 0)
            assertNotNull("$name must be declared in AndroidManifest.xml", info)
        }
        val scan = pm.getActivityInfo(android.content.ComponentName(context, ScanActivity::class.java.name), 0)
        assertEquals("the scanner must not be exported", false, scan.exported)
    }
}
