package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import com.v2ray.ang.handler.MmkvManager

private const val PREF_ONBOARDING_TOOLTIPS_SHOWN = "eclipse_onboarding_tooltips_shown"

/**
 * ECLIPSE: подсказки при самом первом запуске главного экрана — простая,
 * безопасная последовательность карточек поверх затемнённого фона
 * (не точечная подсветка конкретных пикселей экрана — это требует
 * точных координат элементов через onGloballyPositioned и рендеринг
 * "дырки" в оверлее через Canvas/BlendMode, что рискованно писать вслепую
 * без визуальной проверки). Показывается ровно один раз за всю жизнь
 * установки, независимо от онбординга на покупку (это другой, более ранний
 * шаг — редирект на "Мои ключи" при первом запуске приложения).
 */
private data class OnboardingStep(val title: String, val description: String)

private val onboardingSteps = listOf(
    OnboardingStep(
        title = "Список серверов",
        description = "Здесь показаны ваши серверы. Нажмите на карточку, чтобы выбрать — она подсветится золотой рамкой."
    ),
    OnboardingStep(
        title = "Подключение",
        description = "Крупная кнопка внизу справа подключает и отключает VPN. Загорается золотым свечением, пока соединение активно."
    ),
    OnboardingStep(
        title = "Меню и ключи",
        description = "Нажмите ☰ вверху слева (или свайпните экран слева направо) — там «Мои ключи», покупка подписки и настройки."
    ),
)

fun shouldShowOnboardingTooltips(): Boolean {
    return !MmkvManager.decodeSettingsBool(PREF_ONBOARDING_TOOLTIPS_SHOWN, false)
}

fun markOnboardingTooltipsShown() {
    MmkvManager.encodeSettings(PREF_ONBOARDING_TOOLTIPS_SHOWN, true)
}

@Composable
fun OnboardingOverlay(onDismiss: () -> Unit) {
    var stepIndex by remember { mutableIntStateOf(0) }
    val step = onboardingSteps[stepIndex]
    val isLastStep = stepIndex == onboardingSteps.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "${stepIndex + 1}/${onboardingSteps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(step.title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(step.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Пропустить")
                    }
                    Button(onClick = {
                        if (isLastStep) onDismiss() else stepIndex++
                    }) {
                        Text(if (isLastStep) "Понятно" else "Далее")
                    }
                }
            }
        }
    }
}
