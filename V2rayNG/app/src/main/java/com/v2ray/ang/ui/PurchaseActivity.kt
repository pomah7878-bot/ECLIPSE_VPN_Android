package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.AccountKeyDto
import com.v2ray.ang.util.PayCheckResponse
import com.v2ray.ang.util.ShopApiClient
import com.v2ray.ang.util.TariffDto
import com.v2ray.ang.util.TrialCreateResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран личного кабинета магазина ECLIPSE Unlimited: вход по коду доступа,
 * выбор тарифа, оплата через ЮKassa, автоматическая установка полученного
 * ключа в приложение сразу после оплаты — без выхода из приложения.
 */
class PurchaseActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        val cameFromOAuth = intent?.getBooleanExtra(OAuthCallbackActivity.EXTRA_OAUTH_RETURN, false) == true
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_KEYS
        PurchaseScreen(onBackClick = { finish() }, startLoggedIn = cameFromOAuth, mode = mode)
    }

    companion object {
        /** Ключ Intent-экстра: с каким намерением открыт экран — "купить"
         * или "посмотреть свои ключи". Определяет, куда вести пользователя
         * сразу после успешного входа. */
        const val EXTRA_MODE = "mode"
        const val MODE_BUY = "buy"
        const val MODE_KEYS = "keys"
    }
}

private const val POLL_INTERVAL_MS = 3000L

private sealed class PurchaseUiState {
    object LoginIdle : PurchaseUiState()
    object LoginLoading : PurchaseUiState()
    data class LoginError(val message: String) : PurchaseUiState()

    object LoadingAccount : PurchaseUiState()
    data class AccountOverview(val keys: List<AccountKeyDto>, val canLinkOauth: Boolean) : PurchaseUiState()

    object LoadingTariffs : PurchaseUiState()
    data class TariffsError(val message: String) : PurchaseUiState()
    data class TariffsList(val tariffs: List<TariffDto>, val trialAvailable: Boolean) : PurchaseUiState()

    object CreatingPayment : PurchaseUiState()
    data class WaitingPayment(val orderId: String, val qrUrl: String?, val amountRub: Double?) : PurchaseUiState()
    data class PaymentFailed(val message: String) : PurchaseUiState()

    object Importing : PurchaseUiState()
    data class ImportSuccess(val claimCode: String?) : PurchaseUiState()
    data class ImportFailed(val message: String) : PurchaseUiState()
}

