package com.hui455.moonear

/** TOUCHV2 uses five bytes; TOUCHV3 adds two bytes that must be preserved unchanged. */
class TouchConfiguration private constructor(private val bytes: ByteArray) {
    enum class Ear { LEFT, RIGHT }
    enum class Gesture(val title: String, val hint: String) {
        SINGLE("单击", "轻触一下"), DOUBLE("双击", "连续轻触两下"),
        TRIPLE("三击", "连续轻触三下"), HOLD_SHORT("长按 1 秒", "短暂按住触控区域"),
        HOLD_LONG("长按 3 秒", "持续按住触控区域")
    }

    fun action(gesture: Gesture, ear: Ear): Int {
        val value = bytes[gesture.ordinal].toInt() and 255
        return if (ear == Ear.LEFT) value ushr 4 else value and 15
    }

    fun withAction(gesture: Gesture, ear: Ear, action: Int): TouchConfiguration {
        require(action in ACTIONS) { "Unverified action" }
        val result = bytes.copyOf()
        fun put(slot: Gesture, value: Int) {
            val old = result[slot.ordinal].toInt() and 255
            result[slot.ordinal] = (if (ear == Ear.LEFT) (old and 15) or (value shl 4)
                else (old and 240) or value).toByte()
        }
        put(gesture, action)
        // The firmware's two hold durations are mutually exclusive on the same ear.
        if (action != 0) when (gesture) {
            Gesture.HOLD_SHORT -> put(Gesture.HOLD_LONG, 0)
            Gesture.HOLD_LONG -> put(Gesture.HOLD_SHORT, 0)
            else -> Unit
        }
        return TouchConfiguration(result)
    }

    fun payload(): ByteArray = bytes.copyOf()
    override fun equals(other: Any?): Boolean = other is TouchConfiguration && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val DEFAULT = 1
        const val GET = 2
        const val SET = 3
        // IDs 4/5 (volume) are inferred in available protocol notes; do not offer unverified writes.
        val ACTIONS = linkedMapOf(0 to "不执行操作", 1 to "播放 / 暂停", 2 to "上一曲",
            3 to "下一曲", 6 to "语音助手", 7 to "切换降噪模式")
        fun label(id: Int): String = ACTIONS[id] ?: "当前动作（$id）"
        fun parse(payload: ByteArray): TouchConfiguration? =
            if (payload.size == 5 || payload.size == 7) TouchConfiguration(payload.copyOf()) else null
    }
}

