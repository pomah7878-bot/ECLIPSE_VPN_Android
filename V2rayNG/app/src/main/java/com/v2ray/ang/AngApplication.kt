package com.v2ray.ang

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.AutoUpdateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        initSentry()

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Initialize theme state from MMKV
        ThemeManager.refresh()

        // ECLIPSE: автообновление — проверка не чаще раза в сутки при
        // каждом запуске приложения, без необходимости заходить в
        // настройки вручную. Реальная сетевая проверка debounce'ится
        // внутри AutoUpdateManager самостоятельно.
        applicationScope.launch {
            AutoUpdateManager.checkAndDownloadIfNeeded(this@AngApplication)
        }
    }

    /** Собственный scope приложения — Application не имеет встроенного
     * CoroutineScope в отличие от ViewModel, живёт весь процесс приложения. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Инициализирует Sentry, если задан DSN (BuildConfig.SENTRY_DSN,
     * подставляется из local.properties — см. app/build.gradle.kts).
     * Без DSN ничего не делает, чтобы сборка без ключа не падала и не
     * пыталась слать данные в никуда.
     */
    private fun initSentry() {
        if (BuildConfig.SENTRY_DSN.isBlank()) return

        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            // Не собирать PII (email/IP пользователя и т.п.) по умолчанию —
            // для VPN-приложения особенно важно не утекать чувствительными
            // данными в стороннюю систему.
            options.isSendDefaultPii = false
            // Трейсинг производительности не нужен для краш-репортинга,
            // и лишняя телеметрия — тоже не то, что хочется от VPN-клиента.
            options.tracesSampleRate = 0.0
        }
    }
}
