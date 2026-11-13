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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream

object DriveSyncManager {

    private const val TAG = "DriveSyncManager"
    const val FOLDER_NAME = "goma_widget"
    const val FILE_NAME = "widget.json"
    const val IMAGE_NAME = "goma.png"
    private const val MAX_JSON_SIZE_BYTES = 1L * 1024 * 1024
    private const val MAX_IMAGE_SIZE_BYTES = 20L * 1024 * 1024
    private val syncMutex = Mutex()

    sealed class SyncResult {
        data class Success(val data: WidgetData) : SyncResult()
        data class Error(val message: String, val throwable: Throwable? = null) : SyncResult()
    }

    suspend fun sync(context: Context): SyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock {
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

            // 1. goma_widget フォルダを検索（自分が所有するフォルダのみ）
            val folderQuery = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and 'me' in owners and trashed = false"
            val folderList = driveService.files().list()
                .setQ(folderQuery)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()

            val folderId = folderList.files?.firstOrNull()?.id

            // 2. widget.json を検索（フォルダ配下、見つからなければ所有ファイル全体からフォールバック）
            val targetFile = findOwnedFile(driveService, FILE_NAME, folderId)

            if (targetFile == null) {
                val notFoundMsg = "Google Drive内に '$FOLDER_NAME/$FILE_NAME' が見つかりませんでした"
                repository.saveFailure(notFoundMsg)
                GomaAppWidgetProvider.updateAllWidgets(context)
                return@withContext SyncResult.Error(notFoundMsg, FileNotFoundException(notFoundMsg))
            }

            // 3. widget.json のサイズ検証後にダウンロードして保存
            val jsonFileSize = targetFile.getSize() ?: 0L
            if (jsonFileSize > MAX_JSON_SIZE_BYTES) {
                val sizeMsg = "$FILE_NAME のサイズが上限を超えています (${jsonFileSize} bytes)"
                repository.saveFailure(sizeMsg)
                GomaAppWidgetProvider.updateAllWidgets(context)
                return@withContext SyncResult.Error(sizeMsg)
            }

            val jsonContent = driveService.files().get(targetFile.id)
                .executeMediaAsInputStream().use { inStream ->
                    readBoundedUtf8(inStream, MAX_JSON_SIZE_BYTES)
                }

            val widgetData = WidgetDataParser.parse(jsonContent)
            repository.saveSuccess(jsonContent)

            // 4. goma.png の検索 & 差分ダウンロード（存在する場合のみ、更新日時で差分取得）
            try {
                val targetImage = findOwnedFile(driveService, IMAGE_NAME, folderId)

                if (targetImage != null) {
                    val imgSize = targetImage.getSize() ?: 0L
                    if (imgSize > MAX_IMAGE_SIZE_BYTES) {
                        Log.w(TAG, "Skipping goma.png download: size exceeds limit ($imgSize bytes)")
                    } else {
                        val remoteModifiedTime = targetImage.modifiedTime?.value ?: 0L
                        val localModifiedTime = repository.getGomaImageModifiedTime()

                        if (remoteModifiedTime == 0L || remoteModifiedTime != localModifiedTime || !repository.hasGomaImage()) {
                            Log.i(TAG, "Downloading goma.png (modifiedTime: $remoteModifiedTime)")
                            driveService.files().get(targetImage.id).executeMediaAsInputStream().use { inStream ->
                                repository.saveGomaImage(inStream, remoteModifiedTime, MAX_IMAGE_SIZE_BYTES)
                            }
                        } else {
                            Log.i(TAG, "goma.png is up to date, skipping download")
                        }
                    }
                } else {
                    // Drive 上に画像が見つからない場合は、ローカルキャッシュも削除して非表示にする
                    if (repository.hasGomaImage()) {
                        repository.deleteGomaImage()
                    }
                }
            } catch (imgEx: CancellationException) {
                throw imgEx
            } catch (imgEx: Exception) {
                // 画像取得の例外は JSON の更新成功を阻害しないようログのみ
                Log.w(TAG, "Failed to sync goma.png: ${imgEx.message}")
            }

            // 5. ウィジェットを即時更新
            GomaAppWidgetProvider.updateAllWidgets(context)

            Log.i(TAG, "Sync success! Updated data for: ${widgetData.date}")
            SyncResult.Success(widgetData)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync error: ${e.message}", e)
            val errorMsg = e.localizedMessage ?: "Drive同期中にエラーが発生しました"
            repository.saveFailure(errorMsg)
            GomaAppWidgetProvider.updateAllWidgets(context)
            SyncResult.Error(errorMsg, e)
        } finally {
            if (repository.isSyncing()) {
                repository.setSyncing(false)
                GomaAppWidgetProvider.updateAllWidgets(context)
            }
        }
        }
    }

    private fun findOwnedFile(
        driveService: Drive,
        fileName: String,
        folderId: String?
    ): com.google.api.services.drive.model.File? {
        val primaryQuery = if (folderId != null) {
            "name = '$fileName' and '$folderId' in parents and 'me' in owners and trashed = false"
        } else {
            "name = '$fileName' and 'me' in owners and trashed = false"
        }

        val primaryList = driveService.files().list()
            .setQ(primaryQuery)
            .setSpaces("drive")
            .setFields("files(id, name, modifiedTime, size)")
            .setOrderBy("modifiedTime desc")
            .execute()

        primaryList.files?.firstOrNull()?.let { return it }

        if (folderId != null) {
            val fallbackQuery = "name = '$fileName' and 'me' in owners and trashed = false"
            val fallbackList = driveService.files().list()
                .setQ(fallbackQuery)
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime, size)")
                .setOrderBy("modifiedTime desc")
                .execute()
            return fallbackList.files?.firstOrNull()
        }
        return null
    }

    private fun readBoundedUtf8(inputStream: InputStream, maxBytes: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IllegalStateException("File size exceeds limit ($maxBytes bytes)")
            }
            output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }
}
