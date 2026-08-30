package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.colorFabInactiveDark
import com.v2ray.ang.ui.compose.colorFabInactiveLight

/** Форматирует прошедшее время как "Ч:ММ:СС" (часы без ведущего нуля,
 * минуты/секунды — с ним) — тот же формат, что и у большинства VPN-клиентов. */
private fun formatElapsed(startMs: Long, nowMs: Long): String {
    val totalSeconds = ((nowMs - startMs) / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    isDarkTheme: Boolean,
    connectionStartTimeMs: Long?,
    onAction: (MainAction) -> Unit
) {
    // ECLIPSE: живой таймер длительности соединения, тикает раз в секунду,
    // пока активно подключение. Останавливается сам при отключении экрана
    // от композиции (LaunchedEffect отменяется автоматически).
    var elapsedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isRunning, connectionStartTimeMs) {
        if (isRunning && connectionStartTimeMs != null) {
            while (isActive) {
                elapsedText = formatElapsed(connectionStartTimeMs, System.currentTimeMillis())
                delay(1000)
            }
        } else {
            elapsedText = null
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // ECLIPSE: фон убран (был MaterialTheme.colorScheme.surface) —
                // теперь просвечивает фирменный градиент из MainScreen
                .clickable(onClick = { onAction(MainAction.TestCurrentServer) })
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            AppDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics {
                            contentDescription = displayText
                        }
                    )
                    if (elapsedText != null) {
                        Text(
                            text = elapsedText!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        // ECLIPSE: кнопка-корона — тёмный диск с золотым кольцом по краю
        // при активном соединении, отсылка к самому логотипу затмения.
        // FloatingActionButton оставлен без изменений в плане клика/ripple/
        // доступности — фон сделан прозрачным, а градиент рисуется как
        // содержимое кнопки поверх.
        val coronaGradient = if (isRunning) {
            Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFF16141A),
                    0.7f to Color(0xFF16141A),
                    1.0f to Color(0xFFD69E4A),
                )
            )
        } else {
            val inactiveColor = if (isDarkTheme) colorFabInactiveDark else colorFabInactiveLight
            Brush.radialGradient(colors = listOf(inactiveColor, inactiveColor))
        }
        // ECLIPSE-фикс: кнопка заметно крупнее (88dp вместо стандартных
        // 56dp), но отступ -40dp был рассчитан под старую маленькую кнопку
        // из оригинального v2rayNG — с увеличенным размером кнопка налезала
        // на строку статуса под ней (подтверждено скрином пользователя).
        // Увеличен до -60dp для чистого зазора. Свечение было плоским
        // полупрозрачным кругом с жёсткой границей — заменено на настоящий
        // радиальный градиент, плавно гаснущий к краю.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp)
                .offset(y = (-60).dp)
                .navigationBarsPadding()
                .size(112.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isRunning) {
                // ECLIPSE: настоящее неоновое свечение через размытие (blur),
                // а не поддельное кольцо-градиент — яркий насыщенный круг,
                // размытие создаёт естественный мягкий "выброс" света наружу,
                // как у настоящей неоновой подсветки, без ощущения ореола
                // с чёткой видимой границей.
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .blur(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFD69E4A).copy(alpha = 0.9f))
                )
            }
            FloatingActionButton(
                onClick = { onAction(MainAction.ToggleService) },
                modifier = Modifier.size(88.dp),
                containerColor = Color.Transparent,
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(coronaGradient)
                        // ECLIPSE: тонкая приглушённая рамка и в отключённом
                        // состоянии — лёгкий намёк на фирменный стиль вместо
                        // голого однотонного круга, но заметно менее яркая,
                        // чем кольцо активного соединения (не спутать статусы).
                        .border(1.dp, Color(0xFFD69E4A).copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = if (isRunning) painterResource(R.drawable.ic_stop_24dp)
                        else painterResource(R.drawable.ic_play_24dp),
                        contentDescription = stringResource(
                            if (isRunning) R.string.acc_stop else R.string.acc_start
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
