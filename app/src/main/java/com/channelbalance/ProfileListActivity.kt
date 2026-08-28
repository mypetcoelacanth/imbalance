package com.channelbalance

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The "configs" menu: lists every saved named config, letting the user apply
 * (Use), edit, or delete any of them, plus create a new one for the current
 * device.
 */
class ProfileListActivity : AppCompatActivity() {

    private lateinit var profileStore: ProfileStore
    private lateinit var listView: ListView
    private lateinit var activeLabel: TextView
    private var adapter: ConfigAdapter? = null

    private var configs: List<BalanceProfile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_list)
        profileStore = ProfileStore(this)

        listView = findViewById(R.id.config_list)
        activeLabel = findViewById(R.id.active_device_label)
        findViewById<View>(R.id.btn_new_config).setOnClickListener { newConfig() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        configs = profileStore.allConfigs()

        val active = AudioDeviceMonitor(this).activeOutput()
        activeLabel.text = if (active != null) {
            "Active device: ${active.name}"
        } else {
            "No audio device connected"
        }

        if (adapter == null) {
            adapter = ConfigAdapter()
            listView.adapter = adapter
        }
        adapter!!.notifyDataSetChanged()
    }

    /** Apply a config now, binding it to the active device if it has none. */
    private fun useConfig(config: BalanceProfile) {
        val active = AudioDeviceMonitor(this).activeOutput()
        var updated = config
        if (updated.deviceKey == null && active != null) {
            updated = updated.copy(deviceKey = active.key)
        }
        profileStore.markUsed(updated.id)
        if (updated.deviceKey == null) {
            // No device binding (nothing connected): persist the binding change anyway if set above.
            profileStore.saveConfig(updated)
        }
        ChannelBalancer.applyToAll(updated)
        finish()
    }

    private fun editConfig(config: BalanceProfile) {
        val intent = Intent(this, ProfileChooserActivity::class.java)
            .putExtra(ProfileChooserActivity.EXTRA_CONFIG_ID, config.id)
        startActivity(intent)
    }

    private fun deleteConfig(config: BalanceProfile) {
        profileStore.deleteConfig(config.id)
        ChannelBalancer.applyToAll(BalanceProfile(id = "", leftGainDb = 0f, rightGainDb = 0f))
        refresh()
    }

    private fun newConfig() {
        val intent = Intent(this, ProfileChooserActivity::class.java)
        startActivity(intent)
    }

    private inner class ConfigAdapter : BaseAdapter() {
        override fun getCount(): Int = configs.size
        override fun getItem(position: Int): BalanceProfile = configs[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_config, parent, false)
            val config = configs[position]

            view.findViewById<TextView>(R.id.config_name).text = config.name ?: "Unnamed config"
            view.findViewById<TextView>(R.id.config_detail).text = buildString {
                append(config.summary())
                config.deviceKey?.let {
                    append("  ·  bound to device")
                }
            }

            view.findViewById<View>(R.id.btn_config_apply).setOnClickListener { useConfig(config) }
            view.findViewById<View>(R.id.btn_config_edit).setOnClickListener { editConfig(config) }
            view.findViewById<View>(R.id.btn_config_delete).setOnClickListener { deleteConfig(config) }
            return view
        }
    }
}
