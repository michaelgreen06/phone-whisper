package com.kafkasl.phonewhisper

import android.util.Log
import android.view.KeyEvent

object HeadsetButtonDispatcher {
    private const val TAG = "PhoneWhisper"

    private val debouncer = MediaButtonDebouncer()

    fun dispatch(path: String, event: KeyEvent): Boolean {
        if (!isSupportedMediaButton(event.keyCode)) return false

        val result = debouncer.onKeyEvent(
            action = event.action,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
            eventTimeMs = event.eventTime,
            isLongPress = event.isLongPress
        )
        if (result != MediaButtonDebouncer.Output.NONE) {
            Log.i(
                TAG,
                "Headset button path=$path action=${event.action} keyCode=${event.keyCode} result=$result"
            )
        }

        return when (result) {
            MediaButtonDebouncer.Output.TOGGLE -> {
                toggleCapture()
                true
            }
            MediaButtonDebouncer.Output.CANCEL -> {
                cancelCapture()
                true
            }
            MediaButtonDebouncer.Output.NONE -> true
        }
    }

    fun isSupportedMediaButton(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE

    private fun toggleCapture() {
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Enable Accessibility Service before headset capture")
            return
        }

        if (!service.isRecording()) {
            Log.i(TAG, "Headset media button ignored because capture is not recording")
            return
        }

        ArmedHeadsetService.suppressVolumeStartsBriefly()
        Log.i(TAG, "Headset media button stopping recording")
        service.handleCaptureToggle(CaptureSource.Headset)
    }

    private fun cancelCapture() {
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Enable Accessibility Service before headset capture")
            return
        }

        service.cancelRecording(CaptureSource.Headset)
    }
}
