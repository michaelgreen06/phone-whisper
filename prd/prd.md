Here’s the implementation prompt/summary for the Phone Whisper fork.

**Goal**
Modify `phone-whisper` into an always-armed thought capture app for Android using the **Motorola HK126 Bluetooth headset**.

Desired behavior:
- User opens the app once.
- App enters an **armed idle** state by default.
- Phone can be locked/asleep.
- Pressing the HK126 multifunction button starts recording.
- Pressing it again stops recording.
- The app transcribes the audio using Phone Whisper’s existing transcription flow or local model on device
- Instead of injecting text into the focused app, it saves each transcript as a new markdown note in an **Obsidian inbox**.
- No Tasker or external automation should be required.

**Current App Baseline**
Phone Whisper currently works like a dictation overlay:
- floating mic button starts/stops recording
- recording uses `AudioRecord`
- transcription already exists, local or cloud
- transcript is copied to clipboard and injected into the focused text field via accessibility APIs

We want to preserve:
- recording/transcription pipeline
- local transcription support
- cloud transcription option
- simple toggle state machine

We want to replace or bypass:
- focused text-field injection
- clipboard-first behavior as the main output
- dependence on touching the screen overlay

for phase1 let's get the input for the audio to come from the microphone of the hk126 headset. 

//likely not using phase 1
**Phase 1: Verify HK126 Button And Mic**
Add a diagnostics screen or debug mode that shows:
- currently connected Bluetooth audio devices
- whether the HK126 is connected
- whether it is available as an input/communication device
- raw media button events received from the headset
- active recording input route if possible

Android pieces likely needed:
- `BluetoothAdapter` / `BluetoothManager`
- `AudioManager`
- `AudioDeviceInfo`
- `MediaSession` or `MediaSessionCompat`
- `BLUETOOTH_CONNECT` permission on Android 12+
- existing `RECORD_AUDIO`

Success criteria:
- HK126 pairs normally in Android Bluetooth settings
- app can detect it as connected
- app receives a single press from the HK126 MFB
- app can record usable audio while the HK126 is connected

**Phase 2: Add Always-Armed Background Service**
Add a foreground service that starts when the app opens and remains active.

Service responsibilities:
- keep the app in `ARMED_IDLE` state by default
- hold an active `MediaSession`
- listen for Bluetooth headset media button events
- expose a persistent notification like `Phone Whisper armed`
- do not open the microphone while idle
- only start recording after headset button press

Important battery rule:
- idle service should not record, transcribe, poll heavily, or keep hotword detection running
- it should only keep enough state alive to receive the headset button

Success criteria:
- app opened once
- screen locked
- HK126 button still toggles capture
- service survives normal backgrounding
- notification clearly indicates armed state

**Phase 3: Button Toggle Behavior**
Map HK126 button events to the existing recording state machine.

Desired mapping:
- single press while idle: `startRecording()`
- single press while recording: `stopAndTranscribe()`
- optional long press: cancel current recording
- ignore duplicate down/up repeats so one physical press does not trigger twice

Implementation detail:
- refactor current private `onTap()`, `startRecording()`, and `stopAndTranscribe()` logic so both overlay taps and headset button events call the same public/internal command path.
- debounce media button events, because Bluetooth devices may emit both key down and key up.

Success criteria:
- overlay button still works
- HK126 button works
- one press starts, second press stops
- no double-triggering

**Phase 4: Bluetooth Mic Routing**
Ensure recording uses the HK126 mic when available.

Likely approach:
- use `AudioManager.getAvailableCommunicationDevices()`
- find Bluetooth headset device
- call `setCommunicationDevice(...)` before starting recording
- fall back to phone mic if unavailable
- release/clear route after recording if appropriate

Potential Android compatibility issue:
- some devices expose Bluetooth headset audio only in communication mode
- audio may be narrowband/telephony quality
- that is acceptable if transcription quality is good enough

Success criteria:
- test recording with phone across the room or covered
- confirm audio is coming from HK126, not phone mic
- transcript quality is acceptable for short thought capture

**Phase 5: Save To Obsidian Inbox**
Replace default transcript handling.

Instead of:
- copy to clipboard
- inject into current focused text field

Do:
- create a new markdown file per capture in an Obsidian inbox folder

Suggested file format:
```md
---
captured_at: 2026-05-28T14:32:11-06:00
source: hk126
status: inbox
transcription_engine: local
---

send cora a message about xyz
```

Suggested filename:
```text
2026-05-28-143211-thought.md
```

Storage options:
- user-configurable folder path
- Android Storage Access Framework folder picker
- persist URI permission
- write markdown files into selected Obsidian vault inbox folder

Success criteria:
- every completed transcription creates a new markdown note
- no focused app is required
- works while phone was locked during recording
- notes appear in Obsidian sync/vault

**Phase 6: Settings**
Add simple settings:
- `Armed on app open`: default `true`
- `Use headset button`: default `true`
- `Preferred Bluetooth device`: default detected HK126 if present
- `Save destination`: Obsidian inbox folder
- `Also copy transcript to clipboard`: optional, default `false`
- `Also inject into focused field`: optional legacy mode, default `false`
- `Max recording length`: maybe `90s` default to protect battery and accidental captures

**Key Risks To Test Early**
Test these before polishing UI:
- Does HK126 MFB emit usable media button events?
- Does Android deliver those events while the screen is locked?
- Can the app route `AudioRecord` to the HK126 mic reliably?
- Does Phone Whisper’s current local STT handle HK126 Bluetooth audio quality?
- Does the foreground service stay alive without aggressive battery optimization killing it?
- Can the app write directly to the Obsidian inbox folder using SAF?

**Recommended Build Order**
1. Add diagnostics for HK126 connection and button events.
2. Add `MediaSession` and confirm button events while app is open.
3. Move that into a foreground armed service and test with screen locked.
4. Route recording to Bluetooth mic and validate audio source.
5. Save transcript to local app storage as markdown.
6. Add SAF folder picker and save to Obsidian inbox.
7. Disable text injection by default.
8. Add settings and battery guardrails.

**Definition Of MVP Done**
The MVP is done when:
- Android phone is locked/asleep
- HK126 is connected
- app has been opened once and shows armed notification
- one HK126 press starts recording
- second HK126 press stops recording
- transcription runs
- a new markdown note appears in the configured Obsidian inbox
- no Tasker, Google Keep, manual paste, or USB transfer is involved.