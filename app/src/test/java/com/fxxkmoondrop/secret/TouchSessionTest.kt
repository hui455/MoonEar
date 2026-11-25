package com.hui455.moonear

import org.junit.Assert.*
import org.junit.Test

class TouchSessionTest {
    private val factory = byteArrayOf(0x11, 0x23, 0x66, 0x77, 0)
    private class Harness {
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        val timers = mutableListOf<() -> Unit>()
        val session = TouchSession({ _, command, payload -> sent += command to payload }, { timers += it })
        fun reply(command: Int, payload: ByteArray) = session.onPacket(2, command, payload)
    }
    @Test fun changePreservesOtherEarAndUnknownActions() {
        val raw = byteArrayOf(0xA1.toByte(), 0x23, 0x66, 0x77, 0)
        val config = TouchConfiguration.parse(raw)!!
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0x23, 0x66, 0x77, 0),
            config.withAction(TouchConfiguration.Gesture.SINGLE, TouchConfiguration.Ear.RIGHT, 0).payload())
        assertArrayEquals(raw, config.payload())
    }
    @Test fun holdExclusionOnlyChangesTheSameEar() {
        val config = TouchConfiguration.parse(factory)!!
        assertArrayEquals(byteArrayOf(0x11, 0x23, 0x66, 0x07, 0x60),
            config.withAction(TouchConfiguration.Gesture.HOLD_LONG, TouchConfiguration.Ear.LEFT, 6).payload())
    }
    @Test fun antiMistouchReadsFreshBytesAndRequiresReadback() {
        val h = Harness()
        h.session.refresh(); h.reply(2, factory)
        h.session.disableSingleTap(true)
        assertEquals(2, h.sent.last().first)
        val fresh = byteArrayOf(0x11, 0x23, 0x63, 0x77, 0)
        h.reply(2, fresh)
        val expected = byteArrayOf(0, 0x11, 0x63, 0x77, 0)
        assertArrayEquals(expected, h.session.state.proposed!!.payload())
        assertEquals(TouchSession.Phase.PREVIEW, h.session.state.phase)
        assertFalse(h.sent.any { it.first == 3 })
        h.session.applyPreview(); h.reply(2, fresh)
        assertArrayEquals(expected, h.sent.last().second)
        assertFalse(h.session.state.saved)
        h.reply(3, expected)
        assertEquals(TouchSession.Phase.VERIFYING, h.session.state.phase)
        assertEquals(2, h.sent.last().first)
        h.reply(2, expected)
        assertTrue(h.session.state.saved)
        assertTrue(h.session.state.editable)
    }
    @Test fun missingOrMalformedResponseNeverEnablesWrites() {
        val h = Harness()
        h.session.refresh(); h.reply(2, byteArrayOf(1))
        assertEquals(TouchSession.Phase.ERROR, h.session.state.phase)
        assertFalse(h.session.state.editable)
        h.session.refresh(); h.timers.last().invoke(); h.timers.last().invoke()
        assertFalse(h.session.state.editable)
        h.reply(2, factory)
        assertFalse(h.session.state.editable)
    }
    @Test fun rejectedAndMismatchedWritesAreNotSuccessful() {
        val h = Harness()
        h.session.refresh(); h.reply(2, factory)
        h.session.disableSingleTap(false); h.reply(2, factory)
        h.session.applyPreview(); h.reply(2, factory)
        h.reply(3, byteArrayOf())
        h.reply(2, factory)
        assertEquals(TouchSession.Phase.ERROR, h.session.state.phase)
        assertFalse(h.session.state.saved)
        h.session.refresh(); h.session.onPacket(3, 2, byteArrayOf(1))
        assertFalse(h.session.state.editable)
    }
    @Test fun disconnectCancelsPendingWorkAndIgnoresLateResponses() {
        val h = Harness()
        h.session.refresh(); h.reply(2, factory)
        h.session.disableSingleTap(false)
        h.session.reset()
        val count = h.sent.size
        h.reply(2, factory); h.timers.forEach { it() }
        assertEquals(count, h.sent.size)
        assertEquals(TouchSession.Phase.IDLE, h.session.state.phase)
    }

    @Test fun defaultsAreReadSeparatelyAndRestoredExactly() {
        val h = Harness()
        val current = byteArrayOf(0, 0x11, 0x66, 0x77, 0)
        h.session.refresh(); h.reply(2, current)
        assertEquals(1, h.sent.last().first)
        h.reply(1, factory)
        assertArrayEquals(current, h.session.state.config!!.payload())
        assertArrayEquals(factory, h.session.state.defaults!!.payload())
        h.session.restoreFactory(); h.reply(2, current)
        assertArrayEquals(factory, h.session.state.proposed!!.payload())
        h.session.applyPreview(); h.reply(2, current)
        assertArrayEquals(factory, h.sent.last().second)
        h.reply(3, byteArrayOf()); h.reply(2, factory)
        assertTrue(h.session.state.saved)
    }

    @Test fun unsupportedDefaultsDoNotInventValuesOrDisableCurrentEditing() {
        val h = Harness()
        h.session.refresh(); h.reply(2, factory)
        h.session.onPacket(3, 1, byteArrayOf(1))
        assertTrue(h.session.state.editable)
        assertNull(h.session.state.defaults)
        val count = h.sent.size
        h.session.restoreFactory()
        assertEquals(count, h.sent.size)
    }

    @Test fun stalePreviewDoesNotOverwriteChangesMadeElsewhere() {
        val h = Harness()
        h.session.refresh(); h.reply(2, factory)
        h.session.disableSingleTap(false); h.reply(2, factory)
        h.session.applyPreview(); h.reply(2, byteArrayOf(0x11, 0x33, 0x66, 0x77, 0))
        assertFalse(h.sent.any { it.first == 3 })
        assertEquals(TouchSession.Phase.ERROR, h.session.state.phase)
    }

    @Test fun backupIsPersistedBeforeAnyWriteAndDoesNotBecomeFactoryDefault() {
        val h = Harness()
        var backedUp: TouchConfiguration? = null
        h.session.backupWriter = { config ->
            assertFalse(h.sent.any { it.first == 3 })
            backedUp = config
        }
        h.session.refresh(); h.reply(2, factory)
        h.session.disableSingleTap(false); h.reply(2, factory)
        h.session.applyPreview(); h.reply(2, factory)
        assertArrayEquals(factory, backedUp!!.payload())
        assertNull(h.session.state.defaults)
    }

    @Test fun readOnlyFallbackToTouchV3PreservesExtraSlots() {
        val h = Harness()
        h.session.refresh(); h.session.onPacket(3, 2, byteArrayOf(1))
        assertEquals(GaiaCommands.F_TOUCHV3, h.session.feature)
        val extended = byteArrayOf(0x11, 0x23, 0x66, 0x77, 0, 0xAC.toByte(), 0xFE.toByte())
        h.reply(2, extended)
        h.session.disableSingleTap(false); h.reply(2, extended)
        val result = h.session.state.proposed!!.payload()
        assertEquals(0, result[0].toInt())
        assertArrayEquals(extended.copyOfRange(1, 7), result.copyOfRange(1, 7))
    }

    @Test fun cancelledPreviewNeverWrites() {
        val h = Harness()
        h.session.refresh(); h.reply(2, factory)
        h.session.disableSingleTap(false); h.reply(2, factory)
        h.session.cancelPreview(); h.session.applyPreview()
        assertFalse(h.sent.any { it.first == 3 })
        assertTrue(h.session.state.editable)
    }

    @Test fun firmwareWithoutSetAckStillRequiresExactReadback() {
        val sent = mutableListOf<Int>()
        var delayedRead: (() -> Unit)? = null
        val session = TouchSession({ _, command, _ -> sent += command }, {}, { delayedRead = it })
        session.refresh(); session.onPacket(2, 2, factory)
        session.disableSingleTap(false); session.onPacket(2, 2, factory)
        session.applyPreview(); session.onPacket(2, 2, factory)
        assertEquals(3, sent.last())
        delayedRead!!()
        assertEquals(2, sent.last())
        session.onPacket(2, 2, byteArrayOf(0, 0x23, 0x66, 0x77, 0))
        assertTrue(session.state.saved)
    }
}
