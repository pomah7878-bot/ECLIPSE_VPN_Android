package com.v2ray.ang.ui

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.util.ShopApiClient
import kotlinx.coroutines.launch

/**
 * Экран личного кабинета магазина ECLIPSE Unlimited. Фаза 1: вход по коду
 * доступа. Дальнейшие фазы (тарифы, оплата, автоимпорт) добавляются сюда же.
 */
class PurchaseActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        PurchaseScreen(onBackClick = { finish() })
    }
}

private sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class LoggedIn(val accountType: String?) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

@Composable
fun PurchaseScreen(onBackClick: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<LoginUiState>(LoginUiState.Idle) }
    val scope = rememberCoroutineScope()

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
                is LoginUiState.LoggedIn -> {
                    Text(
                        text = stringResource(R.string.purchase_logged_in),
                        style = MaterialTheme.typography.titleMedium
                    )
                    // Следующая фаза: список тарифов появится здесь.
                }

                else -> {
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
                            enabled = s !is LoginUiState.Loading,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (s is LoginUiState.Error) {
                            Text(
                                text = s.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        Button(
                            enabled = code.isNotBlank() && s !is LoginUiState.Loading,
                            onClick = {
                                state = LoginUiState.Loading
                                scope.launch {
                                    val result = ShopApiClient.sessionLogin(code)
                                    state = result.fold(
                                        onSuccess = { resp ->
                                            if (resp.ok) {
                                                LoginUiState.LoggedIn(resp.accountType)
                                            } else {
                                                LoginUiState.Error(
                                                    resp.message ?: "Не удалось войти"
                                                )
                                            }
                                        },
                                        onFailure = {
                                            LoginUiState.Error("Ошибка сети. Проверьте подключение.")
                                        }
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            if (s is LoginUiState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(stringResource(R.string.purchase_login_button))
                        }
                    }
                }
            }
            NavigationBarsSpacer()
        }
    }
}
