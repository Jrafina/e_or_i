package com.ddl.eori.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自定义矢量图标。
 *
 * 为什么不直接用 material-icons-extended：该库包含 5000+ 图标，
 * 会把 dex 撑到 40MB+、显著拖慢构建与安装。这里只保留用到的 5 个图标，
 * 全部为 24dp 视口的简单路径，体积可忽略。
 */
object AppIcons {

    /** 柱状图（统计 Tab） */
    val BarChart: ImageVector by lazy {
        icon("BarChart") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 10f); horizontalLineTo(8f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(10f, 4f); horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(10f); close()
                moveTo(16f, 13f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(16f); close()
            }
        }
    }

    /** 钱包（账户 Tab / 账单行） */
    val Wallet: ImageVector by lazy {
        icon("Wallet") {
            path(fill = SolidColor(Color.Black)) {
                // 外框
                moveTo(3f, 6f)
                curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f)
                horizontalLineTo(17f)
                verticalLineTo(6f)
                horizontalLineTo(5f)
                verticalLineTo(18f)
                horizontalLineTo(19f)
                verticalLineTo(9f)
                horizontalLineTo(7f)
                verticalLineTo(7f)
                horizontalLineTo(21f)
                verticalLineTo(20f)
                curveTo(21f, 20.55f, 20.55f, 21f, 20f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                close()
                // 卡扣
                moveTo(16f, 12.5f)
                curveTo(16.83f, 12.5f, 17.5f, 13.17f, 17.5f, 14f)
                curveTo(17.5f, 14.83f, 16.83f, 15.5f, 16f, 15.5f)
                curveTo(15.17f, 15.5f, 14.5f, 14.83f, 14.5f, 14f)
                curveTo(14.5f, 13.17f, 15.17f, 12.5f, 16f, 12.5f)
                close()
            }
        }
    }

    /** 星芒（AI 提示） */
    val Sparkle: ImageVector by lazy {
        icon("Sparkle") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(14.1f, 8.4f)
                lineTo(20.5f, 10.5f)
                lineTo(14.1f, 12.6f)
                lineTo(12f, 19f)
                lineTo(9.9f, 12.6f)
                lineTo(3.5f, 10.5f)
                lineTo(9.9f, 8.4f)
                close()
                moveTo(19f, 15f)
                lineTo(20f, 18f)
                lineTo(23f, 19f)
                lineTo(20f, 20f)
                lineTo(19f, 23f)
                lineTo(18f, 20f)
                lineTo(15f, 19f)
                lineTo(18f, 18f)
                close()
            }
        }
    }

    /** 眼睛（显示 API Key） */
    val Visibility: ImageVector by lazy {
        icon("Visibility") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 5f)
                curveTo(6.5f, 5f, 2.2f, 9.1f, 1f, 12f)
                curveTo(2.2f, 14.9f, 6.5f, 19f, 12f, 19f)
                curveTo(17.5f, 19f, 21.8f, 14.9f, 23f, 12f)
                curveTo(21.8f, 9.1f, 17.5f, 5f, 12f, 5f)
                close()
                moveTo(12f, 16.5f)
                curveTo(9.5f, 16.5f, 7.5f, 14.5f, 7.5f, 12f)
                curveTo(7.5f, 9.5f, 9.5f, 7.5f, 12f, 7.5f)
                curveTo(14.5f, 7.5f, 16.5f, 9.5f, 16.5f, 12f)
                curveTo(16.5f, 14.5f, 14.5f, 16.5f, 12f, 16.5f)
                close()
            }
        }
    }

    /** 眼睛带斜线（隐藏 API Key） */
    val VisibilityOff: ImageVector by lazy {
        icon("VisibilityOff") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3.3f, 2f)
                lineTo(2f, 3.3f)
                lineTo(5.2f, 6.5f)
                curveTo(3.2f, 8f, 1.8f, 10.2f, 1f, 12f)
                curveTo(2.2f, 14.9f, 6.5f, 19f, 12f, 19f)
                curveTo(13.7f, 19f, 15.3f, 18.6f, 16.7f, 17.9f)
                lineTo(20.7f, 22f)
                lineTo(22f, 20.7f)
                close()
                moveTo(12f, 16.5f)
                curveTo(9.5f, 16.5f, 7.5f, 14.5f, 7.5f, 12f)
                curveTo(7.5f, 11.2f, 7.7f, 10.5f, 8f, 9.9f)
                lineTo(9.6f, 11.5f)
                curveTo(9.5f, 11.7f, 9.5f, 11.8f, 9.5f, 12f)
                curveTo(9.5f, 13.4f, 10.6f, 14.5f, 12f, 14.5f)
                curveTo(12.2f, 14.5f, 12.3f, 14.5f, 12.5f, 14.4f)
                lineTo(14.1f, 16f)
                curveTo(13.5f, 16.3f, 12.8f, 16.5f, 12f, 16.5f)
                close()
                moveTo(17.9f, 15.1f)
                lineTo(15.6f, 12.8f)
                curveTo(15.6f, 12.5f, 15.7f, 12.3f, 15.7f, 12f)
                curveTo(15.7f, 10.6f, 14.6f, 9.5f, 13.2f, 9.5f)
                curveTo(12.9f, 9.5f, 12.7f, 9.6f, 12.4f, 9.6f)
                lineTo(10.7f, 7.9f)
                curveTo(11.1f, 7.8f, 11.6f, 7.5f, 12f, 7.5f)
                curveTo(14.5f, 7.5f, 16.5f, 9.5f, 16.5f, 12f)
                curveTo(16.5f, 12.5f, 16.4f, 13f, 16.3f, 13.4f)
                close()
                moveTo(12f, 5f)
                curveTo(17.5f, 5f, 21.8f, 9.1f, 23f, 12f)
                curveTo(22.4f, 13.4f, 21.5f, 14.7f, 20.3f, 15.8f)
                lineTo(18.9f, 14.4f)
                curveTo(19.7f, 13.7f, 20.4f, 12.9f, 20.9f, 12f)
                curveTo(19.8f, 9.6f, 16.5f, 7.5f, 12f, 7.5f)
                curveTo(11.6f, 7.5f, 11.1f, 7.5f, 10.7f, 7.6f)
                lineTo(9.2f, 6.1f)
                curveTo(10.1f, 5.4f, 11f, 5f, 12f, 5f)
                close()
            }
        }
    }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()
}
