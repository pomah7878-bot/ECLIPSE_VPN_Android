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
            .build()
    }

    /** Сбрасывает сохранённую сессию (выход из личного кабинета). */
    fun logout() {
        cookieJar.clear()
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
}
