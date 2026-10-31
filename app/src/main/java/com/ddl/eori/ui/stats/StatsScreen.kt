package com.ddl.eori.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.ddl.eori.ui.components.MetricCard
import com.ddl.eori.ui.theme.AmountColors
import com.ddl.eori.util.Dates
import com.ddl.eori.util.Money

/**
 * 统计页：四个指标卡片 + 本月按日收支趋势图。
 * 图表为纯 Canvas 绘制（不引入图表库），懒加载：只有进入本页才订阅数据。
 */
@Composable
fun StatsScreen(
    state: StatsUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val incomeColor = if (dark) AmountColors.incomeDark else AmountColors.income
    val expenseColor = if (dark) AmountColors.expenseDark else AmountColors.expense

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 月份切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPreviousMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
            }
            Text(
                text = Dates.formatMonthLabel(state.year, state.month),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onNextMonth, enabled = state.isCurrentMonth.not()) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "下个月",
                    tint = if (state.isCurrentMonth) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // 四个指标
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricCard(
                title = "总支出",
                value = Money.format(state.totals.expenseCents),
                modifier = Modifier.weight(1f),
                valueColor = expenseColor,
            )
            MetricCard(
                title = "总收入",
                value = Money.format(state.totals.incomeCents),
                modifier = Modifier.weight(1f),
                valueColor = incomeColor,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricCard(
                title = "净支出",
                value = Money.format(state.totals.netCents),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "月日均支出",
                value = Money.format(state.dailyAverageCents),
                modifier = Modifier.weight(1f),
                caption = "净支出 ÷ ${state.elapsedDays} 天",
            )
        }

        Spacer(Modifier.height(16.dp))

        // 趋势图
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每日收支趋势", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(12.dp))
                    LegendDot(incomeColor, "收入")
                    Spacer(Modifier.width(8.dp))
                    LegendDot(expenseColor, "支出")
                }
                Spacer(Modifier.height(12.dp))
                DailyTrendChart(
                    points = state.points,
                    incomeColor = incomeColor,
                    expenseColor = expenseColor,
                    gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("1日", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${state.daysInMonth}日",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 按日双柱状图。空数据（全 0）时显示基线，不崩溃。
 */
@Composable
private fun DailyTrendChart(
    points: List<DayPoint>,
    incomeColor: Color,
    expenseColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
) {
    val maxValue = remember(points) {
        points.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) } ?: 0L
    }
    Canvas(modifier = modifier) {
        val chartHeight = size.height
        val chartWidth = size.width
        val baseline = chartHeight

        // 网格线（4 等分）
        for (i in 0..4) {
            val y = chartHeight * i / 4f
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(chartWidth, y),
                strokeWidth = 1f,
            )
        }
        if (points.isEmpty()) return@Canvas

        val slot = chartWidth / points.size
        val barWidth = (slot * 0.32f).coerceAtLeast(1f)
        val safeMax = if (maxValue <= 0) 1f else maxValue.toFloat()

        points.forEachIndexed { index, point ->
            val slotStart = index * slot
            val incomeHeight = (point.incomeCents / safeMax) * (chartHeight - 4f)
            val expenseHeight = (point.expenseCents / safeMax) * (chartHeight - 4f)

            if (incomeHeight > 0f) {
                drawRoundRect(
                    color = incomeColor,
                    topLeft = Offset(slotStart + slot * 0.12f, baseline - incomeHeight),
                    size = Size(barWidth, incomeHeight),
                    cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
                )
            }
            if (expenseHeight > 0f) {
                drawRoundRect(
                    color = expenseColor,
                    topLeft = Offset(slotStart + slot * 0.12f + barWidth, baseline - expenseHeight),
                    size = Size(barWidth, expenseHeight),
                    cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
                )
            }
        }
    }
}
