package jp.poi.widget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import jp.poi.widget.MainActivity
import jp.poi.widget.R
import jp.poi.widget.data.WidgetData
import jp.poi.widget.data.WidgetRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GomaAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // ウィジェットが追加されたら定期同期タスクをスケジュールし、初回取得を実行
        GomaSyncWorker.schedulePeriodicSync(context)
        GomaSyncWorker.enqueueImmediateSync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                // タップ即時更新
                GomaSyncWorker.enqueueImmediateSync(context)
            }
            ACTION_LAUNCH_MUSE -> {
                // Muse アプリの起動
                launchMuseApp(context)
            }
        }
    }

    private fun launchMuseApp(context: Context) {
        val repository = WidgetRepository.getInstance(context)
        val packageName = repository.getMusePackageName()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
        } else {
            Toast.makeText(context, context.getString(R.string.muse_app_not_found), Toast.LENGTH_SHORT).show()
            try {
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
            } catch (e: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "jp.poi.widget.ACTION_REFRESH"
        const val ACTION_LAUNCH_MUSE = "jp.poi.widget.ACTION_LAUNCH_MUSE"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, GomaAppWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val repository = WidgetRepository.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val account = GoogleSignIn.getLastSignedInAccount(context)
            val latestData = repository.getLatestData()
            val isSyncing = repository.isSyncing()

            // 1. タップで即時再取得する PendingIntent
            val refreshIntent = Intent(context, GomaAppWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 2. Muse アプリを起動する PendingIntent
            val museIntent = Intent(context, GomaAppWidgetProvider::class.java).apply {
                action = ACTION_LAUNCH_MUSE
            }
            val musePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 2000,
                museIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 3. アプリ設定起動用 PendingIntent (未ログイン時など)
            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId + 3000,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // --- 背景画像 (goma.png) の設定 ---
            val gomaBitmap = repository.getGomaBitmap()
            if (gomaBitmap != null) {
                views.setViewVisibility(R.id.widget_bg_image, View.VISIBLE)
                views.setViewVisibility(R.id.widget_scrim, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_bg_image, gomaBitmap)
                // イラスト部分のタップで Muse アプリ起動
                views.setViewVisibility(R.id.widget_muse_hotspot, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_muse_hotspot, musePendingIntent)
            } else {
                views.setViewVisibility(R.id.widget_bg_image, View.GONE)
                views.setViewVisibility(R.id.widget_scrim, View.GONE)
                views.setViewVisibility(R.id.widget_muse_hotspot, View.GONE)
            }

            if (latestData == null && account == null) {
                // 未ログインかつデータ無し
                views.setViewVisibility(R.id.widget_content_container, View.GONE)
                views.setViewVisibility(R.id.widget_empty_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_empty_text, context.getString(R.string.widget_please_login))
                views.setOnClickPendingIntent(R.id.widget_root, appPendingIntent)
            } else if (latestData == null) {
                // サインイン済みだがまだデータ未取得
                views.setViewVisibility(R.id.widget_content_container, View.GONE)
                views.setViewVisibility(R.id.widget_empty_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_empty_text, context.getString(R.string.widget_no_data))
                views.setOnClickPendingIntent(R.id.widget_root, refreshPendingIntent)
            } else {
                // データあり（取得失敗時も前回の最新データを維持して表示）
                views.setViewVisibility(R.id.widget_content_container, View.VISIBLE)
                views.setViewVisibility(R.id.widget_empty_text, View.GONE)

                // 1行目: 天気・日付
                val weatherLine = latestData.formatWeatherLine()
                views.setTextViewText(R.id.widget_line_weather, weatherLine)

                // 2行目: 睡眠
                val sleepLine = latestData.formatSleepLine()
                if (sleepLine != null) {
                    views.setViewVisibility(R.id.widget_line_sleep, View.VISIBLE)
                    views.setTextViewText(R.id.widget_line_sleep, sleepLine)
                } else {
                    views.setViewVisibility(R.id.widget_line_sleep, View.GONE)
                }

                // 3行目: 体重（欠けていれば行ごと非表示）
                val weightLine = latestData.formatWeightLine()
                if (weightLine != null) {
                    views.setViewVisibility(R.id.widget_line_weight, View.VISIBLE)
                    views.setTextViewText(R.id.widget_line_weight, weightLine)
                } else {
                    views.setViewVisibility(R.id.widget_line_weight, View.GONE)
                }

                // 4行目: 予定 & ゴミ収集
                val scheduleLine = latestData.formatScheduleAndGarbageLine()
                views.setTextViewText(R.id.widget_line_schedule_garbage, scheduleLine)

                // 24時間以上古い場合の警告
                if (latestData.isOlderThan24Hours()) {
                    views.setViewVisibility(R.id.widget_old_warning, View.VISIBLE)
                    val formatted = latestData.formatUpdatedAt("M/d HH:mm")
                    views.setTextViewText(
                        R.id.widget_old_warning,
                        context.getString(R.string.old_data_warning, formatted)
                    )
                } else {
                    views.setViewVisibility(R.id.widget_old_warning, View.GONE)
                }

                // 左側のデータ部分をタップしたときは即時再取得
                views.setOnClickPendingIntent(R.id.widget_content_container, refreshPendingIntent)
                views.setOnClickPendingIntent(R.id.widget_root, refreshPendingIntent)
            }

            // フッター状態表示（更新中／前回取得時刻）
            if (isSyncing) {
                views.setTextViewText(R.id.widget_footer_status, context.getString(R.string.widget_updating))
            } else {
                val lastFetch = repository.getLastSyncTime()
                val statusText = if (lastFetch > 0L) {
                    val sdf = SimpleDateFormat("HH:mm", Locale.JAPAN)
                    "更新: ${sdf.format(Date(lastFetch))}"
                } else {
                    ""
                }
                views.setTextViewText(R.id.widget_footer_status, statusText)
            }

            // 右下の更新アイコンをタップしたときも即時更新
            views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
