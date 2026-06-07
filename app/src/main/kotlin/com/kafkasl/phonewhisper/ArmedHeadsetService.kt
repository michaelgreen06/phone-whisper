package com.kafkasl.phonewhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

class ArmedHeadsetService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSession? = null
    private var notificationMode = NotificationMode.IDLE
    private var audioFocusRequest: AudioFocusRequest? = null
    private var silentTrack: AudioTrack? = null
    private var lastHeadsetAudioStopMs = 0L
    private var headsetAudioReceiverRegistered = false
    private val headsetAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) {
                handleHeadsetAudioStateChanged(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ArmedHeadsetService created")
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(notificationMode))
        createMediaSession()
        registerLegacyMediaButtonReceiver()
        registerHeadsetAudioReceiver()
        requestMediaButtonAudioFocus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                intent.keyEventExtra()?.let { handleMediaButton(it) }
            }
            ACTION_STOP_AND_TRANSCRIBE -> handleStopAndTranscribeAction()
            ACTION_CANCEL_RECORDING -> handleCancelRecordingAction()
        }
        updateNotification(notificationMode)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSilentPlaybackKeepalive()
        abandonMediaButtonAudioFocus()
        unregisterHeadsetAudioReceiver()
        unregisterLegacyMediaButtonReceiver()
        mediaSession?.release()
        mediaSession = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, MEDIA_SESSION_TAG).apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.keyEventExtra() ?: return super.onMediaButtonEvent(mediaButtonIntent)
                    return handleMediaButton(event)
                }
            }, mainHandler)
            @Suppress("DEPRECATION")
            setMediaButtonReceiver(buildMediaButtonIntent())
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE
                    )
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
            isActive = true
        }
    }

    private fun registerLegacyMediaButtonReceiver() {
        try {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audio.registerMediaButtonEventReceiver(
                ComponentName(this, ArmedMediaButtonReceiver::class.java)
            )
            Log.i(TAG, "Registered legacy media button receiver")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to register legacy media button receiver", e)
        }
    }

    private fun requestMediaButtonAudioFocus() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(
                { change -> Log.i(TAG, "Audio focus change=$change") },
                mainHandler
            )
            .build()

        val result = audio.requestAudioFocus(request)
        audioFocusRequest = request
        Log.i(TAG, "Requested media-button audio focus result=$result")
    }

    private fun startSilentPlaybackKeepalive() {
        try {
            val sampleRate = 8000
            val frameCount = sampleRate / 2
            val buffer = ShortArray(frameCount)
            // Bluetooth AVRCP routes media buttons only to the active playback owner.
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(buffer, 0, buffer.size)
            track.setLoopPoints(0, frameCount, -1)
            track.setVolume(0f)
            track.play()
            silentTrack = track
            Log.i(TAG, "Started silent playback keepalive state=${track.playState}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to start silent playback keepalive", e)
        }
    }

    private fun stopSilentPlaybackKeepalive() {
        val track = silentTrack ?: return
        try {
            track.stop()
            track.release()
            Log.i(TAG, "Stopped silent playback keepalive")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to stop silent playback keepalive", e)
        } finally {
            silentTrack = null
        }
    }

    private fun abandonMediaButtonAudioFocus() {
        val request = audioFocusRequest ?: return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.abandonAudioFocusRequest(request)
        audioFocusRequest = null
        Log.i(TAG, "Abandoned media-button audio focus")
    }

    private fun unregisterLegacyMediaButtonReceiver() {
        try {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audio.unregisterMediaButtonEventReceiver(
                ComponentName(this, ArmedMediaButtonReceiver::class.java)
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to unregister legacy media button receiver", e)
        }
    }

    private fun registerHeadsetAudioReceiver() {
        if (headsetAudioReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(headsetAudioReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(headsetAudioReceiver, filter)
        }
        headsetAudioReceiverRegistered = true
    }

    private fun unregisterHeadsetAudioReceiver() {
        if (!headsetAudioReceiverRegistered) return
        unregisterReceiver(headsetAudioReceiver)
        headsetAudioReceiverRegistered = false
    }

    private fun handleMediaButton(event: KeyEvent): Boolean {
        return HeadsetButtonDispatcher.dispatch("media-session", event)
    }

    private fun handleHeadsetAudioStateChanged(intent: Intent) {
        val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED)
        val current = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        val service = WhisperAccessibilityService.instance ?: return
        if (!service.isRecording()) return
        // During SCO recording, HK126 toggles headset audio instead of sending AVRCP.
        if (previous != BluetoothHeadset.STATE_AUDIO_CONNECTED ||
            current != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastHeadsetAudioStopMs < HEADSET_AUDIO_STOP_DEBOUNCE_MS) return
        lastHeadsetAudioStopMs = now

        Log.i(TAG, "Headset audio button inferred stop previous=$previous current=$current")
        service.handleCaptureToggle(CaptureSource.Headset)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phone Whisper armed",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the headset button ready while the microphone is off."
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun updateNotification(mode: NotificationMode) {
        notificationMode = mode
        notificationManager().notify(NOTIFICATION_ID, buildNotification(mode))
    }

    private fun buildNotification(mode: NotificationMode): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Phone Whisper armed")
            .setContentText(mode.text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (mode == NotificationMode.RECORDING) {
            builder
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Stop and transcribe",
                        buildServiceActionIntent(
                            requestCode = STOP_RECORDING_REQUEST_CODE,
                            action = ACTION_STOP_AND_TRANSCRIBE
                        )
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Cancel recording",
                        buildServiceActionIntent(
                            requestCode = CANCEL_RECORDING_REQUEST_CODE,
                            action = ACTION_CANCEL_RECORDING
                        )
                    ).build()
                )
        }

        return builder.build()
    }

    private fun buildServiceActionIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ArmedHeadsetService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun handleStopAndTranscribeAction() {
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Notification stop ignored because accessibility service is inactive")
            return
        }
        if (!service.isRecording()) {
            Log.i(TAG, "Notification stop ignored because capture is not recording")
            return
        }

        Log.i(TAG, "Notification stop source=notification")
        service.handleCaptureToggle(CaptureSource.Notification)
    }

    private fun handleCancelRecordingAction() {
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Notification cancel ignored because accessibility service is inactive")
            return
        }

        Log.i(TAG, "Notification cancel source=notification")
        service.cancelRecording(CaptureSource.Notification)
    }

    private fun buildMediaButtonIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            MEDIA_BUTTON_REQUEST_CODE,
            Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, ArmedMediaButtonReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notificationManager() =
        getSystemService(NotificationManager::class.java)

    private fun Intent.keyEventExtra(): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    companion object {
        private const val TAG = "PhoneWhisper"
        private const val CHANNEL_ID = "phone_whisper_armed"
        private const val NOTIFICATION_ID = 1001
        private const val MEDIA_SESSION_TAG = "PhoneWhisperHeadset"
        private const val MEDIA_BUTTON_REQUEST_CODE = 1002
        private const val OPEN_APP_REQUEST_CODE = 1003
        private const val STOP_RECORDING_REQUEST_CODE = 1004
        private const val CANCEL_RECORDING_REQUEST_CODE = 1005
        private const val ACTION_STOP_AND_TRANSCRIBE =
            "com.kafkasl.phonewhisper.action.STOP_AND_TRANSCRIBE"
        private const val ACTION_CANCEL_RECORDING =
            "com.kafkasl.phonewhisper.action.CANCEL_RECORDING"
        private const val HEADSET_AUDIO_STOP_DEBOUNCE_MS = 1000L

        @Volatile
        private var instance: ArmedHeadsetService? = null

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ArmedHeadsetService::class.java)
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to start armed headset service", e)
            }
        }

        fun showIdle() {
            instance?.updateNotification(NotificationMode.IDLE)
        }

        fun showRecording() {
            instance?.updateNotification(NotificationMode.RECORDING)
        }

        fun showTranscribing() {
            instance?.updateNotification(NotificationMode.TRANSCRIBING)
        }
    }

    private enum class NotificationMode(val text: String) {
        IDLE("Headset button ready. Mic off."),
        RECORDING("Recording... press again to stop"),
        TRANSCRIBING("Transcribing...")
    }
}
