package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.local.AppThemeMode
import com.mmckb.openwrtstatus.data.local.ThemePrefs
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppSwitch
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * 主题设置页（二级页）：深浅色模式三选一（跟随系统/浅色/深色）与 AMOLED 纯黑开关。
 * 读写直接走进程级 [ThemePrefs]——修改即时作用于全部 Activity，无需返回/重启。
 * 视觉语言与关于页一致（AppBackButton + AppCard）。
 */
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val prefs by ThemePrefs.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 2.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(onBack = onBack)
        }

        AppCard {
            CardSectionTitle("深浅色模式")
            Spacer(Modifier.height(4.dp))
            Text(
                "选择 App 的明暗外观；跟随系统时随系统日夜模式自动切换",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            SmoothOptionSwitcher(
                options = listOf(
                    AppThemeMode.SYSTEM.storeValue to "跟随系统",
                    AppThemeMode.LIGHT.storeValue to "浅色",
                    AppThemeMode.DARK.storeValue to "深色"
                ),
                selected = prefs.mode.storeValue,
                onSelect = { ThemePrefs.setMode(AppThemeMode.fromStoreValue(it)) }
            )
        }

        // AMOLED 纯黑只在非浅色模式下有意义（浅色下无效果），按需展开。
        AnimatedVisibility(
            visible = prefs.mode != AppThemeMode.LIGHT,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "AMOLED 纯黑",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        Text(
                            "深色模式下把背景与卡片压成纯黑，OLED 屏幕更省电",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    AppSwitch(
                        checked = prefs.amoled,
                        onCheckedChange = { ThemePrefs.setAmoled(it) }
                    )
                }
            }
        }

        // 卡片背景延伸到手势条区域，最后一张卡片垫在 inset 之上
        Spacer(Modifier.navigationBarsPadding().height(6.dp))
    }
}
