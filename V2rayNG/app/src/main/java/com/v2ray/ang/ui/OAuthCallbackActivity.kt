package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ShopApiClient
import kotlinx.coroutines.launch

/**
 * Принимает deep-link eclipsevpn://oauth-callback?code=... после входа
 * через Google/Яндекс/VK в системном браузере, меняет одноразовый код на
 * cookie-сессию и открывает личный кабинет уже авторизованным.
 */
class OAuthCallbackActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.getQueryParameter("code")

        if (code.isNullOrBlank()) {
            LogUtil.e("OAuthCallbackActivity", "нет кода в deep-link", Exception("missing code"))
            openPurchaseScreen()
            return
        }

        lifecycleScope.launch {
            val result = ShopApiClient.oauthExchange(code)
            result.onFailure {
                LogUtil.e("OAuthCallbackActivity", "обмен кода не удался", it)
            }
            openPurchaseScreen()
        }
    }

    private fun openPurchaseScreen() {
        val intent = Intent(this, PurchaseActivity::class.java)
        intent.putExtra(EXTRA_OAUTH_RETURN, true)
        startActivity(intent)
        finish()
    }

    @Composable
    override fun ScreenContent() {
    }

    companion object {
        const val EXTRA_OAUTH_RETURN = "oauth_return"
    }
}
