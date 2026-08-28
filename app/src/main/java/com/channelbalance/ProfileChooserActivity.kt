package com.channelbalance

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Config editor opened from the configs menu (new or editing).
 *
 * The slider is applied to the audio in real time (live preview) via the
 * in-process [ChannelBalancer] singleton so the user hears the change
 * immediately. Saving persists a named config bound to the currently active
 * device (so it auto-applies on reconnect).
 */
class ProfileChooserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONFIG_ID = "config_id"
        const val EXTRA_DEVICE_KEY = "device_key"
        const val EXTRA_DEVICE_NAME = "device_name"

        private const val MIN_DB = -12f
        private const val MAX_DB = 12f
        private const val STEP_RESOLUTION = 100 // 0.1 dB steps
        private const val FINE_STEP_DB = 0.1f
    }

    private var configId: String? = null
    private var deviceKey: String? = null
    private var deviceName: String? = null
    private var currentBalance = 0f

    private lateinit var titleView: TextView
    private lateinit var balanceBar: SeekBar
    private lateinit var balanceLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var configName: EditText

    private lateinit var profileStore: ProfileStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chooser)
        profileStore = ProfileStore(this)

        configId = intent.getStringExtra(EXTRA_CONFIG_ID)
        deviceKey = intent.getStringExtra(EXTRA_DEVICE_KEY)
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)

        if (deviceKey == null) {
            // Fall back to the currently active output device.
            val active = AudioDeviceMonitor(this).activeOutput()
            deviceKey = active?.key
            deviceName = active?.name
        }

        titleView = findViewById(R.id.chooser_title)
        balanceBar = findViewById(R.id.balance_bar)
        balanceLabel = findViewById(R.id.balance_label)
        statusLabel = findViewById(R.id.status_label)
        configName = findViewById(R.id.config_name)

        val existing = configId?.let { profileStore.getById(it) }

        if (existing != null) {
            // Editing an existing config: prefill gains + name, keep its device binding.
            deviceKey = existing.deviceKey ?: deviceKey
            configName.setText(existing.name)
            currentBalance = (existing.rightGainDb - existing.leftGainDb) / 2f
            titleView.text = getString(R.string.chooser_edit_title)
        } else if (deviceKey != null) {
            titleView.text = "${getString(R.string.chooser_title)} · ${deviceName ?: deviceKey}"
        } else {
            titleView.text = getString(R.string.chooser_title)
            balanceBar.isEnabled = false
            balanceLabel.text = getString(R.string.no_device_yet)
        }

        balanceBar.max = stepCount()
        balanceBar.progress = progressForDb(currentBalance)

        ChannelBalancer.onStatusChanged = { attached, message ->
            runOnUiThread {
                statusLabel.text = if (attached) {
                    message
                } else {
                    "Audio path issue: $message"
                }
            }
        }

        balanceBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                currentBalance = dbForProgress(progress)
                updateBalanceLabel()
                if (fromUser) applyLive()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        findViewById<View>(R.id.btn_dec).setOnClickListener { nudgeBalance(-FINE_STEP_DB) }
        findViewById<View>(R.id.btn_inc).setOnClickListener { nudgeBalance(+FINE_STEP_DB) }
        findViewById<View>(R.id.btn_apply).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.btn_neutral).setOnClickListener {
            balanceBar.progress = progressForDb(0f)
            currentBalance = 0f
            updateBalanceLabel()
            ChannelBalancer.clear()
            saveAndFinish()
        }
        findViewById<View>(R.id.btn_cancel).setOnClickListener {
            existing?.let { ChannelBalancer.applyToAll(it) } ?: ChannelBalancer.clear()
            finish()
        }

        updateBalanceLabel()
    }

    /** Applies the current slider value to the audio immediately (live preview). */
    private fun applyLive() {
        ChannelBalancer.applyToAll(
            BalanceProfile(
                id = configId ?: "",
                name = configName.text?.toString()?.trim(),
                deviceKey = deviceKey,
                leftGainDb = -currentBalance,
                rightGainDb = +currentBalance,
            )
        )
    }

    /** Adjusts the balance by [deltaDb] (0.1 dB steps) and applies it live. */
    private fun nudgeBalance(deltaDb: Float) {
        val next = (currentBalance + deltaDb).coerceIn(MIN_DB, MAX_DB)
        if (next == currentBalance) return
        currentBalance = next
        balanceBar.progress = progressForDb(currentBalance)
        updateBalanceLabel()
        applyLive()
    }

    /** Persists the config (with name and device binding) and applies it. */
    private fun saveAndFinish() {
        val name = configName.text?.toString()?.trim()?.ifEmpty { null }
        val saved = profileStore.saveConfig(
            BalanceProfile(
                id = configId ?: "",
                name = name,
                deviceKey = deviceKey,
                leftGainDb = -currentBalance,
                rightGainDb = +currentBalance,
                lastUsedAt = System.currentTimeMillis(),
            )
        )
        profileStore.markUsed(saved.id)
        ChannelBalancer.applyToAll(saved)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ChannelBalancer.onStatusChanged = null
    }

    private fun stepCount(): Int =
        ((MAX_DB - MIN_DB) * STEP_RESOLUTION.toFloat()).toInt()

    private fun progressForDb(db: Float): Int =
        ((db - MIN_DB) * STEP_RESOLUTION.toFloat()).toInt().coerceIn(0, stepCount())

    private fun dbForProgress(progress: Int): Float =
        MIN_DB + progress / STEP_RESOLUTION.toFloat()

    private fun updateBalanceLabel() {
        val abs = kotlin.math.abs(currentBalance)
        val text = when {
            currentBalance == 0f -> "Balanced (0 dB)"
            currentBalance > 0f -> "Boosts right channel · +%.1f dB".format(abs)
            else -> "Boosts left channel · +%.1f dB".format(abs)
        }
        balanceLabel.text = text
    }
}
