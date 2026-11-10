package jp.poi.widget.data

import android.content.Context
import android.content.SharedPreferences

class WidgetRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "goma_widget_prefs"
        private const val KEY_LATEST_JSON = "key_latest_json"
        private const val KEY_LAST_FETCH_TIME = "key_last_fetch_time"
        private const val KEY_LAST_STATUS = "key_last_status"
        private const val KEY_IS_SYNCING = "key_is_syncing"

        @Volatile
        private var instance: WidgetRepository? = null

        fun getInstance(context: Context): WidgetRepository {
            return instance ?: synchronized(this) {
                instance ?: WidgetRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    fun saveSuccess(jsonString: String) {
        prefs.edit()
            .putString(KEY_LATEST_JSON, jsonString)
            .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_STATUS, "OK")
            .putBoolean(KEY_IS_SYNCING, false)
            .apply()
    }

    fun saveFailure(errorMessage: String) {
        // 前回取得した JSON (KEY_LATEST_JSON) は消さずに維持する
        prefs.edit()
            .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_STATUS, "ERROR: $errorMessage")
            .putBoolean(KEY_IS_SYNCING, false)
            .apply()
    }

    fun setSyncing(isSyncing: Boolean) {
        prefs.edit().putBoolean(KEY_IS_SYNCING, isSyncing).apply()
    }

    fun isSyncing(): Boolean {
        return prefs.getBoolean(KEY_IS_SYNCING, false)
    }

    fun getLatestData(): WidgetData? {
        val json = prefs.getString(KEY_LATEST_JSON, null) ?: return null
        return try {
            WidgetDataParser.parse(json)
        } catch (e: Exception) {
            null
        }
    }

    fun getLatestJson(): String? {
        return prefs.getString(KEY_LATEST_JSON, null)
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_FETCH_TIME, 0L)
    }

    fun getLastStatus(): String? {
        return prefs.getString(KEY_LAST_STATUS, null)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