/** A write is successful only after an ACK and an exact configuration readback. */
class TouchSession(
    private val send: (Int, Int, ByteArray) -> Unit,
    private val scheduleTimeout: (() -> Unit) -> Unit,
    private val scheduleReadback: (() -> Unit) -> Unit = {},
) {
    enum class Phase { IDLE, READING, READY, PREPARING, PREVIEW, CHECKING, WRITING, VERIFYING, ERROR }
    data class State(
        val phase: Phase = Phase.IDLE,
        val config: TouchConfiguration? = null,
        val message: String? = null,
        val saved: Boolean = false,
        val defaults: TouchConfiguration? = null,
        val backup: TouchConfiguration? = null,
        val proposed: TouchConfiguration? = null,
        val defaultMessage: String? = null,
    ) {
        val busy: Boolean get() = phase in setOf(Phase.READING, Phase.PREPARING, Phase.CHECKING, Phase.WRITING, Phase.VERIFYING)
        val editable: Boolean get() = phase == Phase.READY && config != null
    }
    var state = State()
        private set
    var listener: ((State) -> Unit)? = null
    /** Called before the first write, after the exact old configuration has been read. */
    var backupWriter: ((TouchConfiguration) -> Unit)? = null
    private var generation = 0L
    private var defaultTicket = 0L
    private var readingDefaults = false
    var feature: Int = GaiaCommands.F_TOUCHV2
        private set
    private var triedAlternative = false
    private data class Edit(val gesture: TouchConfiguration.Gesture, val ear: TouchConfiguration.Ear, val action: Int)
    private var edits: List<Edit> = emptyList()
    private var expected: TouchConfiguration? = null
    private var restoreTarget: TouchConfiguration? = null

    fun loadBackup(backup: TouchConfiguration?) { transition(state.copy(backup = backup)) }

    private fun transmit(command: Int, payload: ByteArray = byteArrayOf()) = send(feature, command, payload)

    fun refresh(preferredFeature: Int = feature) {
        if (state.busy || state.phase == Phase.PREVIEW) return
        require(preferredFeature in listOf(GaiaCommands.F_TOUCHV2, GaiaCommands.F_TOUCHV3))
        if (preferredFeature != feature) transition(state.copy(defaults = null))
        feature = preferredFeature
        triedAlternative = false
        edits = emptyList()
        expected = null
        restoreTarget = null
        transition(state.copy(phase = Phase.READING, config = null, proposed = null, saved = false, message = null))
        armTimeout()
        transmit(TouchConfiguration.GET)
    }

    fun change(gesture: TouchConfiguration.Gesture, ear: TouchConfiguration.Ear, action: Int) {
        if (!state.editable) return
        require(action in TouchConfiguration.ACTIONS)
        beginEdits(listOf(Edit(gesture, ear, action)))
    }

    fun disableSingleTap(movePlayPauseToDouble: Boolean) {
        if (!state.editable) return
        val updates = TouchConfiguration.Ear.entries.flatMap { ear ->
            listOf(Edit(TouchConfiguration.Gesture.SINGLE, ear, 0)) +
                if (movePlayPauseToDouble) listOf(Edit(TouchConfiguration.Gesture.DOUBLE, ear, 1)) else emptyList()
        }
        beginEdits(updates)
    }

    private fun beginEdits(updates: List<Edit>) {
        edits = updates
        restoreTarget = null
        expected = null
        transition(state.copy(phase = Phase.PREPARING, saved = false, message = null))
        armTimeout()
        // Always read the latest full configuration before changing a single nibble.
        transmit(TouchConfiguration.GET)
    }

    fun restoreFactory() { state.defaults?.let { prepareRestore(it) } }
    fun restoreBackup() { state.backup?.let { prepareRestore(it) } }
    private fun prepareRestore(target: TouchConfiguration) {
        if (!state.editable) return
        edits = emptyList()
        restoreTarget = target
        expected = null
        transition(state.copy(phase = Phase.PREPARING, proposed = null, saved = false, message = null))
        armTimeout()
        transmit(TouchConfiguration.GET)
    }

    fun applyPreview() {
        if (state.phase != Phase.PREVIEW || state.proposed == null) return
        transition(state.copy(phase = Phase.CHECKING))
        armTimeout()
        transmit(TouchConfiguration.GET)
    }

    fun cancelPreview() {
        if (state.phase != Phase.PREVIEW) return
        expected = null
        edits = emptyList()
        restoreTarget = null
        transition(state.copy(phase = Phase.READY, proposed = null))
    }

    fun onPacket(type: Int, command: Int, payload: ByteArray, packetFeature: Int = feature) {
        if (packetFeature != feature) return
        if (command == TouchConfiguration.DEFAULT && readingDefaults) {
            if (type !in 2..3) return
            readingDefaults = false
            defaultTicket++
            val defaults = if (type == 2 && payload.size == state.config?.payload()?.size) TouchConfiguration.parse(payload) else null
            transition(state.copy(defaults = defaults,
                defaultMessage = if (defaults == null) "耳机未提供厂商默认值" else null))
            return
        }
        if (!state.busy) return
        if (type == 3 && command in listOf(TouchConfiguration.GET, TouchConfiguration.SET)) {
            if (state.phase == Phase.READING && tryAlternative()) return
            fail("耳机暂不支持这项设置，请重试")
            return
        }
        if (type != GaiaCommands.TYPE_RESPONSE) return
        if (command == TouchConfiguration.SET && state.phase == Phase.WRITING) {
            // Some firmwares echo the five bytes; others ACK with an empty payload.
            if (payload.isNotEmpty() && !payload.contentEquals(expected?.payload())) {
                fail("耳机未确认修改，请重新读取")
                return
            }
            transition(state.copy(phase = Phase.VERIFYING))
            transmit(TouchConfiguration.GET)
            return
        }
        if (command != TouchConfiguration.GET || state.phase == Phase.WRITING) return
        val config = TouchConfiguration.parse(payload) ?: run {
            fail("暂时无法读取这副耳机的手势设置")
            return
        }
        if (payload.size != if (feature == GaiaCommands.F_TOUCHV3) 7 else 5) {
            fail("耳机返回的配置格式不匹配，本次未写入")
            return
        }
        when (state.phase) {
            Phase.READING -> {
                finish(config, false)
                fetchDefaults()
            }
            Phase.PREPARING -> {
                if (edits.isEmpty() && restoreTarget == null) return
                val next = restoreTarget ?: edits.fold(config) { current, edit -> current.withAction(edit.gesture, edit.ear, edit.action) }
                if (next.payload().size != config.payload().size) {
                    fail("备份与当前耳机配置格式不同，本次未写入")
                    return
                }
                if (next == config) { finish(config, false); return }
                expected = next
                generation++
                transition(state.copy(phase = Phase.PREVIEW, config = config, proposed = next))
            }
            Phase.CHECKING -> {
                if (config != state.config) {
                    fail("耳机配置已变化，请重新读取后修改")
                    return
                }
                val next = expected ?: return
                if (state.backup == null) {
                    try { backupWriter?.invoke(config) } catch (_: Exception) {
                        fail("修改前配置备份失败，本次未写入")
                        return
                    }
                    transition(state.copy(backup = config))
                }
                transition(state.copy(phase = Phase.WRITING))
                transmit(TouchConfiguration.SET, next.payload())
                val ticket = generation
                scheduleReadback {
                    // Some firmware applies SET without a response. An exact GET remains the success criterion.
                    if (generation == ticket && state.phase == Phase.WRITING) {
                        transition(state.copy(phase = Phase.VERIFYING))
                        transmit(TouchConfiguration.GET)
                    }
                }
            }
            Phase.VERIFYING -> {
                if (config == expected) finish(config, true)
                else fail("读回结果与修改不一致，请重新读取")
            }
            else -> Unit
        }
    }

    fun reset() {
        generation++
        defaultTicket++
        readingDefaults = false
        feature = GaiaCommands.F_TOUCHV2
        triedAlternative = false
        edits = emptyList()
        expected = null
        restoreTarget = null
        transition(State())
    }

    fun fail(message: String) {
        generation++
        edits = emptyList()
        expected = null
        restoreTarget = null
        // A timeout must never leave stale values writable.
        transition(state.copy(phase = Phase.ERROR, config = null, proposed = null, message = message, saved = false))
    }

    private fun finish(config: TouchConfiguration, saved: Boolean) {
        generation++
        edits = emptyList()
        expected = null
        restoreTarget = null
        transition(state.copy(phase = Phase.READY, config = config, saved = saved, proposed = null, message = null))
    }

    private fun fetchDefaults() {
        readingDefaults = true
        val ticket = ++defaultTicket
        transition(state.copy(defaults = null, defaultMessage = "正在读取厂商默认值…"))
        scheduleTimeout {
            if (readingDefaults && ticket == defaultTicket) {
                readingDefaults = false
                transition(state.copy(defaultMessage = "耳机未响应厂商默认值查询"))
            }
        }
        transmit(TouchConfiguration.DEFAULT)
    }

    private fun armTimeout() {
        val ticket = ++generation
        scheduleTimeout {
            if (ticket == generation && state.busy) {
                if (state.phase != Phase.READING || !tryAlternative())
                    fail("未收到耳机确认，请保持连接后重试")
            }
        }
    }

    private fun tryAlternative(): Boolean {
        if (triedAlternative) return false
        triedAlternative = true
        feature = if (feature == GaiaCommands.F_TOUCHV2) GaiaCommands.F_TOUCHV3 else GaiaCommands.F_TOUCHV2
        armTimeout()
        transmit(TouchConfiguration.GET)
        return true
    }

    private fun transition(next: State) { state = next; listener?.invoke(next) }
}
