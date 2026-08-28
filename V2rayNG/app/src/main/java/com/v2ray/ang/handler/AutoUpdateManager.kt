package com.v2ray.ang.handler

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.v2ray.ang.R
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Автообновление приложения: проверка новой версии при запуске (не чаще
 * раза в сутки), фоновая загрузка через системный DownloadManager, и
 * уведомление с одним касанием для завершения установки — единственный
 * шаг, который нельзя убрать технически: Android требует явного согласия
 * пользователя на экране системного установщика для любого приложения
 * вне Google Play, независимо от того, как оно написано.
 */
object AutoUpdateManager {
    // ECLIPSE: ВРЕМЕННО для теста — 1 минута вместо суток. Вернуть на
    // 24L * 60 * 60 * 1000 после подтверждения, что механизм работает.
    private const val CHECK_INTERVAL_MS = 60L * 1000
    private const val PREF_LAST_CHECK_MS = "auto_update_last_check_ms"
    private const val NOTIFICATION_CHANNEL_ID = "eclipse_auto_update"
    private const val NOTIFICATION_ID = 9001
    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".cache"

    /**
     * Проверяет, пора ли делать очередную проверку обновления (не чаще
     * раза в CHECK_INTERVAL_MS), и если да — проверяет и при необходимости
     * запускает фоновую загрузку. Безопасно вызывать при каждом запуске
     * приложения — реальная сетевая проверка произойдёт не чаще раза в сутки.
     */
    suspend fun checkAndDownloadIfNeeded(context: Context) {
        val lastCheck = MmkvManager.decodeSettingsLong(PREF_LAST_CHECK_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            return
        }
        MmkvManager.encodeSettings(PREF_LAST_CHECK_MS, now)

        try {
            val result = UpdateCheckerManager.checkForUpdate(includePreRelease = false)
            if (result.hasUpdate && !result.downloadUrl.isNullOrBlank()) {
                downloadUpdate(context, result.downloadUrl, result.latestVersion ?: "")
            }
        } catch (e: Exception) {
            LogUtil.e("AutoUpdateManager", "check failed", e)
        }
    }

    private fun downloadUpdate(context: Context, url: String, version: String) {
        val fileName = "ECLIPSE_VPN_update_${version.ifBlank { "latest" }}.apk"
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return

        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(context.getString(R.string.app_name))
                .setDescription("Загрузка обновления $version")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)

            val downloadId = downloadManager.enqueue(request)
            registerDownloadCompleteReceiver(context, downloadId, fileName, version)
        } catch (e: Exception) {
            LogUtil.e("AutoUpdateManager", "download enqueue failed", e)
        }
    }

    private fun registerDownloadCompleteReceiver(
        context: Context,
        downloadId: Long,
        fileName: String,
        version: String,
    ) {
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId != downloadId) return
                try {
                    appContext.unregisterReceiver(this)
                } catch (e: Exception) {
                    LogUtil.e("AutoUpdateManager", "unregister receiver failed", e)
                }
                showInstallNotification(appContext, fileName, version)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun showInstallNotification(context: Context, fileName: String, version: String) {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) {
            LogUtil.e("AutoUpdateManager", "downloaded file not found", Exception(file.path))
            return
        }

        val apkUri = try {
            FileProvider.getUriForFile(
                context,
                context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
                file,
            )
        } catch (e: Exception) {
            LogUtil.e("AutoUpdateManager", "FileProvider.getUriForFile failed", e)
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Обновления приложения",
                NotificationManager.IMPORTANCE_HIGH,
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Обновление готово")
            .setContentText("${context.getString(R.string.app_name)} $version — нажмите, чтобы установить")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
