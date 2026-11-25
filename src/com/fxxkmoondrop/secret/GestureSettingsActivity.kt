package com.hui455.moonear

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout

class GestureSettingsActivity : BaseActivity() {
    private val client get() = GaiaBleClient.getInstance()
    private val session get() = client.gestures
    private var ear = TouchConfiguration.Ear.LEFT
    private lateinit var host: FrameLayout
    private var active = false
    private var previewDialog: AlertDialog? = null
    private val stateListener: (TouchSession.State) -> Unit = { state ->
        if (active) {
            render()
            if (state.phase == TouchSession.Phase.PREVIEW) showPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ear = savedInstanceState?.getString("ear")?.let { runCatching { TouchConfiguration.Ear.valueOf(it) }.getOrNull() }
            ?: TouchConfiguration.Ear.LEFT
        host = FrameLayout(this)
        setContentView(host)
        window.setDecorFitsSystemWindows(false)
        val palette = MoonEarPages.colors(this)
        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg
        window.insetsController?.setSystemBarsAppearance(
            if (ThemeUtil.isDark(this)) 0 else 24, 24)
        host.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        host.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        active = true
        client.retainConnection(this)
        session.listener = stateListener
        val address = HeadsetGate.getConnectedMac(this) ?: client.deviceAddress
        if (address != null) {
            val backups = TouchBackupStore(this, address)
            session.loadBackup(backups.load())
            session.backupWriter = { backups.saveFirst(it) }
        } else {
            session.backupWriter = { error("No connected headset address") }
        }
        render()
        if (client.isGaiaReady()) session.refresh()
        else session.fail("请返回设备页连接耳机后，再读取设置")
    }

    private fun render() {
        if (!::host.isInitialized) return
        val scrollY = host.getChildAt(0)?.scrollY ?: 0
        val page = MoonEarPages.gestures(this, session.state, ear,
            onBack = { finish() },
            onEar = { ear = it; render() },
            onRefresh = {
                if (client.isGaiaReady()) session.refresh()
                else session.fail("耳机已断开，请返回设备页重新连接")
            },
            onPreset = { showPreset() },
            onChoose = { showActions(it) },
            onFactory = { if (client.isGaiaReady()) session.restoreFactory() },
            onBackup = { if (client.isGaiaReady()) session.restoreBackup() })
        host.removeAllViews(); host.addView(page)
        page.post { page.scrollTo(0, scrollY) }
    }

    private fun showPreview() {
        if (previewDialog?.isShowing == true) return
        val before = session.state.config ?: return
        val after = session.state.proposed ?: return
        val changes = buildList {
            TouchConfiguration.Ear.entries.forEach { side ->
                TouchConfiguration.Gesture.entries.forEach { gesture ->
                    val old = before.action(gesture, side)
                    val next = after.action(gesture, side)
                    if (old != next) add("${if (side == TouchConfiguration.Ear.LEFT) "左耳" else "右耳"} · ${gesture.title}\n${TouchConfiguration.label(old)} → ${TouchConfiguration.label(next)}")
                }
            }
        }
        previewDialog = AlertDialog.Builder(this)
            .setTitle("确认修改耳机按键")
            .setMessage(changes.joinToString("\n\n") + "\n\n其余按键保持当前设置。")
            .setPositiveButton("保存到耳机") { _, _ ->
                if (client.isGaiaReady()) session.applyPreview()
                else session.fail("耳机已断开，本次未写入")
            }
            .setNegativeButton("取消") { _, _ -> session.cancelPreview() }
            .setOnCancelListener { session.cancelPreview() }
            .create().also { it.show() }
    }

    private fun showPreset() {
        if (!session.state.editable) return
        AlertDialog.Builder(this)
            .setTitle("减少单击误触")
            .setItems(arrayOf("仅关闭左右耳单击", "关闭单击，并将双击改为播放 / 暂停")) { _, index ->
                if (client.isGaiaReady()) session.disableSingleTap(index == 1)
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showActions(gesture: TouchConfiguration.Gesture) {
        val current = session.state.config ?: return
        if (!session.state.editable) return
        val actions = TouchConfiguration.ACTIONS.entries.toList()
        val currentId = current.action(gesture, ear)
        AlertDialog.Builder(this)
            .setTitle("${if (ear == TouchConfiguration.Ear.LEFT) "左耳" else "右耳"} · ${gesture.title}")
            .setSingleChoiceItems(actions.map { it.value }.toTypedArray(), actions.indexOfFirst { it.key == currentId }) { dialog, index ->
                dialog.dismiss()
                if (client.isGaiaReady()) session.change(gesture, ear, actions[index].key)
                else session.fail("耳机已断开，请重新连接后读取设置")
            }
            .setNegativeButton("取消", null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("ear", ear.name); super.onSaveInstanceState(outState) }
    override fun onStop() {
        active = false
        previewDialog?.dismiss()
        previewDialog = null
        session.cancelPreview()
        if (session.listener === stateListener) session.listener = null
        client.releaseConnection(this)
        super.onStop()
    }
}
