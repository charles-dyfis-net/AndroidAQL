package io.github.androidaql

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.View
import android.widget.Button
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.androidaql.service.AqlCollectorService
import io.github.androidaql.ui.MainActivity
import io.github.androidaql.ui.SettingsScreenDialog
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    private var hadScreenshotAutomationPref = false
    private var prevScreenshotAutomationPref = false
    private var hadDeveloperModePref = false
    private var prevDeveloperModePref = false
    private var hadDemoDataPref = false
    private var prevDemoDataPref = false

    private var boundService: AqlCollectorService? = null
    private var testServiceConnection: ServiceConnection? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        hadScreenshotAutomationPref = prefs.contains(KEY_SCREENSHOT_AUTOMATION)
        prevScreenshotAutomationPref = prefs.getBoolean(KEY_SCREENSHOT_AUTOMATION, false)
        hadDeveloperModePref = prefs.contains(KEY_DEVELOPER_MODE)
        prevDeveloperModePref = prefs.getBoolean(KEY_DEVELOPER_MODE, false)
        hadDemoDataPref = prefs.contains(KEY_DEMO_DATA)
        prevDemoDataPref = prefs.getBoolean(KEY_DEMO_DATA, false)

        prefs.edit()
            .putBoolean(KEY_SCREENSHOT_AUTOMATION, true)
            .putBoolean(KEY_DEVELOPER_MODE, false)
            .putBoolean(KEY_DEMO_DATA, false)
            .commit()
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testServiceConnection?.let { conn ->
            runCatching { context.unbindService(conn) }
        }
        testServiceConnection = null
        boundService = null
        runCatching { context.stopService(Intent(context, AqlCollectorService::class.java)) }

        val prefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (hadScreenshotAutomationPref) {
            editor.putBoolean(KEY_SCREENSHOT_AUTOMATION, prevScreenshotAutomationPref)
        } else {
            editor.remove(KEY_SCREENSHOT_AUTOMATION)
        }
        if (hadDeveloperModePref) {
            editor.putBoolean(KEY_DEVELOPER_MODE, prevDeveloperModePref)
        } else {
            editor.remove(KEY_DEVELOPER_MODE)
        }
        if (hadDemoDataPref) {
            editor.putBoolean(KEY_DEMO_DATA, prevDemoDataPref)
        } else {
            editor.remove(KEY_DEMO_DATA)
        }
        editor.commit()
    }

    @Test
    fun testMainActivityLaunchTabNavigationRecreationAndSettingsDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = bindCollectorService(context)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertDecorAndSystemBars(activity)

                val decorView = activity.window.decorView
                val tripsBtn = findExactButton(decorView, "Trips")
                assertNotNull("Expected 'Trips' tab button to be rendered", tripsBtn)
                assertTrue("Expected 'Trips' tab button click to succeed", tripsBtn!!.performClick())

                val nowBtn = findExactButton(decorView, "Now")
                assertNotNull("Expected 'Now' tab button to be rendered", nowBtn)
                assertTrue("Expected 'Now' tab button click to succeed", nowBtn!!.performClick())

                val helpBtn = findExactButton(decorView, "❓ Help")
                assertNotNull("Expected Help button to be rendered", helpBtn)
                helpBtn!!.performClick()

                val settingsBtn = findExactButton(decorView, "⚙️ Settings")
                assertNotNull("Expected Settings button to be rendered", settingsBtn)
                settingsBtn!!.performClick()

                val settingsDialog = SettingsScreenDialog(
                    context = activity,
                    service = service,
                    callbacks = noOpSettingsCallbacks()
                )
                settingsDialog.show()
                settingsDialog.dismiss()
            }

            scenario.recreate()

            scenario.onActivity { recreatedActivity ->
                assertDecorAndSystemBars(recreatedActivity)
                assertNotNull(findExactButton(recreatedActivity.window.decorView, "Now"))
                assertNotNull(findExactButton(recreatedActivity.window.decorView, "Trips"))
            }
        }
    }

    private fun bindCollectorService(context: Context): AqlCollectorService {
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val localBinder = binder as? AqlCollectorService.LocalBinder
                if (localBinder != null) {
                    boundService = localBinder.getService()
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        testServiceConnection = conn
        val bound = context.bindService(
            Intent(context, AqlCollectorService::class.java),
            conn,
            Context.BIND_AUTO_CREATE
        )
        assertTrue("Expected bindService to AqlCollectorService to return true", bound)
        assertTrue(
            "Timed out waiting for AqlCollectorService connection",
            latch.await(5, TimeUnit.SECONDS)
        )
        val svc = boundService
        assertNotNull("Expected non-null AqlCollectorService after bind", svc)
        return svc!!
    }

    private fun assertDecorAndSystemBars(activity: MainActivity) {
        val decorView = activity.window.decorView
        assertNotNull("Expected MainActivity window.decorView to be initialized", decorView)
        assertTrue(
            "Expected decorView to be attached to a window",
            decorView.isAttachedToWindow || decorView.rootView != null
        )
        val insetsController = WindowCompat.getInsetsController(activity.window, decorView)
        assertFalse(insetsController.isAppearanceLightStatusBars)
        assertFalse(insetsController.isAppearanceLightNavigationBars)
    }

    private fun findExactButton(root: View, label: String): Button? {
        val matches = ArrayList<View>()
        root.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT)
        return matches.firstOrNull { it is Button && it.text.toString() == label } as? Button
    }

    private fun noOpSettingsCallbacks() = object : SettingsScreenDialog.Callbacks {
        override fun isDeveloperModeEnabled(): Boolean = false
        override fun setDeveloperModeEnabled(enabled: Boolean) {}
        override fun isDemoDataEnabled(): Boolean = false
        override fun setDemoDataEnabled(enabled: Boolean) {}
        override fun getDropoutGracePeriodSec(): Long = 45L
        override fun setDropoutGracePeriodSec(sec: Long) {}
        override fun onAddWifiPlaceRequested(onSaved: () -> Unit) {}
        override fun onAddSegmentPresetRequested(onSaved: () -> Unit) {}
        override fun onEditAlertThresholdsRequested(onSaved: () -> Unit) {}
        override fun onShowHelpRequested() {}
        override fun onShareDiagnosticLogRequested() {}
        override fun onShowPrivacyDisclosureRequested() {}
        override fun onEnablePermissionsRequested() {}
        override fun onSettingsDismissed() {}
    }

    companion object {
        private const val UI_PREFS_NAME = "aql_ui_prefs"
        private const val KEY_SCREENSHOT_AUTOMATION = "screenshot_automation_active"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_DEMO_DATA = "demo_data_enabled"
    }
}
