package com.hui455.moonear

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.widget.ScrollView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h1150dp-xhdpi")
class MoonEarScreenshotTest {
    @Test fun deviceLight() = capture(false, false, "device-light")
    @Test fun deviceDark() = capture(true, false, "device-dark")
    @Test fun gesturesLight() = capture(false, true, "gestures-light")
    @Test fun gesturesDark() = capture(true, true, "gestures-dark")
    @Test fun gesturesUnavailable() = capture(false, true, "gestures-unavailable", unavailable = true)

    private fun capture(dark: Boolean, gestures: Boolean, name: String, unavailable: Boolean = false) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_FxxkMoondrop)
        activity.getSharedPreferences("cfg", 0).edit().putInt("theme_mode", if (dark) 2 else 1).commit()
        val config = Configuration(activity.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        val view = if (gestures) {
            // Fixture values are used only in JVM tests; production screens always read the device.
            val state = if (unavailable) TouchSession.State(TouchSession.Phase.ERROR,
                message = "暂时无法读取这副耳机的手势设置")
            else TouchSession.State(TouchSession.Phase.READY,
                TouchConfiguration.parse(byteArrayOf(0, 0x23, 0x66, 0x77, 0)), saved = false,
                defaults = TouchConfiguration.parse(byteArrayOf(0x11, 0x23, 0x66, 0x77, 0)),
                backup = TouchConfiguration.parse(byteArrayOf(0x11, 0x23, 0x66, 0x77, 0)))
            MoonEarPages.gestures(activity, state, TouchConfiguration.Ear.LEFT, {}, {}, {}, {}, {})
        } else MoonEarPages.device(activity, MoonEarPages.DeviceState(
            "MOONDROP SPACE TRAVEL 2 ULTRA", true, true, 85, 90, 1, listOf(0, 1, 2, 3)), {}, {}, {}, {})
        activity.setContentView(view)
        val width = (412 * activity.resources.displayMetrics.density).toInt()
        val height = (1150 * activity.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        view.captureRoboImage("screenshots/custom/$name.png")
        controller.pause().stop().destroy()
    }
}
