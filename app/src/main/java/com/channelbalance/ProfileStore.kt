package com.channelbalance

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists named [BalanceProfile] configs in SharedPreferences.
 *
 * Configs are keyed by a unique id (not by device) so several configs can share
 * the same device — e.g. multiple IEMs plugged into the same audio jack. Each
 * config optionally records the [BalanceProfile.deviceKey] it is bound to, which
 * selects the most recently used config auto-applied when that device connects.
 *
 * Backwards compatibility: any profiles saved by the old per-device schema are
 * migrated into named configs on first access.
 */
class ProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("balance_profiles", Context.MODE_PRIVATE)

    private val configsKey = "configs_json"
    private val idCounterKey = "next_config_id"

    /** Applies one-time migrations of legacy data into the current config format. */
    private val migrated: Boolean by lazy {
        val hadOld = prefs.contains("left_") || prefs.all.keys.any { it.startsWith("left_") }
        migrateOldSchema()
        rebindOldWiredKeys()
        hadOld
    }

    fun allConfigs(): List<BalanceProfile> {
        migrated
        return readConfigs()
    }

    fun getById(id: String): BalanceProfile? =
        allConfigs().firstOrNull { it.id == id }

    /** Insert or update a config, keeping its original id if present. */
    fun saveConfig(profile: BalanceProfile): BalanceProfile {
        migrated
        val list = readConfigs().toMutableList()
        val existingIndex = list.indexOfFirst { it.id == profile.id }
        val toSave = if (existingIndex >= 0) profile else profile.copy(id = newId())
        if (existingIndex >= 0) {
            list[existingIndex] = toSave
        } else {
            list.add(toSave)
        }
        writeConfigs(list)
        return toSave
    }

    fun deleteConfig(id: String) {
        migrated
        writeConfigs(readConfigs().filterNot { it.id == id })
    }

    /** Configs bound to [deviceKey], most recently used first. */
    fun configsForDevice(deviceKey: String): List<BalanceProfile> =
        allConfigs()
            .filter { it.deviceKey == deviceKey }
            .sortedByDescending { it.lastUsedAt }

    /** Most recently used config bound to [deviceKey], if any. */
    fun lastUsedForDevice(deviceKey: String): BalanceProfile? =
        configsForDevice(deviceKey).firstOrNull()

    /** Bump the last-used timestamp so it becomes the default for its device. */
    fun markUsed(id: String) {
        val profile = getById(id) ?: return
        saveConfig(profile.copy(lastUsedAt = System.currentTimeMillis()))
    }

    // --- persistence -------------------------------------------------------

    private fun readConfigs(): List<BalanceProfile> {
        val raw = prefs.getString(configsKey, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrElse { emptyList() }
    }

    private fun writeConfigs(list: List<BalanceProfile>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(toJson(p)) }
        prefs.edit().putString(configsKey, arr.toString()).apply()
    }

    private fun toJson(p: BalanceProfile): JSONObject =
        JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("device", p.deviceKey)
            .put("left", p.leftGainDb.toDouble())
            .put("right", p.rightGainDb.toDouble())
            .put("used", p.lastUsedAt)

    private fun fromJson(o: JSONObject): BalanceProfile =
        BalanceProfile(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = if (o.has("name") && !o.isNull("name")) o.getString("name") else null,
            deviceKey = if (o.has("device") && !o.isNull("device")) o.getString("device") else null,
            leftGainDb = o.optDouble("left", 0.0).toFloat(),
            rightGainDb = o.optDouble("right", 0.0).toFloat(),
            lastUsedAt = o.optLong("used", 0L),
        )

    private fun newId(): String = "cfg${prefs.getInt(idCounterKey, 0)}".also {
        prefs.edit().putInt(idCounterKey, prefs.getInt(idCounterKey, 0) + 1).apply()
    }

    /** Convert the old single-value-per-device schema into named configs. */
    private fun migrateOldSchema() {
        val oldKeys = prefs.all.keys
            .filter { it.startsWith("left_") }
            .map { it.removePrefix("left_") }
        if (oldKeys.isEmpty()) return

        val list = readConfigs().toMutableList()
        oldKeys.forEach { deviceKey ->
            val left = prefs.getFloat("left_$deviceKey", 0f)
            val right = prefs.getFloat("right_$deviceKey", 0f)
            val name = prefs.getString("name_$deviceKey", null)
            list.add(
                BalanceProfile(
                    id = newId(),
                    name = name,
                    deviceKey = deviceKey,
                    leftGainDb = left,
                    rightGainDb = right,
                    lastUsedAt = System.currentTimeMillis(),
                )
            )
            prefs.edit()
                .remove("left_$deviceKey")
                .remove("right_$deviceKey")
                .remove("name_$deviceKey")
                .apply()
        }
        writeConfigs(list)
    }

    /**
     * Rebinds configs saved under the old, unstable wired key ("TYPE:deviceId")
     * to the stable "wired" key, so saved IEM configs auto-apply across plug-ins.
     * TYPE_WIRED_HEADPHONES = 3, TYPE_WIRED_HEADSET = 4.
     */
    private fun rebindOldWiredKeys() {
        val list = readConfigs().toMutableList()
        var changed = false
        for (i in list.indices) {
            val dev = list[i].deviceKey ?: continue
            val prefix = dev.substringBefore(':')
            if (prefix == "3" || prefix == "4") {
                list[i] = list[i].copy(deviceKey = "wired")
                changed = true
            }
        }
        if (changed) writeConfigs(list)
    }
}
