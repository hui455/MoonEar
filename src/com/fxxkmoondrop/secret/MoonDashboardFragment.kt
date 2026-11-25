package com.hui455.moonear

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment

/** Uses MoonEar's existing connection owner, discovery, ANC mapping and battery store. */
class MoonDashboardFragment : Fragment() {
    private var host: FrameLayout? = null
    private val ui = Handler(Looper.getMainLooper())
    private var active = false
    private var rendered: MoonEarPages.DeviceState? = null
    private val client get() = GaiaBleClient.getInstance()
    private val callback = object : GaiaBleClient.Callback {
        override fun onConnected(address: String) { ui.post { if (active) { refresh(); render() } } }
        override fun onDisconnected(address: String) { ui.post { if (active) render() } }
        override fun onBatteryLevel(batteryId: Int, level: Int) {
            BatteryStore.setGaiaLevel(client.deviceAddress, batteryId, level)
            ui.post { if (active) render() }
        }
        override fun onAncMode(mode: Int) { ui.post { if (active) render() } }
        override fun onError(message: String) { AppLog.d("MoonDashboard", message) }
    }
    private val poll = object : Runnable {
        override fun run() { if (active) { render(); ui.postDelayed(this, 1500) } }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        return FrameLayout(context).also { frame ->
            host = frame
            frame.setBackgroundColor(MoonEarPages.colors(context).bg)
            frame.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            frame.requestApplyInsets()
            render(true)
        }
    }

    override fun onStart() {
        super.onStart()
        active = true
        client.setCallback(callback)
        refresh()
        ui.post(poll)
    }

    private fun refresh() {
        val context = context ?: return
        if (!bluetoothGranted(context)) return
        if (client.isGaiaReady()) {
            AncBridge.fetchAncMode()
            client.fetchBatteryLevels()
        } else if (!client.isConnecting()) {
            val address = HeadsetGate.getConnectedMac(context) ?: return
            val generation = client.setCallback(callback)
            val app = context.applicationContext
            Thread { if (active) client.forceReconnect(app, address, callback, generation) }.start()
        }
    }

    private fun render(force: Boolean = false) {
        val context = context ?: return
        val frame = host ?: return
        val connected = client.isConnected()
        val address = client.deviceAddress
        val state = MoonEarPages.DeviceState(
            name = client.getConnectedDeviceName() ?: "你的水月雨耳机",
            connected = connected, ready = client.isGaiaReady(),
            left = if (connected) BatteryStore.getGaiaLeft(address) else -1,
            right = if (connected) BatteryStore.getGaiaRight(address) else -1,
            mode = if (connected) AncBridge.getCurrentMode() else -1,
            modes = if (client.isGaiaReady()) client.supportedUiModes().toList() else emptyList(),
        )
        if (!force && state == rendered) return
        rendered = state
        val oldScroll = (frame.getChildAt(0) as? android.widget.ScrollView)?.scrollY ?: 0
        val view = MoonEarPages.device(context, state,
            onGestures = { startActivity(Intent(context, GestureSettingsActivity::class.java)) },
            onEq = { startActivity(Intent(context, EqEditorActivity::class.java)) },
            onNoise = { mode -> if (client.isGaiaReady()) AncBridge.setAncMode(mode, "custom-dashboard") },
            onReconnect = {
                if (!bluetoothGranted(context)) startActivity(Intent(context, PermissionActivity::class.java))
                else if (HeadsetGate.getConnectedMac(context) == null && !client.isConnected()) {
                    Toast.makeText(context, "请先在蓝牙设置中连接耳机", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                } else refresh()
            })
        frame.removeAllViews(); frame.addView(view)
        view.post { view.scrollTo(0, oldScroll) }
    }

    override fun onStop() {
        active = false
        ui.removeCallbacksAndMessages(null)
        client.releaseCallback(callback)
        super.onStop()
    }
    private fun bluetoothGranted(context: android.content.Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
    override fun onDestroyView() { host = null; rendered = null; super.onDestroyView() }
}
