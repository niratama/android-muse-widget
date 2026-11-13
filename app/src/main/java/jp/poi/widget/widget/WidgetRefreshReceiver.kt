package jp.poi.widget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * ウィジェットのタップ更新を受け取る内部専用レシーバー（exported=false）。
 * 外部アプリからの不正な同期トリガーを防ぐため、GomaAppWidgetProvider から分離している。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            GomaSyncWorker.enqueueImmediateSync(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "jp.poi.widget.ACTION_REFRESH"
    }
}
