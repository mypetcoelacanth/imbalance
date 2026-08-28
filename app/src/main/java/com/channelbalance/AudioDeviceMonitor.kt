package com.channelbalance

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Watches the audio device graph for connection/disconnection of output sinks
 * and resolves the currently active output device.
 *
 * Uses [AudioDeviceCallback] (available since API 23, well below our minSdk 28)
 * so it works on Android 9 through 16+.
 */
class AudioDeviceMonitor(context: Context) {

    /** Notified whenever the active output device changes (connect or disconnect). */
    interface OnActiveDeviceListener {
        fun onActiveDeviceChanged()
    }

    var onActiveDeviceListener: OnActiveDeviceListener? = null

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var currentActiveKey: String? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            notifyIfActiveChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            notifyIfActiveChanged()
        }
    }

    private fun notifyIfActiveChanged() {
        val activeKey = activeOutput()?.key
        if (activeKey != currentActiveKey) {
            currentActiveKey = activeKey
            onActiveDeviceListener?.onActiveDeviceChanged()
        }
    }

    /** Lists every currently connected output sink, most relevant first. */
    fun currentOutputs(): List<OutputDevice> {
        val outputs = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull { OutputDevice.from(it) }
        return prioritize(outputs)
    }

    /**
     * Picks the single device most likely to be carrying audio right now.
     * Precedence: wired > Bluetooth > USB > built-in speaker.
     */
    fun activeOutput(): OutputDevice? {
        val list = currentOutputs()
        fun hasAny(vararg types: Int): OutputDevice? = list.firstOrNull {
            types.any { t -> it.type == t }
        }

        return hasAny(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        ) ?: hasAny(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        ) ?: hasAny(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        ) ?: hasAny(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        ) ?: list.firstOrNull()
    }

    /** Sorts a device list so the most relevant sink comes first. */
    private fun prioritize(list: List<OutputDevice>): List<OutputDevice> {
        fun rank(o: OutputDevice): Int = when (o.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> 0

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> 1

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> 2

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
            else -> 4
        }
        return list.sortedBy { rank(it) }
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        notifyIfActiveChanged()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }
}
