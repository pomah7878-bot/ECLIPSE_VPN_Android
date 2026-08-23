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
}
