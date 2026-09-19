package com.mmckb.openwrtstatus.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mmckb.openwrtstatus.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 进程内缓存：避免每次重组/切页都重新解码背景图。 */
private var cachedBitmap: Bitmap? = null
private var cachedStamp: Long = 0

/**
 * 全局背景：未启用自定义背景时就是纯色背景；启用后在内容下方绘制
 * 用户选择的图片（按设置的模糊、缩放与位置渲染）。onResume 时自动重载，
 * 因此在调整页应用后返回即可看到最新效果。
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }

    var bitmap by remember { mutableStateOf(cachedBitmap) }
    var adjust by remember { mutableStateOf(store.loadBackgroundAdjust()) }
    var enabled by remember { mutableStateOf(store.isBackgroundEnabled()) }
    var fileStamp by remember { mutableStateOf(store.backgroundFile().lastModified()) }

    // 从调整页返回（onResume）时重载设置，立即反映最新效果。
    LifecycleResumeEffect(Unit) {
        fileStamp = store.backgroundFile().lastModified()
        adjust = store.loadBackgroundAdjust()
        enabled = store.isBackgroundEnabled()
        onPauseOrDispose { }
    }

    LaunchedEffect(fileStamp) {
        if (fileStamp != cachedStamp || cachedBitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                decodeBackground(store.backgroundFile(), context.resources.displayMetrics)
            }
            if (loaded != null) {
                cachedBitmap = loaded
                cachedStamp = fileStamp
                bitmap = loaded
            }
        }
    }

    Box(modifier.fillMaxSize().background(colors.background)) {
        if (enabled && bitmap != null) {
            val a = adjust
            Image(
                painter = BitmapPainter(bitmap!!.asImageBitmap()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = a.scale
                        scaleY = a.scale
                        translationX = a.offsetX * size.width
                        translationY = a.offsetY * size.height
                    }
                    .blur(a.blurDp.dp, BlurredEdgeTreatment.Unbounded)
            )
        }
        content()
    }
}

/** 解码背景图，降采样到约 1.5 倍屏幕尺寸以内，控制内存占用。 */
private fun decodeBackground(file: java.io.File, metrics: android.util.DisplayMetrics): Bitmap? {
    if (!file.exists()) return null
    val targetW = (metrics.widthPixels * 1.5f).toInt()
    val targetH = (metrics.heightPixels * 1.5f).toInt()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetW || bounds.outHeight / (sample * 2) >= targetH) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}

/** 把 SAF 选中的图片复制进应用私有目录并按屏幕尺寸降采样，返回是否成功。 */
fun copyPickedImageToBackground(context: android.content.Context, uri: android.net.Uri): Boolean =
    runCatching {
        val metrics = context.resources.displayMetrics
        val targetW = (metrics.widthPixels * 1.5f).toInt()
        val targetH = (metrics.heightPixels * 1.5f).toInt()
        // 第一遍只读图片尺寸：inJustDecodeBounds 模式下 decodeStream 固定返回 null，
        // 不能把返回值当作失败依据。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri) ?: return false
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW || bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = context.contentResolver.openInputStream(uri) ?: return false
        val bitmap = decodeStream.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return false
        val store = SettingsStore(context)
        store.backgroundFile().parentFile?.mkdirs()
        store.backgroundFile().outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        cachedStamp = 0 // 强制 AppBackground 下次重载
        true
    }.getOrDefault(false)
