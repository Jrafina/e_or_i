package com.ddl.eori.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ddl.eori.data.local.AccountType
import com.ddl.eori.data.local.TransactionEntity
import com.ddl.eori.data.local.TxType
import com.ddl.eori.ui.theme.AmountColors
import com.ddl.eori.util.Dates
import com.ddl.eori.util.Money

/** 账户类型对应的圆形徽标颜色 */
@Composable
fun accountColor(type: AccountType): Color = when (type) {
    AccountType.ALIPAY -> Color(0xFF1677FF)
    AccountType.WECHAT -> Color(0xFF07C160)
    AccountType.BANK -> Color(0xFF8A5CF6)
    AccountType.CASH -> Color(0xFFF59E0B)
    AccountType.OTHER -> MaterialTheme.colorScheme.outline
}

/**
 * 账单行。账户被删除时（accountId 非空但不在账户表中）显示“已删除账户”。
 *
 * @param knownAccountIds 当前仍存在的账户 id 集合，用于判断悬空引用
 */
@Composable
fun TransactionRow(
    tx: TransactionEntity,
    knownAccountIds: Set<Long>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val income = tx.type == TxType.INCOME
    val amountColor = if (income) {
        if (MaterialTheme.colorScheme.background.luminance() > 0.5f) AmountColors.income else AmountColors.incomeDark
    } else {
        if (MaterialTheme.colorScheme.background.luminance() > 0.5f) AmountColors.expense else AmountColors.expenseDark
    }
    val accountText = when {
        tx.accountId == null -> "未指定账户"
        tx.accountId in knownAccountIds -> tx.accountName ?: "账户"
        else -> "已删除账户"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(amountColor.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Wallet,
                contentDescription = null,
                tint = amountColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = tx.note.ifBlank { if (income) "收入" else "支出" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$accountText · ${Dates.formatDayLabel(tx.occurredAt)} ${Dates.formatDateTime(tx.occurredAt).takeLast(5)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Money.formatSigned(tx.amountCents, income),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = amountColor,
            )
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 指标卡片（统计页） */
@Composable
fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    caption: String? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 空状态占位 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 区块标题 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        trailing?.invoke()
    }
}
