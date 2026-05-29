package com.kafkasl.phonewhisper

class MediaButtonDebouncer(
    private val debounceWindowMs: Long = DEFAULT_DEBOUNCE_WINDOW_MS,
) {
    private var lastAcceptedEventTimeMs: Long? = null

    fun onKeyEvent(
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        eventTimeMs: Long,
        isLongPress: Boolean,
    ): Output {
        if (action != ACTION_DOWN || repeatCount != 0 || keyCode !in SUPPORTED_KEY_CODES) {
            return Output.NONE
        }

        val previousAcceptedTimeMs = lastAcceptedEventTimeMs
        if (previousAcceptedTimeMs != null && eventTimeMs - previousAcceptedTimeMs < debounceWindowMs) {
            return Output.NONE
        }

        lastAcceptedEventTimeMs = eventTimeMs
        return if (isLongPress) Output.CANCEL else Output.TOGGLE
    }

    enum class Output {
        NONE,
        TOGGLE,
        CANCEL,
    }

    companion object {
        const val DEFAULT_DEBOUNCE_WINDOW_MS: Long = 500L

        const val ACTION_DOWN: Int = 0

        const val KEYCODE_HEADSETHOOK: Int = 79
        const val KEYCODE_MEDIA_PLAY_PAUSE: Int = 85
        const val KEYCODE_MEDIA_PLAY: Int = 126
        const val KEYCODE_MEDIA_PAUSE: Int = 127

        private val SUPPORTED_KEY_CODES = setOf(
            KEYCODE_HEADSETHOOK,
            KEYCODE_MEDIA_PLAY_PAUSE,
            KEYCODE_MEDIA_PLAY,
            KEYCODE_MEDIA_PAUSE,
        )
    }
}
