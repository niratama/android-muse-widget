package jp.poi.widget.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class WidgetRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "goma_widget_prefs"
        private const val KEY_LATEST_JSON = "key_latest_json"
        private const val KEY_LAST_FETCH_TIME = "key_last_fetch_time"
        private const val KEY_LAST_STATUS = "key_last_status"
        private const val KEY_IS_SYNCING = "key_is_syncing"
        private const val KEY_GOMA_IMAGE_MODIFIED_TIME = "key_goma_image_modified_time"
        private const val KEY_MUSE_PACKAGE_NAME = "key_muse_package_name"
        private const val GOMA_IMAGE_FILENAME = "goma.png"

        const val DEFAULT_MUSE_PACKAGE = "com.facebook.aura"

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

    // --- goma.png 画像キャッシュ & 安全なリサイズ取得 ---

    private fun getGomaFile(): File = File(context.filesDir, GOMA_IMAGE_FILENAME)

    fun hasGomaImage(): Boolean = getGomaFile().exists()

    fun getGomaImageModifiedTime(): Long = prefs.getLong(KEY_GOMA_IMAGE_MODIFIED_TIME, 0L)

    fun saveGomaImage(inputStream: InputStream, modifiedTimeEpochMs: Long) {
        val file = getGomaFile()
        FileOutputStream(file).use { output ->
            inputStream.copyTo(output)
        }
        prefs.edit()
            .putLong(KEY_GOMA_IMAGE_MODIFIED_TIME, modifiedTimeEpochMs)
            .apply()
    }

    fun deleteGomaImage() {
        val file = getGomaFile()
        if (file.exists()) {
            file.delete()
        }
        prefs.edit().remove(KEY_GOMA_IMAGE_MODIFIED_TIME).apply()
    }

    /**
     * RemoteViews の 1MB 制限 (TransactionTooLargeException) を防ぐため、
     * 安全な解像度（最大幅約450px、高さ約300px）へダウンサンプリングして Bitmap を返します。
     */
    fun getGomaBitmap(maxWidth: Int = 450, maxHeight: Int = 300): Bitmap? {
        val file = getGomaFile()
        if (!file.exists()) return null

        return try {
            // 1. サイズのみ取得
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val rawWidth = options.outWidth
            val rawHeight = options.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return null

            // 2. inSampleSize を計算
            var inSampleSize = 1
            if (rawHeight > maxHeight || rawWidth > maxWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while ((halfHeight / inSampleSize) >= maxHeight && (halfWidth / inSampleSize) >= maxWidth) {
                    inSampleSize *= 2
                }
            }

            // 3. 縮小デコード
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            null
        }
    }

    // --- Muse アプリ設定 ---

    fun getMusePackageName(): String {
        val saved = prefs.getString(KEY_MUSE_PACKAGE_NAME, null)
        return if (saved == null || saved == "com.choosemuse.muse") {
            DEFAULT_MUSE_PACKAGE
        } else {
            saved
        }
    }

    fun setMusePackageName(packageName: String) {
        prefs.edit().putString(KEY_MUSE_PACKAGE_NAME, packageName).apply()
    }

    fun clear() {
        deleteGomaImage()
        prefs.edit().clear().apply()
    }
}
