package com.channelbalance

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies a [BalanceProfile] to active media audio sessions using the
 * DynamicsProcessing AudioEffect.
 *
 * DynamicsProcessing is the only public (non-hidden) Android API that exposes
 * independent per-channel gain, and it is available from API 28 (Android 9)
 * onward, which is exactly the minSdk of this app.
 *
 * Attaching an insert effect to the global output mix (session 0) is deprecated
 * and, on OEM builds such as Xiaomi/MIUI, blocked by the platform's
 * MODIFY_DEFAULT_AUDIO_EFFECTS UID whitelist. The reliable unrooted approach is
 * therefore per-session: attach a DynamicsProcessing instance to each media
 * app's individual audio session (session id != 0) discovered via
 * AudioPlaybackConfiguration callbacks. Per-player session effects are fully
 * supported and not subject to the global whitelist.
 *
 * This is a process-wide singleton so that both the [BalanceService] and the
 * [ProfileChooserActivity] can apply gains instantly (live slider preview)
 * without IPC round-trips.
 *
 * Channel indices: channel 0 is front-left, channel 1 is front-right.
 */
object ChannelBalancer {

    private const val TAG = "ChannelBalancer"
    private const val PRIORITY = 0
    private const val CH_LEFT = 0
    private const val CH_RIGHT = 1
    private const val MAX_GAIN_DB = 12f

    /** Notified when the underlying effect attaches/fails so the UI can report the real state. */
    var onStatusChanged: ((attached: Boolean, message: String) -> Unit)? = null

    /**
     * One DynamicsProcessing per active audio session id. Session ids are
     * system-unique for a set of audio streams; keys 0 are ignored (global mix).
     */
    private val effects = ConcurrentHashMap<Int, DynamicsProcessing>()

    private val lock = Any()

    /**
     * The gains currently in effect. Any session attached later is immediately
     * given these gains, so applying a config now still affects audio sessions
     * that start afterwards (fixes "works sometimes").
     */
    @Volatile
    private var activeProfile: BalanceProfile? = null

    @Volatile
    var attachedSessionCount: Int = 0
        private set

    /** Number of distinct audio sessions currently under our control. */
    @Volatile
    var isAttached: Boolean = false
        private set

    /**
     * Ensure a DynamicsProcessing instance is attached to [sessionId].
     * Sessions (positive ids) are per-player; session 0 is the global mix and
     * is intentionally skipped (deprecated + OEM-blocked).
     */
    fun attachSession(sessionId: Int) {
        if (sessionId <= 0) return
        synchronized(lock) {
            if (effects.containsKey(sessionId)) return
            try {
                val config = buildConfig()
                val dp = DynamicsProcessing(PRIORITY, sessionId, config)
                dp.enabled = true
                effects[sessionId] = dp
                updateAttachedState()
                Log.i(TAG, "DynamicsProcessing attached to session $sessionId")
                onStatusChanged?.invoke(isAttached, "Attached (${attachedSessionCount} sessions)")
                // Immediately apply the active gains so nothing is missed.
                activeProfile?.let { apply(sessionId, it) }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to attach DynamicsProcessing to session $sessionId", t)
                onStatusChanged?.invoke(
                    isAttached,
                    "Attach failed on session $sessionId: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    /** Push the current gain profile into the effect for [sessionId]. */
    fun apply(sessionId: Int, profile: BalanceProfile) {
        synchronized(lock) {
            val dp = effects[sessionId] ?: return
            try {
                setChannelGains(dp, clamp(profile.leftGainDb), clamp(profile.rightGainDb))
                Log.i(TAG, "Applied ${profile.summary()} to session $sessionId")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to apply gains to session $sessionId", t)
                onStatusChanged?.invoke(isAttached, "Apply failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Apply [profile] to every tracked session and remember it as the active
     * profile so any session that starts later also gets it.
     */
    fun applyToAll(profile: BalanceProfile) {
        synchronized(lock) {
            activeProfile = profile
            effects.keys.forEach { apply(it, profile) }
        }
    }

    /** Reset all channels on every tracked session to neutral (0 dB) and release them. */
    fun clear() {
        synchronized(lock) {
            activeProfile = null
            effects.keys.forEach { sid ->
                try {
                    effects.remove(sid)?.release()
                    Log.i(TAG, "Cleared session $sid")
                } catch (t: Throwable) {
                    Log.w(TAG, "Clear failure on session $sid", t)
                }
            }
            isAttached = false
            attachedSessionCount = 0
        }
    }

    /** Releases every native effect. Safe to call repeatedly. */
    fun release() {
        synchronized(lock) {
            activeProfile = null
            effects.keys.forEach { sid ->
                try {
                    effects.remove(sid)?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "Release failure on session $sid (ignored)", t)
                }
            }
            isAttached = false
            attachedSessionCount = 0
        }
    }

    /** Drop control of a session that has gone away; keep other sessions. */
    fun detachSession(sessionId: Int) {
        synchronized(lock) {
            effects.remove(sessionId)?.let {
                try {
                    it.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "Detach release failure on session $sessionId (ignored)", t)
                }
                updateAttachedState()
                Log.i(TAG, "Detached session $sessionId")
            }
        }
    }

    private fun updateAttachedState() {
        attachedSessionCount = effects.size
        isAttached = effects.isNotEmpty()
    }

    private fun setChannelGains(dp: DynamicsProcessing, leftDb: Float, rightDb: Float) {
        dp.setInputGainAllChannelsTo(0f)
        dp.setInputGainbyChannel(CH_LEFT, leftDb)
        dp.setInputGainbyChannel(CH_RIGHT, rightDb)
    }

    private fun buildConfig(): DynamicsProcessing.Config =
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            false, 0,
            false, 0,
            false, 0,
            false
        )
            .setInputGainAllChannelsTo(0f)
            .setInputGainByChannelIndex(CH_LEFT, 0f)
            .setInputGainByChannelIndex(CH_RIGHT, 0f)
            .build()

    private fun clamp(v: Float): Float =
        v.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
}
