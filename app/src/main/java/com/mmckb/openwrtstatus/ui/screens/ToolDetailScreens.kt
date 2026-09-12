package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/** 工具页：占位，功能后续补充。 */
@Composable
fun ToolScreen(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = rememberTopBarPadding(), bottom = 96.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "工具功能开发中",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
    }
}

/** 详情页：占位，功能后续补充。 */
@Composable
fun DetailScreen(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = rememberTopBarPadding(), bottom = 96.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "详情内容开发中",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
    }
}
