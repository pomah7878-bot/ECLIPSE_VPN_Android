package com.v2ray.ang.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Простое in-memory хранилище cookie для сессии магазина. Живёт, пока
 * приложение открыто — при перезапуске нужен повторный вход по коду.
 * Этого достаточно для первой фазы; при желании можно сохранять между
 * запусками через SharedPreferences.
 */
private class InMemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host] ?: emptyList()
    }

    fun clear() {
        store.clear()
    }
}

data class SessionLoginResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("account_type") val accountType: String? = null,
)

data class TariffDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("duration_days") val durationDays: Int = 0,
    @SerializedName("price_rub") val priceRub: Double = 0.0,
    @SerializedName("traffic_limit_gb") val trafficLimitGb: Double? = null,
    @SerializedName("is_current") val isCurrent: Boolean = false,
)

data class TariffsResponse(
    @SerializedName("tariffs") val tariffs: List<TariffDto> = emptyList(),
    @SerializedName("trial_available") val trialAvailable: Boolean = false,
)

data class PayCreateResponse(
    @SerializedName("order_id") val orderId: String? = null,
    @SerializedName("qr_url") val qrUrl: String? = null,
    @SerializedName("amount_rub") val amountRub: Double? = null,
    @SerializedName("error") val error: String? = null,
)

data class PayCheckResponse(
    @SerializedName("status") val status: String = "pending",
    @SerializedName("claim_code") val claimCode: String? = null,
    @SerializedName("sub_url") val subUrl: String? = null,
    @SerializedName("message") val message: String? = null,
)

data class OAuthProvidersResponse(
    @SerializedName("providers") val providers: List<String> = emptyList(),
)

data class OAuthExchangeResponse(
    @SerializedName("ok") val ok: Boolean = false,
)

data class AccountKeyDto(
    @SerializedName("key_id") val keyId: Int = 0,
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("traffic_used") val trafficUsed: Long = 0,
    @SerializedName("traffic_limit") val trafficLimit: Long = 0,
    @SerializedName("is_active") val isActive: Boolean = false,
    @SerializedName("server_name") val serverName: String? = null,
    @SerializedName("sub_url") val subUrl: String? = null,
)

data class AccountSessionResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("logged_in") val loggedIn: Boolean = false,
    @SerializedName("account_type") val accountType: String? = null,
    @SerializedName("can_link_oauth") val canLinkOauth: Boolean = false,
    @SerializedName("keys") val keys: List<AccountKeyDto> = emptyList(),
    @SerializedName("balance_human") val balanceHuman: String? = null,
)

data class LinkCodeResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("message") val message: String? = null,
)

data class TrialCreateResponse(
    @SerializedName("status") val status: String? = null,
    @SerializedName("claim_code") val claimCode: String? = null,
    @SerializedName("sub_url") val subUrl: String? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("message") val message: String? = null,
)

/**
 * Клиент для взаимодействия с публичным API магазина ECLIPSE Unlimited
 * (/api/public/...) — вход по коду доступа, покупка тарифов, оплата.
 *
 * В отличие от HttpUtil (только GET, без сохранения cookie между запросами),
 * этот клиент держит одну cookie-сессию на всё время работы приложения —
 * необходимо для многошагового флоу "вошёл -> смотрю тарифы -> плачу".
 */
object ShopApiClient {

    private const val BASE_URL = "https://eclipse.unlimited.bot.nu"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val cookieJar = InMemoryCookieJar()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("X-Eclipse-App", "1")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /** Сбрасывает сохранённую сессию (выход из личного кабинета). */
    fun logout() {
        cookieJar.clear()
    }

    /** URL для запуска OAuth-входа в Custom Tabs — ?client=app просит
     * сервер в конце вернуть код обмена через deep-link вместо cookie. */
    fun oauthStartUrl(provider: String): String {
        return "$BASE_URL/auth/$provider/start?client=app"
    }

