package io.github.androidaql.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.androidaql.engine.AlertEvaluator

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_ALERT_DISMISSED -> {
                val metricName = intent.getStringExtra(EXTRA_METRIC_NAME) ?: return
                val metric = runCatching { AlertEvaluator.MetricType.valueOf(metricName) }.getOrNull() ?: return
                AqlCollectorService.instance?.onUserDismissedAlert(metric)
            }
            ACTION_BOOKMARK_CURRENT_TRIP -> {
                AqlCollectorService.instance?.bookmarkActiveExcursion()
            }
        }
    }

    companion object {
        const val ACTION_ALERT_DISMISSED = "io.github.androidaql.ACTION_ALERT_DISMISSED"
        const val ACTION_BOOKMARK_CURRENT_TRIP = "io.github.androidaql.ACTION_BOOKMARK_CURRENT_TRIP"
        const val EXTRA_METRIC_NAME = "extra_metric_name"
    }
}
