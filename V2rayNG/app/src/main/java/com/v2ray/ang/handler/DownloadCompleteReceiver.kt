package com.v2ray.ang.handler

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.util.LogUtil

/**
 * Статически зарегистрированный (в манифесте) получатель
 * ACTION_DOWNLOAD_COMPLETE — работает независимо от того, жив ли процесс
 * приложения на момент завершения загрузки (система сама поднимет его
 * при необходимости). Ожидаемые downloadId/имя файла/версия читаются из
 * персистентного хранилища (MmkvManager), а не из захваченной переменной
 * в памяти — эта информация должна пережить возможную смерть процесса
 * между постановкой в очередь и реальным завершением загрузки.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val expectedId = MmkvManager.decodeSettingsLong(AutoUpdateManager.PREF_PENDING_DOWNLOAD_ID, -1L)
            if (completedId != expectedId) {
                return
            }

            val fileName = MmkvManager.decodeSettingsString(AutoUpdateManager.PREF_PENDING_FILENAME, "").orEmpty()
            val version = MmkvManager.decodeSettingsString(AutoUpdateManager.PREF_PENDING_VERSION, "").orEmpty()

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (downloadManager == null) {
                LogUtil.e("DownloadCompleteReceiver", "DownloadManager unavailable", Exception("DownloadManager is null"))
                return
            }

            val query = DownloadManager.Query().setFilterById(expectedId)
            downloadManager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
                    val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            AutoUpdateManager.showInstallNotification(context, fileName, version)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            LogUtil.e(
                                "DownloadCompleteReceiver",
                                "download failed, reason=$reason",
                                Exception("STATUS_FAILED reason=$reason"),
                            )
                        }
                        else -> {
                            // pending/paused — ничего не делаем, ждём следующего события
                        }
                    }
                }
            }

            MmkvManager.encodeSettings(AutoUpdateManager.PREF_PENDING_DOWNLOAD_ID, -1L)
        } catch (e: Throwable) {
            // Throwable, а не Exception — ловим и Error-подклассы (например,
            // NoClassDefFoundError, если что-то не подтянулось).
            LogUtil.e("DownloadCompleteReceiver", "onReceive failed", e)
        }
    }
}
