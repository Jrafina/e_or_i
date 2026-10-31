package com.ddl.eori.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ddl.eori.data.local.DailySum
import com.ddl.eori.data.local.PeriodTotals
import com.ddl.eori.data.repo.TransactionRepository
import com.ddl.eori.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.TimeZone

/** 统计页单日数据点 */
data class DayPoint(val day: Int, val incomeCents: Long, val expenseCents: Long)

data class StatsUiState(
    val year: Int = 0,
    val month: Int = 0,
    val totals: PeriodTotals = PeriodTotals(),
    val points: List<DayPoint> = emptyList(),
    val elapsedDays: Int = 1,
    val daysInMonth: Int = 30,
    val isCurrentMonth: Boolean = true,
    val loading: Boolean = true,
) {
    /** 月当前日支出 = 本月净支出 ÷ 本月已过天数（至少为 1） */
    val dailyAverageCents: Long
        get() = if (elapsedDays <= 0) 0 else totals.netCents / elapsedDays
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val txRepo: TransactionRepository,
) : ViewModel() {

    private val zone: TimeZone = TimeZone.getDefault()

    /** 当前查看的年月，默认本月 */
    private val selectedMonth = MutableStateFlow(Dates.currentYearMonth(now(), zone))

    val uiState: StateFlow<StatsUiState> = selectedMonth
        .flatMapLatest { (year, month) ->
            val range = Dates.monthRange(year, month, zone)
            val tzOffsetMinutes = (zone.getOffset(System.currentTimeMillis()) / 60000)
            combine(
                txRepo.observeTotals(range.first, range.last + 1),
                txRepo.observeDaily(range.first, range.last + 1, tzOffsetMinutes),
            ) { totals, daily ->
                val totalDays = Dates.daysInMonth(year, month, zone)
                val byDay = daily.associateBy { it.day }
                val points = (1..totalDays).map { d ->
                    val key = "%04d-%02d-%02d".format(year, month, d)
                    val sum = byDay[key]
                    DayPoint(d, sum?.incomeCents ?: 0, sum?.expenseCents ?: 0)
                }
                val (currentYear, currentMonth) = Dates.currentYearMonth(now(), zone)
                StatsUiState(
                    year = year,
                    month = month,
                    totals = totals,
                    points = points,
                    elapsedDays = if (year == currentYear && month == currentMonth) {
                        Dates.elapsedDaysInMonth(now(), zone)
                    } else totalDays,
                    daysInMonth = totalDays,
                    isCurrentMonth = year == currentYear && month == currentMonth,
                    loading = false,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun previousMonth() {
        selectedMonth.value = shift(-1)
    }

    fun nextMonth() {
        // 不允许切到未来月份
        val (currentYear, currentMonth) = Dates.currentYearMonth(now(), zone)
        val (y, m) = selectedMonth.value
        if (y > currentYear || (y == currentYear && m >= currentMonth)) return
        selectedMonth.value = shift(1)
    }

    fun resetToCurrentMonth() {
        selectedMonth.value = Dates.currentYearMonth(now(), zone)
    }

    private fun shift(delta: Int): Pair<Int, Int> {
        val (year, month) = selectedMonth.value
        val cal = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, 1)
            add(Calendar.MONTH, delta)
        }
        return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH) + 1
    }

    private fun now() = System.currentTimeMillis()
}