    /**
     * Вход по коду доступа — либо коду из бота, либо коду анонимной покупки.
     * При успехе сервер устанавливает cookie сессии автоматически (через
     * cookieJar), дальнейшие запросы будут авторизованы.
     */
    suspend fun sessionLogin(code: String): Result<SessionLoginResponse> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf("code" to code)).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/public/account/session-login")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                val parsed = gson.fromJson(text, SessionLoginResponse::class.java)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "sessionLogin failed", e)
            Result.failure(e)
        }
    }

    /** Список тарифов — требует активную сессию (вход по коду уже выполнен). */
    suspend fun getTariffs(): Result<TariffsResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/public/tariffs")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401) {
                    return@withContext Result.failure(Exception("Сессия истекла, войдите снова"))
                }
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                Result.success(gson.fromJson(text, TariffsResponse::class.java))
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "getTariffs failed", e)
            Result.failure(e)
        }
    }

    /** Создаёт платёж ЮKassa за выбранный тариф. */
    suspend fun createPayment(tariffId: Int): Result<PayCreateResponse> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf("tariff_id" to tariffId)).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/public/pay/create")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                val parsed = gson.fromJson(text, PayCreateResponse::class.java)
                if (parsed.orderId == null) {
                    return@withContext Result.failure(
                        Exception(parsed.error ?: "Не удалось создать платёж")
                    )
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "createPayment failed", e)
            Result.failure(e)
        }
    }

    /** Проверяет статус платежа; при status="paid" ответ уже содержит sub_url. */
    suspend fun checkPayment(orderId: String): Result<PayCheckResponse> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf("order_id" to orderId)).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/public/pay/check")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                Result.success(gson.fromJson(text, PayCheckResponse::class.java))
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "checkPayment failed", e)
            Result.failure(e)
        }
    }

    /** Список реально настроенных на сервере OAuth-провайдеров ("google", "yandex", "vk"). */
    suspend fun getOAuthProviders(): Result<OAuthProvidersResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/public/oauth/providers")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                Result.success(gson.fromJson(text, OAuthProvidersResponse::class.java))
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "getOAuthProviders failed", e)
            Result.failure(e)
        }
    }

    /**
     * Обменивает одноразовый код (полученный через deep-link после входа
     * через системный браузер) на настоящую cookie-сессию для дальнейших
     * запросов из приложения.
     */
    suspend fun oauthExchange(code: String): Result<OAuthExchangeResponse> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf("code" to code)).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/public/account/oauth-exchange")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                val parsed = gson.fromJson(text, OAuthExchangeResponse::class.java)
                if (!parsed.ok) {
                    return@withContext Result.failure(Exception("Код недействителен или истёк"))
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "oauthExchange failed", e)
            Result.failure(e)
        }
    }

    /** Данные личного кабинета текущей сессии — существующие ключи, если есть. */
    suspend fun getAccountSession(): Result<AccountSessionResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/public/account/session")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                Result.success(gson.fromJson(text, AccountSessionResponse::class.java))
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "getAccountSession failed", e)
            Result.failure(e)
        }
    }

    /**
     * Привязывает текущий (обычно OAuth) аккаунт к существующему клиенту
     * бота по коду из бота — чтобы старый клиент бота, впервые вошедший
     * через Google/Яндекс/VK, увидел свои реальные ключи.
     */
    suspend fun linkBotCode(code: String): Result<LinkCodeResponse> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf("code" to code)).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/public/account/link-code")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                Result.success(gson.fromJson(text, LinkCodeResponse::class.java))
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "linkBotCode failed", e)
            Result.failure(e)
        }
    }

    /**
     * Активирует бесплатный пробный период для текущей сессии. Запрос из
     * приложения помечен заголовком X-Eclipse-App (добавляется интерцептором
     * автоматически) — сервер пропускает проверку Cloudflare Turnstile для
     * этого канала (у неё нет нативного Android-виджета), полагаясь на
     * rate-limit по IP и обязательную настоящую сессию как основную защиту.
     */
    suspend fun createTrial(): Result<TrialCreateResponse> = withContext(Dispatchers.IO) {
        try {
            val body = "{}".toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/public/trial/create")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    return@withContext Result.failure(Exception("Пустой ответ сервера"))
                }
                val parsed = gson.fromJson(text, TrialCreateResponse::class.java)
                if (parsed.error != null) {
                    return@withContext Result.failure(Exception(parsed.message ?: parsed.error))
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            LogUtil.e("ShopApiClient", "createTrial failed", e)
            Result.failure(e)
        }
    }
}
