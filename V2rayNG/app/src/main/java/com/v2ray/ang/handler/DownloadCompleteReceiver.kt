package com.v2ray.ang.handler

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.v2ray.ang.util.LogUtil

/**
 * ECLIPSE: статически зарегистрированный (в манифесте) получатель
 * ACTION_DOWNLOAD_COMPLETE — раньше был динамическим (регистрировался
 * в памяти при запуске загрузки), из-за чего пропадал вместе с процессом
 * приложения, если тот завершался ДО фактического окончания загрузки
 * (что объясняло непоследовательность: иногда сообщение доходило, иногда
 * нет). Статический получатель работает независимо от того, жив ли
 * процесс — система сама поднимет его при необходимости. Ожидаемые
 * downloadId/имя файла/версия читаются из персистентного хранилища
 * (MmkvManager), а не из захваченной переменной в памяти — эта информация
 * должна пережить возможную смерть процесса между постановкой в очередь
 * и реальным завершением загрузки.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ECLIPSE: всё тело обёрнуто в try-catch верхнего уровня — раньше
        // необъяснимая тишина сразу после "ID СОВПАЛ" могла означать
        // непойманное исключение где-то внутри (MmkvManager, DownloadManager
        // и т.д.), падающее молча в BroadcastReceiver без единого признака.
        try {
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val expectedId = MmkvManager.decodeSettingsLong(AutoUpdateManager.PREF_PENDING_DOWNLOAD_ID, -1L)

            if (completedId != expectedId) {
                Toast.makeText(
                    context,
                    "[AutoUpdate] Чужой id ($completedId != $expectedId), игнорирую",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            Toast.makeText(context, "[AutoUpdate] ID СОВПАЛ, проверяю статус...", Toast.LENGTH_LONG).show()

            val fileName = MmkvManager.decodeSettingsString(AutoUpdateManager.PREF_PENDING_FILENAME, "").orEmpty()
            val version = MmkvManager.decodeSettingsString(AutoUpdateManager.PREF_PENDING_VERSION, "").orEmpty()
            Toast.makeText(context, "[AutoUpdate] fileName=$fileName version=$version", Toast.LENGTH_LONG).show()

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (downloadManager == null) {
                Toast.makeText(context, "[AutoUpdate] ОШИБКА: DownloadManager недоступен", Toast.LENGTH_LONG).show()
                return
            }

            val query = DownloadManager.Query().setFilterById(expectedId)
            val cursor = downloadManager.query(query)
            Toast.makeText(context, "[AutoUpdate] Курсор получен: ${cursor != null}", Toast.LENGTH_LONG).show()
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val reasonIndex = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val status = if (statusIndex >= 0) c.getInt(statusIndex) else -1
                    val reason = if (reasonIndex >= 0) c.getInt(reasonIndex) else -1
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Toast.makeText(context, "[AutoUpdate] Статус: УСПЕШНО", Toast.LENGTH_LONG).show()
                            AutoUpdateManager.showInstallNotification(context, fileName, version)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            Toast.makeText(
                                context,
                                "[AutoUpdate] Статус: ОШИБКА ЗАГРУЗКИ, reason=$reason",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> {
                            Toast.makeText(context, "[AutoUpdate] Статус: $status (неожиданный)", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(context, "[AutoUpdate] Запись о загрузке не найдена", Toast.LENGTH_LONG).show()
                }
            }

            MmkvManager.encodeSettings(AutoUpdateManager.PREF_PENDING_DOWNLOAD_ID, -1L)
        } catch (e: Throwable) {
            // ECLIPSE: Throwable, а не Exception — ловим и Error-подклассы
            // (например, NoClassDefFoundError, если что-то не подтянулось).
            Toast.makeText(
                context,
                "[AutoUpdate] НЕПОЙМАННОЕ ИСКЛЮЧЕНИЕ: ${e.javaClass.simpleName}: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            LogUtil.e("DownloadCompleteReceiver", "onReceive failed", e)
        }
    }
}
