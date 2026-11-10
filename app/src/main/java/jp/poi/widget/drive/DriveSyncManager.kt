package jp.poi.widget.drive

import android.content.Context
import android.util.Log
import jp.poi.widget.data.WidgetData
import jp.poi.widget.data.WidgetDataParser
import jp.poi.widget.data.WidgetRepository
import jp.poi.widget.widget.GomaAppWidgetProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

object DriveSyncManager {

    private const val TAG = "DriveSyncManager"
    const val FOLDER_NAME = "goma_widget"
    const val FILE_NAME = "widget.json"

    sealed class SyncResult {
        data class Success(val data: WidgetData) : SyncResult()
        data class Error(val message: String, val throwable: Throwable? = null) : SyncResult()
    }

    suspend fun sync(context: Context): SyncResult = withContext(Dispatchers.IO) {
        val repository = WidgetRepository.getInstance(context)
        repository.setSyncing(true)
        // ウィジェットを更新中表示に更新
        GomaAppWidgetProvider.updateAllWidgets(context)

        try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null || account.account == null) {
                val errorMsg = "Googleアカウントでサインインしていません"
                repository.saveFailure(errorMsg)
                GomaAppWidgetProvider.updateAllWidgets(context)
                return@withContext SyncResult.Error(errorMsg)
            }

            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_READONLY)
            ).apply {
                selectedAccount = account.account
            }

            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("GomaWidget").build()

            // 1. goma_widget フォルダを検索
            val folderQuery = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val folderList = driveService.files().list()
                .setQ(folderQuery)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()

            val folderId = folderList.files?.firstOrNull()?.id

            // 2. widget.json を検索（フォルダ配下、見つからなければ全体検索フォールバック）
            val fileQuery = if (folderId != null) {
                "name = '$FILE_NAME' and '$folderId' in parents and trashed = false"
            } else {
                "name = '$FILE_NAME' and trashed = false"
            }

            var fileList = driveService.files().list()
                .setQ(fileQuery)
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime)")
                .setOrderBy("modifiedTime desc")
                .execute()

            var targetFile = fileList.files?.firstOrNull()

            if (targetFile == null && folderId != null) {
                // フォルダ外にある可能性を考慮したフォールバック検索
                val fallbackQuery = "name = '$FILE_NAME' and trashed = false"
                fileList = driveService.files().list()
                    .setQ(fallbackQuery)
                    .setSpaces("drive")
                    .setFields("files(id, name, modifiedTime)")
                    .setOrderBy("modifiedTime desc")
                    .execute()
                targetFile = fileList.files?.firstOrNull()
            }

            if (targetFile == null) {
                val notFoundMsg = "Google Drive内に '$FOLDER_NAME/$FILE_NAME' が見つかりませんでした"
                repository.saveFailure(notFoundMsg)
                GomaAppWidgetProvider.updateAllWidgets(context)
                return@withContext SyncResult.Error(notFoundMsg, FileNotFoundException(notFoundMsg))
            }

            // 3. ファイルの内容をダウンロード
            val outputStream = ByteArrayOutputStream()
            driveService.files().get(targetFile.id).executeMediaAndDownloadTo(outputStream)
            val jsonContent = outputStream.toString("UTF-8")

            // 4. パースしてリポジトリに保存
            val widgetData = WidgetDataParser.parse(jsonContent)
            repository.saveSuccess(jsonContent)

            // 5. ウィジェットを即時更新
            GomaAppWidgetProvider.updateAllWidgets(context)

            Log.i(TAG, "Sync success! Updated data for: ${widgetData.date}")
            SyncResult.Success(widgetData)
        } catch (e: Exception) {
            Log.e(TAG, "Sync error: ${e.message}", e)
            val errorMsg = e.localizedMessage ?: "Drive同期中にエラーが発生しました"
            repository.saveFailure(errorMsg)
            GomaAppWidgetProvider.updateAllWidgets(context)
            SyncResult.Error(errorMsg, e)
        }
    }
}
