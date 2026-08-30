package com.v2ray.ang.handler

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000 // раз в сутки
    private const val PREF_LAST_CHECK_MS = "auto_update_last_check_ms"
    private const val NOTIFICATION_CHANNEL_ID = "eclipse_auto_update"
    private const val NOTIFICATION_ID = 9001
    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".cache"

    // ECLIPSE: персистентные (не в памяти) данные ожидаемой загрузки —
    // читаются статическим DownloadCompleteReceiver, который может
    // сработать в СВЕЖЕМ процессе, если старый уже завершился к моменту
    // фактического окончания загрузки. Видимость не private — нужны
    // получателю в отдельном классе.
    const val PREF_PENDING_DOWNLOAD_ID = "auto_update_pending_download_id"
    const val PREF_PENDING_FILENAME = "auto_update_pending_filename"
    const val PREF_PENDING_VERSION = "auto_update_pending_version"

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

    private suspend fun downloadUpdate(context: Context, url: String, version: String) {
        // Без этого разрешения APK скачается, но экран установки будет
        // мгновенно самозакрываться без показа UI — направляем пользователя
        // напрямую в нужный системный экран для нашего приложения.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            try {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
            } catch (e: Exception) {
                LogUtil.e("AutoUpdateManager", "open install permission settings failed", e)
            }
            return
        }

        val fileName = "ECLIPSE_VPN_update_${version.ifBlank { "latest" }}.apk"
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            LogUtil.e("AutoUpdateManager", "DownloadManager unavailable", Exception("DownloadManager is null"))
            return
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(context.getString(R.string.app_name))
                .setDescription("Загрузка обновления $version")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)

            val downloadId = downloadManager.enqueue(request)
            // Сохраняем персистентно (не только в памяти) — если процесс
            // завершится до реального окончания загрузки, статический
            // DownloadCompleteReceiver в свежем процессе сможет прочитать
            // эти значения и всё равно корректно обработать завершение.
            MmkvManager.encodeSettings(PREF_PENDING_DOWNLOAD_ID, downloadId)
            MmkvManager.encodeSettings(PREF_PENDING_FILENAME, fileName)
            MmkvManager.encodeSettings(PREF_PENDING_VERSION, version)
        } catch (e: Exception) {
            LogUtil.e("AutoUpdateManager", "download enqueue failed", e)
        }
    }

    // Не private — вызывается из DownloadCompleteReceiver (отдельный
    // класс, статически зарегистрированный в манифесте).
    fun showInstallNotification(context: Context, fileName: String, version: String) {
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

        // PendingIntent.getActivity() напрямую (не через посредник-receiver)
        // — особое исключение Android "это нажатие на уведомление",
        // обходящее ограничения фонового запуска экранов (особенно жёсткие
        // у Huawei/EMUI), действует только при прямом таргетинге на Activity.
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
