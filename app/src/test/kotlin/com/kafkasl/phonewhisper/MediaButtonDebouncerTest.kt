package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaButtonDebouncerTest {
    @Test
    fun ignoresActionUp() {
        val debouncer = MediaButtonDebouncer()

        val result = debouncer.onKeyEvent(
            action = 1,
            keyCode = MediaButtonDebouncer.KEYCODE_HEADSETHOOK,
            repeatCount = 0,
            eventTimeMs = 1_000L,
            isLongPress = false,
        )

        assertEquals(MediaButtonDebouncer.Output.NONE, result)
    }

    @Test
    fun ignoresRepeatedDownEvents() {
        val debouncer = MediaButtonDebouncer()

        val result = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = MediaButtonDebouncer.KEYCODE_HEADSETHOOK,
            repeatCount = 1,
            eventTimeMs = 1_000L,
            isLongPress = false,
        )

        assertEquals(MediaButtonDebouncer.Output.NONE, result)
    }

    @Test
    fun ignoresUnsupportedKey() {
        val debouncer = MediaButtonDebouncer()

        val result = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = 42,
            repeatCount = 0,
            eventTimeMs = 1_000L,
            isLongPress = false,
        )

        assertEquals(MediaButtonDebouncer.Output.NONE, result)
    }

    @Test
    fun ignoresEventsInsideDebounceWindow() {
        val debouncer = MediaButtonDebouncer()

        val first = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = MediaButtonDebouncer.KEYCODE_HEADSETHOOK,
            repeatCount = 0,
            eventTimeMs = 1_000L,
            isLongPress = false,
        )
        val second = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = MediaButtonDebouncer.KEYCODE_MEDIA_PLAY_PAUSE,
            repeatCount = 0,
            eventTimeMs = 1_499L,
            isLongPress = false,
        )

        assertEquals(MediaButtonDebouncer.Output.TOGGLE, first)
        assertEquals(MediaButtonDebouncer.Output.NONE, second)
    }

    @Test
    fun acceptsEventsAtOrAfterDebounceWindow() {
        val debouncer = MediaButtonDebouncer()

        val first = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = MediaButtonDebouncer.KEYCODE_HEADSETHOOK,
            repeatCount = 0,
            eventTimeMs = 1_000L,
            isLongPress = false,
        )
        val second = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = MediaButtonDebouncer.KEYCODE_MEDIA_PLAY_PAUSE,
            repeatCount = 0,
            eventTimeMs = 1_500L,
            isLongPress = false,
        )

        assertEquals(MediaButtonDebouncer.Output.TOGGLE, first)
        assertEquals(MediaButtonDebouncer.Output.TOGGLE, second)
    }

    @Test
    fun acceptsAllSupportedKeyVariants() {
        val keyCodes = listOf(
            MediaButtonDebouncer.KEYCODE_HEADSETHOOK,
            MediaButtonDebouncer.KEYCODE_MEDIA_PLAY_PAUSE,
            MediaButtonDebouncer.KEYCODE_MEDIA_PLAY,
            MediaButtonDebouncer.KEYCODE_MEDIA_PAUSE,
        )

        keyCodes.forEachIndexed { index, keyCode ->
            val debouncer = MediaButtonDebouncer()

            val result = debouncer.onKeyEvent(
                action = MediaButtonDebouncer.ACTION_DOWN,
                keyCode = keyCode,
                repeatCount = 0,
                eventTimeMs = 1_000L + index,
                isLongPress = false,
            )

            assertEquals(MediaButtonDebouncer.Output.TOGGLE, result)
        }
    }

    @Test
    fun longPressReturnsCancelWhenNotDebounced() {
        val debouncer = MediaButtonDebouncer()

        val result = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = MediaButtonDebouncer.KEYCODE_MEDIA_PLAY_PAUSE,
            repeatCount = 0,
            eventTimeMs = 1_000L,
            isLongPress = true,
        )

        assertEquals(MediaButtonDebouncer.Output.CANCEL, result)
    }

    @Test
    fun longPressIsAlsoDebounced() {
        val debouncer = MediaButtonDebouncer()

        val first = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = MediaButtonDebouncer.KEYCODE_MEDIA_PLAY_PAUSE,
            repeatCount = 0,
            eventTimeMs = 1_000L,
            isLongPress = false,
        )
        val second = debouncer.onKeyEvent(
            action = MediaButtonDebouncer.ACTION_DOWN,
            keyCode = MediaButtonDebouncer.KEYCODE_MEDIA_PLAY_PAUSE,
            repeatCount = 0,
            eventTimeMs = 1_100L,
            isLongPress = true,
        )

        assertEquals(MediaButtonDebouncer.Output.TOGGLE, first)
        assertEquals(MediaButtonDebouncer.Output.NONE, second)
    }
}
