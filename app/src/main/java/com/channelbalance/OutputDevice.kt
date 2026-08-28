package com.channelbalance

import android.media.AudioDeviceInfo

/**
 * A connected output device that audio can be routed to.
 */
data class OutputDevice(
    val key: String,
    val name: String,
    val type: Int,
) {

    companion object {
        /** Maps an [AudioDeviceInfo] to a stable [OutputDevice], or null if it is not an output sink. */
        fun from(info: AudioDeviceInfo): OutputDevice? {
            if (!info.isSink) return null
            val type = info.type
            val key = when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    // Bluetooth devices get a stable address-based key when available.
                    val address = info.address
                    if (address.isBlank()) "bluetooth:${type}" else "bt:$address"
                }

                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb:${info.id}"

                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    // All wired headphones share the same physical 3.5mm jack, and the
                    // numeric device id changes across plug-ins, so use one stable key.
                    "wired"
                }

                else -> "${type}:${info.id}"
            }
            val displayName = if (info.productName.isNullOrBlank()) {
                typeName(type)
            } else {
                info.productName.toString()
            }
            return OutputDevice(
                key = key,
                name = displayName,
                type = type,
            )
        }

        fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Speaker"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Analog"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line Digital"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Audio Device"
        }
    }
}
