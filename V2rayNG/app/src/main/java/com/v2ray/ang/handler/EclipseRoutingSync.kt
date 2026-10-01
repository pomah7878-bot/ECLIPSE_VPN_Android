package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ECLIPSE: геофайлы и пресет маршрутизации из репозитория
 * pomah7878-bot/eclipse-routing.
 *
 * Компактные geosite.dat/geoip.dat из репозитория содержат только нужные метки,
 * поэтому пресет и файлы меняются ТОЛЬКО вместе: сначала скачиваются и проверяются
 * оба файла, и лишь после этого подменяются файлы и набор правил. Если пользователь
 * менял правила вручную или выбрал другой источник геофайлов — ничего не трогаем.
 */
object EclipseRoutingSync {
    /** true — приложение работает на файлах и пресете из репозитория ECLIPSE */
    const val PREF_ACTIVE = "eclipse_routing_active"
    private const val PREF_LAST_ATTEMPT_MS = "eclipse_routing_last_attempt_ms"
    private const val PRESET_ASSET = "custom_routing_eclipse"
    private const val ACTIVE_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val RETRY_INTERVAL_MS = 60L * 60 * 1000

    // Правила прежнего пресета по умолчанию — признак того, что пользователь их не менял
    private val OLD_DEFAULT_REMARKS = listOf(
        "Bypass bittorrent", "Block udp443", "Direct LAN IP",
        "Direct LAN domains", "Bypass Russia domains", "Bypass Russia IP"
    )

    suspend fun syncIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        try {
            doSync(context)
        } catch (e: Throwable) {
            LogUtil.e(AppConfig.TAG, "eclipse routing sync failed", e)
        }
    }

    private fun doSync(context: Context) {
        val source = MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_FILES_SOURCES)
        if (!source.isNullOrBlank() && source != AppConfig.ECLIPSE_ROUTING_SOURCE) return

        val active = MmkvManager.decodeSettingsBool(PREF_ACTIVE, false)
        val now = System.currentTimeMillis()
        val last = MmkvManager.decodeSettingsLong(PREF_LAST_ATTEMPT_MS, 0L)
        val interval = if (active) ACTIVE_INTERVAL_MS else RETRY_INTERVAL_MS
        if (now - last < interval) return
        MmkvManager.encodeSettings(PREF_LAST_ATTEMPT_MS, now)

        val presetJson = Utils.readTextFromAssets(context, PRESET_ASSET)
        val newRemarks = JsonUtil.fromJsonSafe(presetJson, Array<RulesetItem>::class.java)
            ?.map { it.remarks.orEmpty() }.orEmpty()
        if (newRemarks.isEmpty()) return

        val currentRemarks = MmkvManager.decodeRoutingRulesets()?.map { it.remarks.orEmpty() }.orEmpty()
        val eligible = currentRemarks.isEmpty() ||
            currentRemarks == OLD_DEFAULT_REMARKS ||
            currentRemarks == newRemarks
        if (!eligible) return

        val dir = File(Utils.userAssetPath(context))
        if (!dir.isDirectory) return

        val downloads = listOf(
            AppConfig.GEOSITE_DAT to listOf("category-ru", "whitelist", "private"),
            AppConfig.GEOIP_DAT to listOf("private", "direct"),
        )
        val ready = mutableListOf<Pair<File, File>>()
        for ((name, tags) in downloads) {
            val tmp = File(dir, name + ".eclipse_tmp")
            if (!download(AppConfig.ECLIPSE_ROUTING_RAW_URL + name, tmp) || !looksValid(tmp, tags)) {
                LogUtil.e(AppConfig.TAG, "eclipse geo file rejected: " + name, Exception("download or validation failed"))
                downloads.forEach { File(dir, it.first + ".eclipse_tmp").delete() }
                return
            }
            ready.add(tmp to File(dir, name))
        }
        for ((tmp, target) in ready) {
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    LogUtil.e(AppConfig.TAG, "eclipse geo rename failed: " + target.name, Exception("rename failed"))
                    return
                }
            }
        }

        if (currentRemarks != newRemarks) {
            if (!SettingsManager.resetRoutingRulesets(presetJson)) return
        }
        MmkvManager.encodeSettings(PREF_ACTIVE, true)
        LogUtil.i(AppConfig.TAG, "eclipse routing synced")
    }

    private fun download(url: String, target: File): Boolean {
        val username = SettingsManager.getSocksUsername()
        val password = SettingsManager.getSocksPassword()
        val ports = listOf(0, SettingsManager.getHttpPort()).distinct()
        for (port in ports) {
            try {
                target.delete()
                val request = UrlContentRequest(
                    url = url,
                    timeout = 15000,
                    httpPort = port,
                    proxyUsername = username,
                    proxyPassword = password,
                )
                if (HttpUtil.downloadToFile(request, target)) return true
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "eclipse download failed: " + url, e)
            }
        }
        return false
    }

    /** Защита от пустого файла, страницы с ошибкой или испорченной ночной синхронизации. */
    private fun looksValid(file: File, tags: List<String>): Boolean {
        if (!file.isFile || file.length() < 10_000L) return false
        val text = String(file.readBytes(), Charsets.ISO_8859_1).lowercase()
        return tags.all { text.contains(it) }
    }
}
