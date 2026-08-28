package com.channelbalance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.audiofx.AudioEffect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that lives as long as the user keeps it active.
 *
 * Responsibilities:
 *  - monitor output devices via [AudioDeviceMonitor]
 *  - when a device connects, auto-apply the most recently used named config
 *    bound to that device (configs are created in [ProfileListActivity])
 *  - attach a per-session DynamicsProcessing effect to each media audio session,
 *    pushing the active config's gains into it (unrooted, per-player)
 *
 * A foreground service is required on Android 8+ (API 26+) for a background
 * process to run for an extended period, so this is the supported way to
 * "run forever".
 */
class BalanceService : Service(), AudioDeviceMonitor.OnActiveDeviceListener {

    private lateinit var profileStore: ProfileStore
    private lateinit var monitor: AudioDeviceMonitor

    /**
     * Media apps broadcast this (public) intent each time they open an audio
     * effect control session. It is the supported way to discover the session ID
     * of audio started by *other* apps without root, letting us attach a
     * per-session DynamicsProcessing effect.
     *
     * Must be registered dynamically while this foreground service is running
     * because Android 8+ (API 26+) does not deliver implicit broadcasts to
     * manifest-declared receivers.
     */
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) return
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return
            // attachSession auto-applies the ChannelBalancer's active profile.
            ChannelBalancer.attachSession(session)
            Log.i(TAG, "Audio session opened: $session")
        }
    }

    companion object {
        private const val TAG = "BalanceService"
        private const val CHANNEL_ID = "channel_balance_service"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, BalanceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BalanceService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        profileStore = ProfileStore(this)
        monitor = AudioDeviceMonitor(this).apply {
            onActiveDeviceListener = this@BalanceService
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        monitor.start()

        // Register for per-session audio effect control broadcasts (implicit →
        // must be dynamic while this service is in the foreground).
        val filter = IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ requires an explicit exported flag; media apps broadcast
            // from other processes, so this receiver must be exported.
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
        }

        Log.i(TAG, "BalanceService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleConnectedDevice()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionReceiver) }
        monitor.stop()
        ChannelBalancer.release()
        Log.i(TAG, "BalanceService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Device listener -------------------------------------------------

    override fun onActiveDeviceChanged() {
        handleConnectedDevice()
    }

    /**
     * Apply the most recently used named config bound to the active device, if
     * any. No popups: the user manages which config applies via the menu.
     */
    private fun handleConnectedDevice() {
        val device = monitor.activeOutput() ?: return
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            // Default speaker: no config needed, neutral balance.
            ChannelBalancer.clear()
            return
        }

        val config = profileStore.lastUsedForDevice(device.key)
        if (config != null) {
            ChannelBalancer.applyToAll(config)
            Log.i(TAG, "Applied '${config.name ?: config.id}' to ${device.name} (${device.key})")
        } else {
            Log.i(TAG, "Device ${device.name} (${device.key}) has no bound config yet")
        }
    }

    // --- Notification ----------------------------------------------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // "Configs" quick action opens the config menu, even from the background.
        val configsIntent = Intent(this, ProfileListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val configsPending = PendingIntent.getActivity(
            this,
            1,
            configsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val configsAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_manage),
            getString(R.string.action_configs),
            configsPending
        ).build()

        return builder
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(configsAction)
            .setOngoing(true)
            .build()
    }
}
