package com.hui455.moonear

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Shared production views. Screenshot tests exercise the same view builders as the app. */
object MoonEarPages {
    data class DeviceState(
        val name: String = "你的水月雨耳机", val connected: Boolean = false,
        val ready: Boolean = false, val left: Int = -1, val right: Int = -1,
        val mode: Int = -1, val modes: List<Int> = emptyList(),
    )
    data class Colors(val bg: Int, val card: Int, val text: Int, val muted: Int,
        val accent: Int, val soft: Int, val line: Int, val onAccent: Int)

    fun colors(context: Context): Colors = if (ThemeUtil.isDark(context)) Colors(
        Color.rgb(17, 21, 23), Color.rgb(27, 33, 35), Color.rgb(238, 244, 241),
        Color.rgb(159, 175, 169), Color.rgb(161, 225, 201), Color.rgb(38, 62, 54),
        Color.rgb(48, 58, 55), Color.rgb(15, 48, 37))
    else Colors(Color.rgb(246, 248, 246), Color.WHITE, Color.rgb(25, 40, 34),
        Color.rgb(104, 121, 112), Color.rgb(35, 104, 79), Color.rgb(225, 241, 231),
        Color.rgb(226, 233, 227), Color.WHITE)

    private class Builder(val c: Context) {
        val p = colors(c)
        fun dp(n: Int) = (n * c.resources.displayMetrics.density + .5f).toInt()
        fun column() = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        fun row() = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun text(value: String, size: Float = 14f, color: Int = p.text, bold: Boolean = false) = TextView(c).apply {
            text = value; textSize = size; setTextColor(color)
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            includeFontPadding = false
        }
        fun gap(parent: LinearLayout, n: Int) { parent.addView(View(c), LinearLayout.LayoutParams(1, dp(n))) }
        fun rounded(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
        fun icon(res: Int, color: Int = p.accent, size: Int = 24) = ImageView(c).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
        fun card(parent: LinearLayout, fill: Int = p.card, padding: Int = 20): LinearLayout {
            val outer = MaterialCardView(c).apply {
                radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(fill)
            }
            val content = column().apply { setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
            outer.addView(content)
            parent.addView(outer, LinearLayout.LayoutParams(-1, -2))
            return content
        }
        fun button(label: String, filled: Boolean = true, onClick: () -> Unit) = MaterialButton(c).apply {
            text = label; textSize = 14f; isAllCaps = false; cornerRadius = dp(18)
            minHeight = dp(48); minimumHeight = dp(48); insetTop = 0; insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(if (filled) p.accent else p.soft)
            setTextColor(if (filled) p.onAccent else p.accent)
            setOnClickListener { onClick() }
        }
        fun link(parent: LinearLayout, title: String, summary: String, res: Int, enabled: Boolean = true, click: () -> Unit) {
            val row = row().apply { minimumHeight = dp(76); setPadding(dp(18), dp(12), dp(18), dp(12)) }
            row.addView(icon(res))
            val labels = column().apply { setPadding(dp(14), 0, dp(8), 0) }
            labels.addView(text(title, 15f, bold = true)); gap(labels, 5)
            labels.addView(text(summary, 12f, p.muted))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("›", 25f, p.muted))
            row.isEnabled = enabled; row.alpha = if (enabled) 1f else .5f
            row.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(p.soft), null, rounded(p.card))
            row.setOnClickListener { click() }
            parent.addView(row)
        }
        fun page(): Pair<ScrollView, LinearLayout> {
            val content = column().apply { setPadding(dp(22), dp(16), dp(22), dp(28)) }
            val scroll = ScrollView(c).apply { setBackgroundColor(p.bg); isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }
            return scroll to content
        }
        fun chip(label: String, selected: Boolean = false) = text(label, 12f, if (selected) p.accent else p.muted, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7)); background = rounded(p.soft, 30)
        }
    }

    fun device(context: Context, state: DeviceState, onGestures: () -> Unit, onEq: () -> Unit,
        onNoise: (Int) -> Unit, onReconnect: () -> Unit): View = with(Builder(context)) {
        val (scroll, root) = page()
        val brand = row()
        brand.setPadding(dp(42), 0, 0, 0)
        brand.addView(text("MoonEar", 20f, bold = true), LinearLayout.LayoutParams(0, dp(48), 1f))
        val refresh = ImageButton(c).apply {
            setImageResource(R.drawable.ic_refresh); imageTintList = ColorStateList.valueOf(p.muted)
            background = rounded(p.card, 24); contentDescription = "刷新耳机连接"
            setOnClickListener { onReconnect() }
        }
        brand.addView(refresh, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(brand); gap(root, 18)
        root.addView(text("我的设备", 12f, p.muted, true)); gap(root, 10)
        val deviceTitle = state.name.removePrefix("MOONDROP ").replace("SPACE TRAVEL 2 ULTRA", "太空漫游 2 Ultra", true)
        root.addView(text(deviceTitle, 28f, bold = true)); gap(root, 10)
        val status = row()
        status.addView(icon(R.drawable.ic_bluetooth, if (state.ready) p.accent else p.muted, 16))
        status.addView(text(if (state.ready) "  已连接 · 控制已就绪" else if (state.connected) "  已连接 · 正在读取耳机" else "  等待耳机连接", 12f, p.muted))
        root.addView(status); gap(root, 20)
        val product = card(root, p.soft, 18)
        val image = FrameLayout(c)
        val halo = View(c).apply { background = rounded(p.card, 80); alpha = .65f }
        image.addView(halo, FrameLayout.LayoutParams(dp(126), dp(126), Gravity.CENTER))
        val headphones = icon(R.drawable.ic_earbuds_pair, p.accent, 86)
        image.addView(headphones, FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER))
        product.addView(image, LinearLayout.LayoutParams(-1, dp(132)))
        gap(product, 10)
        val battery = row()
        listOf("左耳" to state.left, "右耳" to state.right).forEachIndexed { index, (label, level) ->
            if (index > 0) battery.addView(View(c), LinearLayout.LayoutParams(dp(10), 1))
            val tile = row().apply { gravity = Gravity.CENTER; background = rounded(p.card, 16); setPadding(dp(12), dp(11), dp(12), dp(11)) }
            tile.addView(text("$label  ", 12f, p.muted))
            tile.addView(text(if (state.connected && level in 0..100) "$level%" else "—", 18f, bold = true))
            battery.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
        }
        product.addView(battery); gap(root, 22)
        val heading = row()
        heading.addView(text("聆听模式", 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text(if (state.mode >= 0) AncBridge.MODE_NAMES.getOrNull(state.mode) ?: "" else "等待连接", 12f, p.muted))
        root.addView(heading); gap(root, 12)
        val noise = card(root, padding = 12)
        val modes = row()
        val options = listOf(Triple(0, "关闭", R.drawable.ic_anc_off), Triple(1, "降噪", R.drawable.ic_anc_on),
            Triple(2, "通透", R.drawable.ic_anc_passthrough), Triple(3, "抗风", R.drawable.ic_air))
        options.forEach { (mode, label, res) ->
            val item = column().apply {
                gravity = Gravity.CENTER; setPadding(0, dp(13), 0, dp(13))
                if (state.mode == mode) background = rounded(p.soft, 17)
                isEnabled = state.ready && mode in state.modes
                alpha = if (isEnabled) 1f else .38f
                contentDescription = "切换到$label"; setOnClickListener { onNoise(mode) }
            }
            item.addView(icon(res, if (state.mode == mode) p.accent else p.muted)); gap(item, 8)
            item.addView(text(label, 12f, if (state.mode == mode) p.accent else p.muted, state.mode == mode).apply { gravity = Gravity.CENTER })
            modes.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        noise.addView(modes); gap(root, 16)
        val accidental = card(root, p.soft, 18)
        val tip = row()
        tip.addView(icon(R.drawable.ic_shield, size = 20))
        tip.addView(text("  关闭耳机单击误触", 16f, p.accent, true))
        accidental.addView(tip); gap(accidental, 9)
        accidental.addView(text("轻碰耳机总是暂停？关闭单击，让双击负责播放。", 13f, p.muted))
        gap(accidental, 14)
        accidental.addView(button("设置防误触  →", false, onGestures), LinearLayout.LayoutParams(-1, dp(46)))
        gap(root, 22)
        root.addView(text("耳机设置", 18f, bold = true)); gap(root, 12)
        val settings = card(root, padding = 0)
        link(settings, "耳机按键", "修改耳机机身的单击、双击和长按", R.drawable.ic_tune, click = onGestures)
        settings.addView(View(c).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        link(settings, "声音均衡器", "调整频段，找到喜欢的声音", R.drawable.ic_settings_ethernet, state.ready, onEq)
        gap(root, 18)
        root.addView(text("控制直接保存在耳机中", 11f, p.muted).apply { gravity = Gravity.CENTER })
        scroll
    }

    fun gestures(context: Context, state: TouchSession.State, ear: TouchConfiguration.Ear,
        onBack: () -> Unit, onEar: (TouchConfiguration.Ear) -> Unit, onRefresh: () -> Unit,
        onPreset: () -> Unit, onChoose: (TouchConfiguration.Gesture) -> Unit,
        onFactory: () -> Unit = {}, onBackup: () -> Unit = {}): View = with(Builder(context)) {
        val (scroll, root) = page()
        val toolbar = row()
        toolbar.addView(button("‹ 返回", false, onBack), LinearLayout.LayoutParams(dp(94), dp(44)))
        toolbar.addView(text("耳机按键", 15f, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(44), 1f))
        toolbar.addView(button("刷新", false, onRefresh).apply { isEnabled = !state.busy }, LinearLayout.LayoutParams(dp(78), dp(44)))
        root.addView(toolbar); gap(root, 26)
        root.addView(text("碰一下，\n也不打断音乐。", 29f, bold = true)); gap(root, 12)
        root.addView(text("设置作用于耳机机身的触控区，可分别调整左右耳。", 13f, p.muted))
        gap(root, 22)
        val recommendation = card(root, p.soft, 18)
        val title = row()
        title.addView(icon(R.drawable.ic_shield, size = 21)); title.addView(text("  单击防误触", 16f, p.accent, true))
        recommendation.addView(title); gap(recommendation, 10)
        recommendation.addView(text("关闭左右耳单击；也可将播放 / 暂停移到双击。", 13f, p.muted))
        gap(recommendation, 14)
        val protected = state.config?.let { config -> TouchConfiguration.Ear.entries.all { config.action(TouchConfiguration.Gesture.SINGLE, it) == 0 } } ?: false
        recommendation.addView(button(if (protected) "✓ 左右耳单击已关闭" else "设置防误触", false, onPreset).apply {
            isEnabled = state.editable
        }, LinearLayout.LayoutParams(-1, dp(48)))
        gap(root, 24)
        val selector = row().apply { background = rounded(p.card, 20); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        TouchConfiguration.Ear.entries.forEach { side ->
            val selected = side == ear
            val option = text(if (side == TouchConfiguration.Ear.LEFT) "L  左耳" else "R  右耳", 14f,
                if (selected) p.onAccent else p.muted, true).apply {
                gravity = Gravity.CENTER; background = rounded(if (selected) p.accent else p.card, 16)
                setOnClickListener { onEar(side) }; contentDescription = if (side == TouchConfiguration.Ear.LEFT) "选择左耳" else "选择右耳"
            }
            selector.addView(option, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        root.addView(selector); gap(root, 14)
        val controls = card(root, padding = 0)
        TouchConfiguration.Gesture.entries.forEachIndexed { index, gesture ->
            val row = row().apply { minimumHeight = dp(77); setPadding(dp(18), dp(14), dp(18), dp(14));
                isEnabled = state.editable; setOnClickListener { onChoose(gesture) } }
            val labels = column()
            labels.addView(text(gesture.title, 15f, bold = true)); gap(labels, 6)
            val defaultValue = state.defaults?.let { TouchConfiguration.label(it.action(gesture, ear)) }
            labels.addView(text(if (defaultValue == null) gesture.hint else "厂商默认：$defaultValue", 11f, p.muted))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val value = state.config?.let { TouchConfiguration.label(it.action(gesture, ear)) } ?: "未读取"
            row.addView(text(value, 13f, if (state.editable) p.accent else p.muted))
            row.addView(text("  ›", 24f, p.muted)); controls.addView(row)
            if (index < 4) controls.addView(View(c).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        gap(root, 16)
        val recovery = card(root, padding = 0)
        link(recovery, "恢复厂商默认", if (state.defaults != null) "使用这副耳机返回的出厂按键配置"
            else state.defaultMessage ?: "尚未读取到厂商默认配置", R.drawable.ic_restart_alt,
            state.editable && state.defaults != null, onFactory)
        recovery.addView(View(c).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        link(recovery, "恢复修改前配置", if (state.backup != null) "还原本应用首次修改前保存的配置" else "首次修改前将自动备份",
            R.drawable.ic_refresh, state.editable && state.backup != null, onBackup)
        gap(root, 16)
        val status = when (state.phase) {
            TouchSession.Phase.READING, TouchSession.Phase.PREPARING, TouchSession.Phase.CHECKING -> "正在读取耳机设置…"
            TouchSession.Phase.PREVIEW -> "请确认要修改的按键"
            TouchSession.Phase.WRITING, TouchSession.Phase.VERIFYING -> "正在保存并确认设置…"
            TouchSession.Phase.READY -> if (state.saved) "✓ 已保存到耳机，并确认生效" else "当前值读取自耳机 · 修改前会显示变更清单"
            else -> state.message ?: "请先连接耳机，再读取手势设置"
        }
        root.addView(text(status, 12f, p.muted).apply { gravity = Gravity.CENTER })
        gap(root, 10)
        root.addView(text("同一只耳机的两种长按时长互斥，启用一项会关闭另一项。", 11f, p.muted).apply { gravity = Gravity.CENTER })
        scroll
    }
}
