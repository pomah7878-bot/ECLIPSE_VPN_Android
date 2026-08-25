package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.extension.toTrafficString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ECLIPSE: раскрывающаяся информационная карточка для текущей выбранной
 * группы серверов — трафик и срок действия из заголовка
 * Subscription-Userinfo (см. AngConfigManager.applySubscriptionUserinfo).
 * Показывается только если у подписки реально есть эти данные — молча
 * ничего не рендерит для "всех серверов" (без guid) или подписок без
 * данных трафика (не все панели их отдают).
 */
@Composable
fun SubscriptionInfoCard(subscriptionItem: SubscriptionItem?) {
    if (subscriptionItem == null) return
    val hasInfo = subscriptionItem.trafficDownload != null || subscriptionItem.expireAt != null
    if (!hasInfo) return

    var expanded by remember { mutableStateOf(false) }
    val rotation = if (expanded) 180f else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = subscriptionItem.remarks,
                    style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation),
                )
            }

            val upload = subscriptionItem.trafficUpload ?: 0L
            val download = subscriptionItem.trafficDownload ?: 0L
            val used = upload + download
            val total = subscriptionItem.trafficTotal ?: 0L
            val trafficText = if (total > 0) {
                "${used.toTrafficString()} / ${total.toTrafficString()}"
            } else {
                "${used.toTrafficString()} / \u221E" // безлимит
            }
            Text(
                text = trafficText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    subscriptionItem.expireAt?.let { expireSeconds ->
                        val dateText = try {
                            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                                .format(Date(expireSeconds * 1000))
                        } catch (e: Exception) {
                            null
                        }
                        if (dateText != null) {
                            Text(
                                text = "Истекает: $dateText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
