package jp.poi.widget

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.poi.widget.data.WidgetData
import jp.poi.widget.data.WidgetDataParser
import jp.poi.widget.data.WidgetRepository
import jp.poi.widget.drive.DriveSyncManager
import jp.poi.widget.widget.GomaAppWidgetProvider
import jp.poi.widget.widget.GomaSyncWorker
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.button.MaterialButton
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var repository: WidgetRepository

    private lateinit var tvAccountStatus: TextView
    private lateinit var btnSignIn: MaterialButton
    private lateinit var btnSignOut: MaterialButton
    private lateinit var tvSyncStatus: TextView
    private lateinit var btnSyncNow: MaterialButton
    private lateinit var progressSync: ProgressBar

    // Preview views
    private lateinit var previewContainer: View
    private lateinit var previewEmptyText: TextView
    private lateinit var previewLineWeather: TextView
    private lateinit var previewLineSleep: TextView
    private lateinit var previewLineWeight: TextView
    private lateinit var previewLineSchedule: TextView
    private lateinit var previewOldWarning: TextView
    private lateinit var previewFooterStatus: TextView
    private lateinit var previewBtnRefresh: ImageView
    private lateinit var previewBgImage: ImageView
    private lateinit var previewScrim: View
    private lateinit var previewMuseHotspot: View

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                Toast.makeText(this, "サインインしました: ${account.email}", Toast.LENGTH_SHORT).show()
                updateUI(account)
                // 定期同期タスクを登録し、初回取得を実行
                GomaSyncWorker.schedulePeriodicSync(this)
                triggerSync()
            } catch (e: ApiException) {
                Toast.makeText(this, "サインインに失敗しました (code: ${e.statusCode})", Toast.LENGTH_LONG).show()
                updateUI(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = WidgetRepository.getInstance(this)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        initViews()

        // 初期状態でキャッシュが無ければ assets のサンプルをロード
        if (repository.getLatestData() == null) {
            loadInitialSampleData()
        }

        val account = GoogleSignIn.getLastSignedInAccount(this)
        updateUI(account)

        // 定期同期タスクの確実な登録
        GomaSyncWorker.schedulePeriodicSync(this)
    }

    private fun initViews() {
        tvAccountStatus = findViewById(R.id.tv_account_status)
        btnSignIn = findViewById(R.id.btn_sign_in)
        btnSignOut = findViewById(R.id.btn_sign_out)
        tvSyncStatus = findViewById(R.id.tv_sync_status)
        btnSyncNow = findViewById(R.id.btn_sync_now)
        progressSync = findViewById(R.id.progress_sync)

        // Preview views inside included widget_layout
        val previewRoot = findViewById<View>(R.id.preview_widget)
        previewContainer = previewRoot.findViewById(R.id.widget_content_container)
        previewEmptyText = previewRoot.findViewById(R.id.widget_empty_text)
        previewLineWeather = previewRoot.findViewById(R.id.widget_line_weather)
        previewLineSleep = previewRoot.findViewById(R.id.widget_line_sleep)
        previewLineWeight = previewRoot.findViewById(R.id.widget_line_weight)
        previewLineSchedule = previewRoot.findViewById(R.id.widget_line_schedule_garbage)
        previewOldWarning = previewRoot.findViewById(R.id.widget_old_warning)
        previewFooterStatus = previewRoot.findViewById(R.id.widget_footer_status)
        previewBtnRefresh = previewRoot.findViewById(R.id.widget_btn_refresh)
        previewBgImage = previewRoot.findViewById(R.id.widget_bg_image)
        previewScrim = previewRoot.findViewById(R.id.widget_scrim)
        previewMuseHotspot = previewRoot.findViewById(R.id.widget_muse_hotspot)

        btnSignIn.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            signInLauncher.launch(signInIntent)
        }

        btnSignOut.setOnClickListener {
            googleSignInClient.signOut().addOnCompleteListener {
                Toast.makeText(this, "サインアウトしました", Toast.LENGTH_SHORT).show()
                updateUI(null)
                GomaAppWidgetProvider.updateAllWidgets(this)
            }
        }

        btnSyncNow.setOnClickListener {
            triggerSync()
        }

        previewBtnRefresh.setOnClickListener {
            triggerSync()
        }

        previewMuseHotspot.setOnClickListener {
            launchMuseApp()
        }
    }

    private fun launchMuseApp() {
        val packageName = repository.getMusePackageName()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, getString(R.string.muse_app_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(account: GoogleSignInAccount?) {
        if (account != null) {
            tvAccountStatus.text = getString(R.string.status_signed_in, account.email)
            btnSignIn.visibility = View.GONE
            btnSignOut.visibility = View.VISIBLE
            btnSyncNow.isEnabled = true
        } else {
            tvAccountStatus.text = getString(R.string.status_not_signed_in)
            btnSignIn.visibility = View.VISIBLE
            btnSignOut.visibility = View.GONE
            btnSyncNow.isEnabled = false
        }

        val lastStatus = repository.getLastStatus()
        val lastTime = repository.getLastSyncTime()
        if (lastTime > 0) {
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN)
            val timeStr = sdf.format(Date(lastTime))
            val hasImg = if (repository.hasGomaImage()) "（画像あり）" else ""
            tvSyncStatus.text = "最終同期: $timeStr\nステータス: ${lastStatus ?: "不明"} $hasImg"
        } else {
            tvSyncStatus.text = "同期はまだ実行されていません。"
        }

        updatePreview(repository.getLatestData())
    }

    private fun updatePreview(data: WidgetData?) {
        // 画像プレビュー表示
        val gomaBitmap = repository.getGomaBitmap()
        if (gomaBitmap != null) {
            previewBgImage.visibility = View.VISIBLE
            previewScrim.visibility = View.VISIBLE
            previewBgImage.setImageBitmap(gomaBitmap)
            previewMuseHotspot.visibility = View.VISIBLE
        } else {
            previewBgImage.visibility = View.GONE
            previewScrim.visibility = View.GONE
            previewMuseHotspot.visibility = View.GONE
        }

        if (data == null) {
            previewContainer.visibility = View.GONE
            previewEmptyText.visibility = View.VISIBLE
            previewEmptyText.text = getString(R.string.widget_no_data)
            return
        }

        previewContainer.visibility = View.VISIBLE
        previewEmptyText.visibility = View.GONE

        // 1行目: 天気
        previewLineWeather.text = data.formatWeatherLine()

        // 2行目: 睡眠
        val sleepLine = data.formatSleepLine()
        if (sleepLine != null) {
            previewLineSleep.visibility = View.VISIBLE
            previewLineSleep.text = sleepLine
        } else {
            previewLineSleep.visibility = View.GONE
        }

        // 3行目: 体重（欠けているときは行ごと非表示）
        val weightLine = data.formatWeightLine()
        if (weightLine != null) {
            previewLineWeight.visibility = View.VISIBLE
            previewLineWeight.text = weightLine
        } else {
            previewLineWeight.visibility = View.GONE
        }

        // 4行目: 予定 & ゴミ
        previewLineSchedule.text = data.formatScheduleAndGarbageLine()

        // 24時間以上経過時の警告
        if (data.isOlderThan24Hours()) {
            previewOldWarning.visibility = View.VISIBLE
            previewOldWarning.text = getString(
                R.string.old_data_warning,
                data.formatUpdatedAt("M/d HH:mm")
            )
        } else {
            previewOldWarning.visibility = View.GONE
        }

        val lastFetch = repository.getLastSyncTime()
        if (lastFetch > 0L) {
            val sdf = SimpleDateFormat("HH:mm", Locale.JAPAN)
            previewFooterStatus.text = "更新: ${sdf.format(Date(lastFetch))}"
        }
    }

    private fun triggerSync() {
        btnSyncNow.isEnabled = false
        progressSync.visibility = View.VISIBLE
        tvSyncStatus.text = getString(R.string.syncing)

        lifecycleScope.launch {
            val result = DriveSyncManager.sync(this@MainActivity)
            progressSync.visibility = View.GONE
            btnSyncNow.isEnabled = true

            when (result) {
                is DriveSyncManager.SyncResult.Success -> {
                    Toast.makeText(this@MainActivity, R.string.sync_success, Toast.LENGTH_SHORT).show()
                }
                is DriveSyncManager.SyncResult.Error -> {
                    Toast.makeText(this@MainActivity, getString(R.string.sync_failed, result.message), Toast.LENGTH_LONG).show()
                }
            }

            val account = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
            updateUI(account)
        }
    }

    private fun loadInitialSampleData() {
        try {
            val inputStream = assets.open("widget.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()
            reader.close()
            repository.saveSuccess(jsonString)
        } catch (e: Exception) {
            // 無視
        }
    }
}
