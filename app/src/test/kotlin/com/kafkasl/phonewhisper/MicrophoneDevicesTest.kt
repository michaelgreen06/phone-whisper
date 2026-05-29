package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophoneDevicesTest {

    @Test
    fun `auto selection resolves no explicit device`() {
        val selection = MicrophoneDevices.SavedSelection(
            mode = MicrophoneDevices.MODE_AUTO,
            type = MicrophoneDevices.TYPE_BUILTIN_MIC,
            address = "built-in",
            name = "Phone microphone"
        )

        val match = MicrophoneDevices.matchSelectedDevice(selection, listOf(builtIn()))

        assertNull(match)
    }

    @Test
    fun `built in microphone falls back by type when address changes`() {
        val selection = MicrophoneDevices.SavedSelection(
            mode = MicrophoneDevices.MODE_DEVICE,
            type = MicrophoneDevices.TYPE_BUILTIN_MIC,
            address = "old-address",
            name = "Phone microphone"
        )

        val match = MicrophoneDevices.matchSelectedDevice(selection, listOf(builtIn(address = "new-address")))

        assertEquals("new-address", match?.address)
    }

    @Test
    fun `bluetooth microphone matches by type and address`() {
        val selection = MicrophoneDevices.SavedSelection(
            mode = MicrophoneDevices.MODE_DEVICE,
            type = MicrophoneDevices.TYPE_BLUETOOTH_SCO,
            address = "bt-1",
            name = "Headset"
        )

        val match = MicrophoneDevices.matchSelectedDevice(
            selection,
            listOf(
                bluetooth(address = "bt-2", name = "Other Headset"),
                bluetooth(address = "bt-1", name = "Headset")
            )
        )

        assertEquals("Headset", match?.name)
    }

    @Test
    fun `bluetooth microphone does not fall back by type only`() {
        val selection = MicrophoneDevices.SavedSelection(
            mode = MicrophoneDevices.MODE_DEVICE,
            type = MicrophoneDevices.TYPE_BLUETOOTH_SCO,
            address = "missing",
            name = "Headset"
        )

        val match = MicrophoneDevices.matchSelectedDevice(selection, listOf(bluetooth(address = "bt-1")))

        assertNull(match)
    }

    @Test
    fun `subtitle names auto and selected microphone`() {
        assertEquals(
            "Auto",
            MicrophoneDevices.subtitle(
                MicrophoneDevices.SavedSelection(MicrophoneDevices.MODE_AUTO, null, "", "")
            )
        )
        assertEquals(
            "Headset",
            MicrophoneDevices.subtitle(
                MicrophoneDevices.SavedSelection(
                    MicrophoneDevices.MODE_DEVICE,
                    MicrophoneDevices.TYPE_BLUETOOTH_SCO,
                    "bt-1",
                    "Headset"
                )
            )
        )
    }

    private fun builtIn(address: String = "built-in") =
        MicrophoneDevices.DeviceDescriptor(
            type = MicrophoneDevices.TYPE_BUILTIN_MIC,
            address = address,
            name = "Phone microphone"
        )

    private fun bluetooth(address: String, name: String = "Headset") =
        MicrophoneDevices.DeviceDescriptor(
            type = MicrophoneDevices.TYPE_BLUETOOTH_SCO,
            address = address,
            name = name
        )
}
