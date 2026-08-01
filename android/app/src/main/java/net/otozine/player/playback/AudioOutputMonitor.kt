package net.otozine.player.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks where audio is actually going.
 *
 * This is context the recommender gets for free and that a stock player throws
 * away. Wired headphones at 11pm is a different listening situation from
 * Bluetooth on a bus, and the queue strategy, EQ profile and bitrate tier should
 * all differ. Phase 0 only reports it; Phase 3 consumes it.
 */
class AudioOutputMonitor(context: Context) {

    enum class Output {
        /** No route we recognise, or nothing playing yet. */
        UNKNOWN,

        /** Phone's own speaker -- usually means the phone is on a table. */
        SPEAKER,

        /** 3.5 mm or USB-C headphones. On the S24 FE this is always USB-C. */
        WIRED,

        /** A2DP Bluetooth. */
        BLUETOOTH,

        /** Cast / remote submix. */
        REMOTE,
    }

    data class State(
        val output: Output = Output.UNKNOWN,
        /** Product name of the device, e.g. "WH-1000XM4". Null when unknown. */
        val deviceName: String? = null,
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, handler)
        refresh()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    fun refresh() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Ordered by how strongly each implies an actual listening situation:
        // if headphones are connected they are what you are listening on, even
        // though the speaker is also technically "available".
        val best = devices.minByOrNull { priority(it.type) }
        _state.value = State(
            output = best?.let { classify(it.type) } ?: Output.UNKNOWN,
            deviceName = best?.productName?.toString()?.takeIf { it.isNotBlank() },
        )
    }

    private fun classify(type: Int): Output = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> Output.WIRED

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> Output.BLUETOOTH

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        -> Output.SPEAKER

        AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        AudioDeviceInfo.TYPE_HDMI,
        -> Output.REMOTE

        else -> Output.UNKNOWN
    }

    private fun priority(type: Int): Int = when (classify(type)) {
        Output.WIRED -> 0
        Output.BLUETOOTH -> 1
        Output.REMOTE -> 2
        Output.SPEAKER -> 3
        Output.UNKNOWN -> 4
    }
}
