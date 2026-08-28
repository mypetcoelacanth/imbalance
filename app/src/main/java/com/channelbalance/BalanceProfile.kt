package com.channelbalance

import java.util.Locale

/**
 * A named channel imbalance compensation config: independent gain (in dB)
 * applied to the left and right channels.
 *
 * Configs are identified by a unique [id] so that multiple configs (e.g. for
 * different IEM models) can share the same [deviceKey] — exactly what happens
 * when several earphones are plugged into the same 3.5 mm audio jack.
 *
 * @property name human-readable label (e.g. the IEM model).
 * @property deviceKey optional device this config is bound to; when set, the
 *   config auto-applies whenever that device connects.
 * @property lastUsedAt epoch ms of the last time this config was applied, used
 *   to pick the most recent config for a device.
 */
data class BalanceProfile(
    val id: String,
    val name: String? = null,
    val deviceKey: String? = null,
    val leftGainDb: Float,
    val rightGainDb: Float,
    val lastUsedAt: Long = 0L,
) {
    fun isNeutral(): Boolean =
        leftGainDb == 0f && rightGainDb == 0f

    /** Human readable summary, e.g. "L +2.5 / R -1.0 dB". */
    fun summary(): String = String.format(
        Locale.US,
        "L %+.1f / R %+.1f dB",
        leftGainDb,
        rightGainDb
    )
}
