package com.mmckb.openwrtstatus.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.local.SettingsStore
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val MAX_BLUR_DP = 25f

/**
 * 背景调整页（二级页，独立 Activity）：实时预览选定的背景图，
 * 支持模糊、缩放与拖动调整位置，「应用」保存后全局生效。
 */
@Composable
fun BackgroundEditScreen(
    onBack: () -> Unit,
    onApplied: () -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(store.backgroundFile().absolutePath)
        }
        loading = false
    }

    val initial = remember { store.loadBackgroundAdjust() }
    var blurDp by remember { mutableStateOf(initial.blurDp) }
    var scale by remember { mutableStateOf(initial.scale) }
    var offsetX by remember { mutableStateOf(initial.offsetX) }
    var offsetY by remember { mutableStateOf(initial.offsetY) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack = onBack)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    store.clearBackground()
                    onRemoved()
                }) { Text("移除背景", color = colors.error) }
            }
            Text(
                "调整背景",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                "拖动图片调整位置；模糊效果需要 Android 12 及以上。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            // 预览区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceVariant)
                    .onSizeChanged { previewSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (previewSize.width > 0 && previewSize.height > 0) {
                                offsetX = (offsetX + dragAmount.x / previewSize.width)
                                    .coerceIn(-0.5f, 0.5f)
                                offsetY = (offsetY + dragAmount.y / previewSize.height)
                                    .coerceIn(-0.5f, 0.5f)
                            }
                        }
                    }
            ) {
                val bmp = bitmap
                if (bmp == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                        } else {
                            Text(
                                "背景图不存在，请重新选择",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Image(
                        painter = BitmapPainter(bmp.asImageBitmap()),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX * size.width
                                translationY = offsetY * size.height
                            }
                            .blur(blurDp.dp, BlurredEdgeTreatment.Unbounded)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            AppCard {
                Text(
                    "模糊　${blurDp.toInt()} dp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface
                )
                Slider(
                    value = blurDp,
                    onValueChange = { blurDp = it },
                    valueRange = 0f..MAX_BLUR_DP
                )
                Text(
                    "放大　${"%.1f".format(scale)} ×",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface
                )
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    valueRange = 1f..3f
                )
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    store.saveBackgroundAdjust(
                        SettingsStore.BackgroundAdjust(
                            blurDp = blurDp,
                            scale = scale,
                            offsetX = offsetX,
                            offsetY = offsetY
                        )
                    )
                    onApplied()
                },
                enabled = bitmap != null,
                shape = com.mmckb.openwrtstatus.ui.theme.AppShapes.card,
                modifier = Modifier.fillMaxWidth()
            ) { Text("应用") }
        }
    }
}
