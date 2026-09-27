package com.mmckb.openwrtstatus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 提示条类型：温和告知 / 成功 / 警告 / 错误（VibeHub Alert 图鉴同款四变体）。 */
enum class AppAlertType { Info, Success, Warning, Error }

/**
 * 横幅式警告提示（复刻 vibe-hub.org/alert 的 Alert 组件）：
 * 整条横幅，类型决定底色与边框（同色系浅底 + 1px 描边 + 8dp 圆角），
 * 图标区分类型，文案为「标题 + 说明」——重要程度不只靠颜色表达。
 *
 * 配色从 accent 色推导（底 = accent 低透明度、边框 = accent 中透明度），
 * 浅色模式下与图鉴样例（#EDF0FC/#BEDAFF/#3559D8 等）一致，深色模式自动加深。
 */
@Composable
fun AppAlert(
    type: AppAlertType,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val dark = isSystemInDarkTheme()
    val accent = when (type) {
        AppAlertType.Info -> colors.primary
        AppAlertType.Success -> colors.success
        AppAlertType.Warning -> if (dark) Color(0xFFFFB74D) else Color(0xFF925318)
        AppAlertType.Error -> colors.error
    }
    val background = accent.copy(alpha = if (dark) 0.16f else 0.10f)
    val borderColor = accent.copy(alpha = if (dark) 0.45f else 0.35f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = when (type) {
                AppAlertType.Info -> Icons.Filled.Info
                AppAlertType.Success -> Icons.Filled.CheckCircle
                AppAlertType.Warning -> Icons.Filled.Warning
                AppAlertType.Error -> Icons.Filled.ErrorOutline
            },
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent.copy(alpha = 0.85f)
                )
            }
        }
    }
}
