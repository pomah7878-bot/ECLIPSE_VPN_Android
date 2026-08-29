package com.v2ray.ang.handler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.v2ray.ang.util.LogUtil

/**
 * ECLIPSE: статически зарегистрированный (в манифесте) получатель тапа
 * по уведомлению "Обновление готово" — в отличие от динамически
 * зарегистрированного (как для ACTION_DOWNLOAD_COMPLETE), работает даже
 * если процесс приложения уже завершился к моменту нажатия (система
 * сама поднимет процесс для доставки broadcast статическому получателю).
 * Временные диагностические Toast — убрать после подтверждения причины.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Toast.makeText(context, "[AutoUpdate] Нажатие получено, открываю установщик...", Toast.LENGTH_LONG).show()
        val apkUriString = intent.getStringExtra(EXTRA_APK_URI)
        if (apkUriString.isNullOrBlank()) {
            Toast.makeText(context, "[AutoUpdate] ОШИБКА: URI файла отсутствует", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(apkUriString), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "[AutoUpdate] ОШИБКА запуска установщика: ${e.message}", Toast.LENGTH_LONG).show()
            LogUtil.e("UpdateInstallReceiver", "startActivity failed", e)
        }
    }

    companion object {
        const val ACTION_INSTALL_UPDATE = "com.v2ray.ang.ACTION_INSTALL_UPDATE"
        const val EXTRA_APK_URI = "apk_uri"
    }
}