@Composable
fun PurchaseScreen(onBackClick: () -> Unit, startLoggedIn: Boolean = false, mode: String = PurchaseActivity.MODE_KEYS) {
    var code by remember { mutableStateOf("") }
    // ECLIPSE-фикс: раньше при обычном открытии экрана (не возврат из OAuth)
    // сразу показывалась форма входа, даже если валидная сессия уже
    // сохранена — сохранение cookie (1.10.0) работало, но экран никогда
    // не пытался ей воспользоваться при обычном запуске. Теперь всегда
    // начинаем с тихой проверки сессии в фоне.
    var state by remember { mutableStateOf<PurchaseUiState>(PurchaseUiState.LoadingAccount) }
    var oauthProviders by remember { mutableStateOf<List<String>>(emptyList()) }
    var importingKeyId by remember { mutableStateOf<Int?>(null) }
    var importedKeyIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var linkCode by remember { mutableStateOf("") }
    var linkCodeLoading by remember { mutableStateOf(false) }
    var linkCodeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun openOAuthTab(provider: String) {
        try {
            val url = ShopApiClient.oauthStartUrl(provider)
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            LogUtil.e("PurchaseActivity", "open oauth tab failed", e)
        }
    }

    // ECLIPSE-фикс: перенесена выше loadAccountOverview() — локальные функции
    // в Kotlin подчиняются порядку объявления (в отличие от методов класса),
    // а loadAccountOverview() теперь вызывает loadTariffs() при mode=buy.
    fun loadTariffs() {
        state = PurchaseUiState.LoadingTariffs
        scope.launch {
            val result = ShopApiClient.getTariffs()
            state = result.fold(
                onSuccess = { resp -> PurchaseUiState.TariffsList(resp.tariffs, resp.trialAvailable) },
                onFailure = { PurchaseUiState.TariffsError("Не удалось загрузить тарифы") }
            )
        }
    }

    fun loadAccountOverview(silentFallback: Boolean = false) {
        state = PurchaseUiState.LoadingAccount
        scope.launch {
            val result = ShopApiClient.getAccountSession()
            if (result.isSuccess) {
                val resp = result.getOrThrow()
                if (resp.loggedIn) {
                    // Реально проверяем, какие ключи уже добавлены в приложение —
                    // не полагаемся только на память текущего сеанса просмотра
                    // экрана (importedKeyIds пустеет при каждом новом открытии).
                    importedKeyIds = withContext(Dispatchers.IO) {
                        val existingUrls = MmkvManager.decodeSubscriptions()
                            .map { it.subscription.url }
                            .toSet()
                        resp.keys
                            .filter { key -> !key.subUrl.isNullOrBlank() && existingUrls.contains(key.subUrl) }
                            .map { it.keyId }
                            .toSet()
                    }
                    if (mode == PurchaseActivity.MODE_BUY) {
                        loadTariffs()
                    } else {
                        state = PurchaseUiState.AccountOverview(resp.keys, resp.canLinkOauth)
                    }
                } else if (silentFallback) {
                    // ECLIPSE: тихая фоновая проверка при обычном открытии экрана
                    // (не явное нажатие "Войти") — нет валидной сессии, просто
                    // показываем форму входа, без пугающего "сессия истекла".
                    state = PurchaseUiState.LoginIdle
                } else {
                    state = PurchaseUiState.LoginError("Сессия истекла, попробуйте войти снова")
                }
            } else if (silentFallback) {
                state = PurchaseUiState.LoginIdle
            } else {
                state = PurchaseUiState.LoginError("Не удалось загрузить личный кабинет")
            }
        }
    }

    fun importExistingKey(key: AccountKeyDto) {
        val subUrl = key.subUrl
        if (subUrl.isNullOrBlank()) return
        importingKeyId = key.keyId
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val (count, countSub) = AngConfigManager.importBatchConfig(subUrl, "", false)
                    withContext(Dispatchers.Main) {
                        importingKeyId = null
                        if (count + countSub > 0) {
                            importedKeyIds = importedKeyIds + key.keyId
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.e("PurchaseActivity", "import existing key failed", e)
                    withContext(Dispatchers.Main) { importingKeyId = null }
                }
            }
        }
    }

    fun submitLinkCode() {
        linkCodeLoading = true
        linkCodeError = null
        scope.launch {
            val result = ShopApiClient.linkBotCode(linkCode)
            linkCodeLoading = false
            result.fold(
                onSuccess = { resp ->
                    if (resp.ok) {
                        linkCode = ""
                        loadAccountOverview()
                    } else {
                        linkCodeError = resp.message ?: "Не удалось привязать аккаунт"
                    }
                },
                onFailure = { linkCodeError = "Ошибка сети. Проверьте подключение." }
            )
        }
    }

    // Останавливаем фоновый поллинг платежа, если пользователь покидает экран,
    // чтобы не тратить сеть и не оставлять "висящую" корутину после ухода.
    var pollJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose { pollJob?.cancel() }
    }

    fun startPolling(orderId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val result = ShopApiClient.checkPayment(orderId)
                result.onSuccess { resp: PayCheckResponse ->
                    when (resp.status) {
                        "paid" -> {
                            val subUrl = resp.subUrl
                            if (subUrl.isNullOrBlank()) {
                                state = PurchaseUiState.PaymentFailed(
                                    "Оплата прошла, но ссылка на ключ не получена. Обратитесь в поддержку."
                                )
                            } else {
                                state = PurchaseUiState.Importing
                                withContext(Dispatchers.IO) {
                                    try {
                                        val (count, countSub) = AngConfigManager.importBatchConfig(subUrl, "", false)
                                        withContext(Dispatchers.Main) {
                                            state = if (count + countSub > 0) {
                                                PurchaseUiState.ImportSuccess(resp.claimCode)
                                            } else {
                                                PurchaseUiState.ImportFailed("Не удалось добавить ключ автоматически")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        LogUtil.e("PurchaseActivity", "import failed", e)
                                        withContext(Dispatchers.Main) {
                                            state = PurchaseUiState.ImportFailed("Ошибка при добавлении ключа")
                                        }
                                    }
                                }
                            }
                            return@launch
                        }

                        "failed" -> {
                            state = PurchaseUiState.PaymentFailed("Платёж не прошёл. Попробуйте ещё раз.")
                            return@launch
                        }

                        else -> {
                            // pending — продолжаем опрос
                        }
                    }
                }
            }
        }
    }

    fun startTrial() {
        state = PurchaseUiState.CreatingPayment
        scope.launch {
            val result = ShopApiClient.createTrial()
            result.fold(
                onSuccess = { resp: TrialCreateResponse ->
                    val subUrl = resp.subUrl
                    if (subUrl.isNullOrBlank()) {
                        state = PurchaseUiState.PaymentFailed("Не удалось активировать пробный период")
                    } else {
                        state = PurchaseUiState.Importing
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    val (count, countSub) = AngConfigManager.importBatchConfig(subUrl, "", false)
                                    withContext(Dispatchers.Main) {
                                        state = if (count + countSub > 0) {
                                            PurchaseUiState.ImportSuccess(resp.claimCode)
                                        } else {
                                            PurchaseUiState.ImportFailed("Не удалось добавить ключ автоматически")
                                        }
                                    }
                                } catch (e: Exception) {
                                    LogUtil.e("PurchaseActivity", "trial import failed", e)
                                    withContext(Dispatchers.Main) {
                                        state = PurchaseUiState.ImportFailed("Ошибка при добавлении ключа")
                                    }
                                }
                            }
                        }
                    }
                },
                onFailure = { e ->
                    state = PurchaseUiState.PaymentFailed(e.message ?: "Не удалось активировать пробный период")
                }
            )
        }
    }

    fun createPayment(tariffId: Int) {
        state = PurchaseUiState.CreatingPayment
        scope.launch {
            val result = ShopApiClient.createPayment(tariffId)
            result.fold(
                onSuccess = { resp ->
                    val orderId = resp.orderId
                    if (orderId.isNullOrBlank()) {
                        state = PurchaseUiState.PaymentFailed("Не удалось создать платёж")
                    } else {
                        state = PurchaseUiState.WaitingPayment(orderId, resp.qrUrl, resp.amountRub)
                        startPolling(orderId)
                    }
                },
                onFailure = { e ->
                    state = PurchaseUiState.PaymentFailed(e.message ?: "Не удалось создать платёж")
                }
            )
        }
    }

    // Список настроенных OAuth-провайдеров — загружаем один раз при
    // открытии экрана, независимо от того, как на него попали.
    LaunchedEffect(Unit) {
        val result = ShopApiClient.getOAuthProviders()
        result.onSuccess { resp -> oauthProviders = resp.providers }
    }

    // Возврат из OAuthCallbackActivity после успешного входа через
    // системный браузер — сразу переходим к тарифам, минуя экран кода.
    // При обычном открытии (не через OAuth) тоже пробуем тихо использовать
    // уже сохранённую сессию (silentFallback=true — без пугающих ошибок,
    // если сессии нет, просто показываем форму входа).
    LaunchedEffect(Unit) {
        loadAccountOverview(silentFallback = !startLoggedIn)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_purchase),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            when (val s = state) {
                is PurchaseUiState.LoginIdle, is PurchaseUiState.LoginLoading, is PurchaseUiState.LoginError -> {
                    Text(
                        text = stringResource(R.string.purchase_login_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.trim() },
                            label = { Text(stringResource(R.string.purchase_code_label)) },
                            singleLine = true,
                            enabled = s !is PurchaseUiState.LoginLoading,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (s is PurchaseUiState.LoginError) {
                            Text(
                                text = s.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        Button(
                            enabled = code.isNotBlank() && s !is PurchaseUiState.LoginLoading,
                            onClick = {
                                state = PurchaseUiState.LoginLoading
                                scope.launch {
                                    val result = ShopApiClient.sessionLogin(code)
                                    result.fold(
                                        onSuccess = { resp ->
                                            if (resp.ok) {
                                                if (mode == PurchaseActivity.MODE_BUY) {
                                                    loadTariffs()
                                                } else {
                                                    loadAccountOverview()
                                                }
                                            } else {
                                                state = PurchaseUiState.LoginError(
                                                    resp.message ?: "Не удалось войти"
                                                )
                                            }
                                        },
                                        onFailure = {
                                            state = PurchaseUiState.LoginError("Ошибка сети. Проверьте подключение.")
                                        }
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            if (s is PurchaseUiState.LoginLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(stringResource(R.string.purchase_login_button))
                        }

                        if (oauthProviders.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.purchase_or_divider),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                            oauthProviders.forEach { provider ->
                                val label = when (provider) {
                                    "google" -> stringResource(R.string.purchase_oauth_google)
                                    "yandex" -> stringResource(R.string.purchase_oauth_yandex)
                                    "vk" -> stringResource(R.string.purchase_oauth_vk)
                                    else -> provider
                                }
                                OutlinedButton(
                                    onClick = { openOAuthTab(provider) },
                                    enabled = s !is PurchaseUiState.LoginLoading,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }

                is PurchaseUiState.LoadingAccount -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is PurchaseUiState.AccountOverview -> {
                    if (s.keys.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.purchase_your_keys),
                            style = MaterialTheme.typography.titleMedium
                        )
                        LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                            items(s.keys) { key ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = key.displayName,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        if (key.serverName != null) {
                                            Text(
                                                text = key.serverName,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        val imported = importedKeyIds.contains(key.keyId)
                                        val importing = importingKeyId == key.keyId
                                        Button(
                                            onClick = { importExistingKey(key) },
                                            enabled = !imported && !importing && !key.subUrl.isNullOrBlank(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp)
                                        ) {
                                            if (importing) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.padding(end = 8.dp),
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                            Text(
                                                if (imported) stringResource(R.string.purchase_key_imported)
                                                else stringResource(R.string.purchase_import_key_button)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (s.canLinkOauth) {
                        Text(
                            text = stringResource(R.string.purchase_link_account_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = if (s.keys.isNotEmpty()) 20.dp else 0.dp)
                        )
                        OutlinedTextField(
                            value = linkCode,
                            onValueChange = { linkCode = it.trim() },
                            label = { Text(stringResource(R.string.purchase_code_label)) },
                            singleLine = true,
                            enabled = !linkCodeLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        if (linkCodeError != null) {
                            Text(
                                text = linkCodeError.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = { submitLinkCode() },
                            enabled = linkCode.isNotBlank() && !linkCodeLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            if (linkCodeLoading) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            }
                            Text(stringResource(R.string.purchase_link_account_button))
                        }
                    }

                    if (s.keys.isEmpty()) {
                        Text(
                            text = stringResource(R.string.purchase_no_keys_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = if (s.canLinkOauth) 20.dp else 0.dp)
                        )
                    }
                    Button(
                        onClick = { loadTariffs() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (s.keys.isEmpty()) 12.dp else 20.dp)
                    ) {
                        Text(
                            if (s.keys.isEmpty()) stringResource(R.string.purchase_buy_first_button)
                            else stringResource(R.string.purchase_buy_more_button)
                        )
                    }
                    // ECLIPSE: кнопка выхода — сброс сессии, чтобы можно
                    // было перелогиниться (например, войти под другим
                    // аккаунтом или заново привязать код из бота).
                    TextButton(
                        onClick = {
                            ShopApiClient.logout()
                            code = ""
                            state = PurchaseUiState.LoginIdle
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.purchase_logout_button))
                    }
                }

                is PurchaseUiState.LoadingTariffs -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is PurchaseUiState.TariffsError -> {
                    Text(text = s.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(
                        onClick = { loadTariffs() },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text(stringResource(R.string.purchase_retry_button))
                    }
                }

                is PurchaseUiState.TariffsList -> {
                    Text(
                        text = stringResource(R.string.purchase_select_tariff),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (s.trialAvailable) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.purchase_trial_title),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = stringResource(R.string.purchase_trial_hint),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { startTrial() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    Text(stringResource(R.string.purchase_trial_button))
                                }
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                        items(s.tariffs) { tariff ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = tariff.name,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "${tariff.durationDays} " +
                                            stringResource(R.string.purchase_days_suffix) +
                                            " — ${tariff.priceRub.toInt()} ₽",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Button(
                                        onClick = { createPayment(tariff.id) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                    ) {
                                        Text(stringResource(R.string.purchase_buy_button))
                                    }
                                }
                            }
                        }
                    }
                }

                is PurchaseUiState.CreatingPayment -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is PurchaseUiState.WaitingPayment -> {
                    Text(
                        text = stringResource(R.string.purchase_waiting_payment),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (s.amountRub != null) {
                        Text(
                            text = "${s.amountRub.toInt()} ₽",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (!s.qrUrl.isNullOrBlank()) {
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.qrUrl)))
                                } catch (e: Exception) {
                                    LogUtil.e("PurchaseActivity", "open payment url failed", e)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        ) {
                            Text(stringResource(R.string.purchase_open_payment_button))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(
                            text = stringResource(R.string.purchase_polling_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                is PurchaseUiState.PaymentFailed -> {
                    Text(text = s.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(
                        onClick = { loadTariffs() },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text(stringResource(R.string.purchase_retry_button))
                    }
                }

                is PurchaseUiState.Importing -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.purchase_importing))
                    }
                }

                is PurchaseUiState.ImportSuccess -> {
                    Text(
                        text = stringResource(R.string.purchase_success),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, MainActivity::class.java))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text(stringResource(R.string.purchase_go_to_main))
                    }
                    if (!s.claimCode.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                try {
                                    val url = "https://t.me/vless_keysvpn_bot?start=claim_${s.claimCode}"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: Exception) {
                                    LogUtil.e("PurchaseActivity", "open telegram failed", e)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.purchase_open_telegram_button))
                        }
                        Text(
                            text = stringResource(R.string.purchase_telegram_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                is PurchaseUiState.ImportFailed -> {
                    Text(text = s.message, color = MaterialTheme.colorScheme.error)
                }
            }
            NavigationBarsSpacer()
        }
    }
}
