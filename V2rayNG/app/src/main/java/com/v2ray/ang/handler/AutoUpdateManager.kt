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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.v2ray.ang.R
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // ECLIPSE: ВРЕМЕННО 1 минута для диагностики — вернуть на
    // 24L * 60 * 60 * 1000 после того, как найдём и подтвердим причину.
    private const val CHECK_INTERVAL_MS = 60L * 1000
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
    // ECLIPSE: ВРЕМЕННАЯ диагностика через Toast — убрать после того, как
    // найдём и подтвердим реальную причину, почему автообновление не
    // предлагает установку. LogUtil недоступен для просмотра без ADB,
    // Toast виден прямо на экране без специальных инструментов.
    private suspend fun debugToast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "[AutoUpdate] $message", Toast.LENGTH_LONG).show()
        }
    }

    suspend fun checkAndDownloadIfNeeded(context: Context) {
        debugToast(context, "Проверка запущена")
        val lastCheck = MmkvManager.decodeSettingsLong(PREF_LAST_CHECK_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            debugToast(context, "Пропущено (debounce), осталось ${(CHECK_INTERVAL_MS - (now - lastCheck)) / 1000}с")
            return
        }
        MmkvManager.encodeSettings(PREF_LAST_CHECK_MS, now)
        debugToast(context, "Запрашиваю GitHub...")

        try {
            val result = UpdateCheckerManager.checkForUpdate(includePreRelease = false)
            if (result.hasUpdate && !result.downloadUrl.isNullOrBlank()) {
                debugToast(context, "Найдено: v${result.latestVersion}")
                downloadUpdate(context, result.downloadUrl, result.latestVersion ?: "")
            } else {
                debugToast(context, "Обновлений нет (hasUpdate=${result.hasUpdate})")
            }
        } catch (e: Exception) {
            debugToast(context, "ОШИБКА проверки: ${e.message}")
            LogUtil.e("AutoUpdateManager", "check failed", e)
        }
    }

    private suspend fun downloadUpdate(context: Context, url: String, version: String) {
        val fileName = "ECLIPSE_VPN_update_${version.ifBlank { "latest" }}.apk"
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            debugToast(context, "ОШИБКА: DownloadManager недоступен")
            return
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(context.getString(R.string.app_name))
                .setDescription("Загрузка обновления $version")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION)

            val downloadId = downloadManager.enqueue(request)
            // ECLIPSE: сохраняем персистентно (не только в памяти) — если
            // процесс завершится до реального окончания загрузки, статический
            // DownloadCompleteReceiver в СВЕЖЕМ процессе сможет прочитать эти
            // значения и всё равно корректно обработать завершение.
            MmkvManager.encodeSettings(PREF_PENDING_DOWNLOAD_ID, downloadId)
            MmkvManager.encodeSettings(PREF_PENDING_FILENAME, fileName)
            MmkvManager.encodeSettings(PREF_PENDING_VERSION, version)
            debugToast(context, "Загрузка поставлена в очередь, id=$downloadId")
        } catch (e: Exception) {
            debugToast(context, "ОШИБКА загрузки: ${e.message}")
            LogUtil.e("AutoUpdateManager", "download enqueue failed", e)
        }
    }

    // ECLIPSE: не private — вызывается из DownloadCompleteReceiver
    // (отдельный класс, статически зарегистрированный в манифесте).
    fun showInstallNotification(context: Context, fileName: String, version: String) {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) {
            Toast.makeText(context, "[AutoUpdate] ОШИБКА: файл не найден (${file.path})", Toast.LENGTH_LONG).show()
            LogUtil.e("AutoUpdateManager", "downloaded file not found", Exception(file.path))
            return
        }
        Toast.makeText(context, "[AutoUpdate] Файл скачан, показываю уведомление...", Toast.LENGTH_LONG).show()

        val apkUri = try {
            FileProvider.getUriForFile(
                context,
                context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
                file,
            )
        } catch (e: Exception) {
            Toast.makeText(context, "[AutoUpdate] ОШИБКА FileProvider: ${e.message}", Toast.LENGTH_LONG).show()
            LogUtil.e("AutoUpdateManager", "FileProvider.getUriForFile failed", e)
            return
        }

        // ECLIPSE-фикс: PendingIntent.getActivity() напрямую на установку
        // мог не срабатывать, если процесс приложения уже завершился к
        // моменту нажатия на уведомление. Теперь через getBroadcast() на
        // статически зарегистрированный (в манифесте) UpdateInstallReceiver —
        // система сама поднимет процесс при необходимости для доставки.
        val installBroadcastIntent = Intent(UpdateInstallReceiver.ACTION_INSTALL_UPDATE).apply {
            setPackage(context.packageName)
            putExtra(UpdateInstallReceiver.EXTRA_APK_URI, apkUri.toString())
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            installBroadcastIntent,
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

        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        Toast.makeText(
            context,
            "[AutoUpdate] Уведомления разрешены: $notificationsEnabled. Показываю...",
            Toast.LENGTH_LONG
        ).show()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
