package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

object MicrophoneDevices {
    const val PREF_MODE = "microphone_selection_mode"
    const val PREF_TYPE = "microphone_selection_type"
    const val PREF_ADDRESS = "microphone_selection_address"
    const val PREF_NAME = "microphone_selection_name"

    const val MODE_AUTO = "auto"
    const val MODE_DEVICE = "device"

    const val TYPE_BUILTIN_MIC = AudioDeviceInfo.TYPE_BUILTIN_MIC
    const val TYPE_BLUETOOTH_SCO = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    const val TYPE_BLE_HEADSET = AudioDeviceInfo.TYPE_BLE_HEADSET

    private const val TAG = "PhoneWhisper"

    data class DeviceDescriptor(
        val type: Int,
        val address: String,
        val name: String,
        val isSource: Boolean = true
    )

    data class SavedSelection(
        val mode: String,
        val type: Int?,
        val address: String,
        val name: String
    ) {
        val isAuto: Boolean get() = mode != MODE_DEVICE
    }

    data class DeviceOption(
        val title: String,
        val subtitle: String,
        val mode: String,
        val type: Int?,
        val address: String,
        val name: String,
        val isBluetooth: Boolean = false
    ) {
        val dialogLabel: String
            get() = if (subtitle.isBlank()) title else "$title\n$subtitle"
    }

    fun loadSelection(prefs: SharedPreferences): SavedSelection =
        SavedSelection(
            mode = prefs.getString(PREF_MODE, MODE_AUTO) ?: MODE_AUTO,
            type = if (prefs.contains(PREF_TYPE)) prefs.getInt(PREF_TYPE, -1).takeIf { it != -1 } else null,
            address = prefs.getString(PREF_ADDRESS, "") ?: "",
            name = prefs.getString(PREF_NAME, "") ?: ""
        )

    fun saveSelection(prefs: SharedPreferences, option: DeviceOption) {
        prefs.edit().apply {
            putString(PREF_MODE, option.mode)
            if (option.type == null) remove(PREF_TYPE) else putInt(PREF_TYPE, option.type)
            putString(PREF_ADDRESS, option.address)
            putString(PREF_NAME, option.name)
        }.apply()
    }

    fun subtitle(selection: SavedSelection): String {
        if (selection.isAuto) return "Auto"
        return selection.name.ifBlank { selection.type?.let(::typeLabel) ?: "Selected microphone" }
    }

    fun selectorOptions(context: Context, includeBluetooth: Boolean = canUseBluetoothDevices(context)): List<DeviceOption> {
        val options = mutableListOf(
            DeviceOption(
                title = "Auto",
                subtitle = "Use Android default input routing",
                mode = MODE_AUTO,
                type = null,
                address = "",
                name = "Auto"
            )
        )

        val descriptors = deviceDescriptors(context, includeBluetooth)
            .filter { it.isSource && isSelectableInputType(it.type) }
            .distinctBy { "${it.type}|${it.address}|${it.name}" }

        val builtIn = descriptors.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        options += deviceOption(
            builtIn ?: DeviceDescriptor(
                type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
                address = "",
                name = "Phone microphone"
            )
        )

        descriptors
            .filterNot { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            .sortedWith(compareBy<DeviceDescriptor> { !isBluetoothType(it.type) }.thenBy { it.name })
            .forEach { options += deviceOption(it) }

        return options
    }

    fun selectedOptionIndex(selection: SavedSelection, options: List<DeviceOption>): Int {
        if (selection.isAuto) return options.indexOfFirst { it.mode == MODE_AUTO }.coerceAtLeast(0)
        val exact = options.indexOfFirst {
            it.mode == MODE_DEVICE &&
                it.type == selection.type &&
                it.address == selection.address
        }
        if (exact >= 0) return exact

        val builtIn = options.indexOfFirst {
            selection.type == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                it.mode == MODE_DEVICE &&
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
        return builtIn.takeIf { it >= 0 } ?: 0
    }

    fun resolveSelectedDevice(context: Context, selection: SavedSelection = loadSelection(context.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE))): AudioDeviceInfo? {
        if (selection.isAuto) return null
        val match = matchSelectedDevice(selection, deviceDescriptors(context, canUseBluetoothDevices(context)))
            ?: return null

        return audioInputDevices(context)
            .firstOrNull { device ->
                device.type == match.type &&
                    (device.address.orEmpty() == match.address || match.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
            }
    }

    fun resolveCommunicationDevice(context: Context, selection: SavedSelection): AudioDeviceInfo? {
        if (!isBluetoothSelection(selection) || !canUseBluetoothDevices(context)) return null
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        }

        return devices.firstOrNull { device ->
            isBluetoothType(device.type) &&
                selection.address.isNotBlank() &&
                device.address.orEmpty() == selection.address
        } ?: devices.firstOrNull { device ->
            isBluetoothType(device.type) &&
                selection.name.isNotBlank() &&
                device.productName?.toString() == selection.name
        } ?: devices.firstOrNull { isBluetoothType(it.type) }
    }

    fun matchSelectedDevice(selection: SavedSelection, devices: List<DeviceDescriptor>): DeviceDescriptor? {
        if (selection.isAuto) return null
        val type = selection.type ?: return null
        val inputs = devices.filter { it.isSource && it.type == type }

        inputs.firstOrNull { it.address == selection.address }?.let { return it }

        return if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) inputs.firstOrNull() else null
    }

    fun canUseBluetoothDevices(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun needsBluetoothConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canUseBluetoothDevices(context)

    fun isBluetoothSelection(selection: SavedSelection): Boolean =
        !selection.isAuto && selection.type?.let(::isBluetoothType) == true

    private fun deviceOption(device: DeviceDescriptor): DeviceOption {
        val title = typeLabel(device.type, device.name)
        return DeviceOption(
            title = title,
            subtitle = when {
                isBluetoothType(device.type) -> "Bluetooth input"
                device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in input"
                else -> "Connected input"
            },
            mode = MODE_DEVICE,
            type = device.type,
            address = device.address,
            name = title,
            isBluetooth = isBluetoothType(device.type)
        )
    }

    private fun audioInputDevices(context: Context): List<AudioDeviceInfo> =
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to enumerate input devices; missing permission?", e)
            emptyList()
        }

    private fun deviceDescriptors(context: Context, includeBluetooth: Boolean): List<DeviceDescriptor> =
        audioInputDevices(context).mapNotNull { device ->
            val type = device.type
            if (isBluetoothType(type) && !includeBluetooth) return@mapNotNull null
            descriptor(device)
        }

    private fun descriptor(device: AudioDeviceInfo): DeviceDescriptor? =
        try {
            DeviceDescriptor(
                type = device.type,
                address = device.address.orEmpty(),
                name = device.productName?.toString().orEmpty(),
                isSource = device.isSource
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read input device details; skipping device", e)
            null
        }

    private fun isSelectableInputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES

    fun isBluetoothType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun typeLabel(type: Int, productName: String = ""): String {
        val cleanName = productName.trim()
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return "Phone microphone"
        if (cleanName.isNotBlank()) return cleanName
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth microphone"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset microphone"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset microphone"
            else -> "Connected microphone"
        }
    }
}
