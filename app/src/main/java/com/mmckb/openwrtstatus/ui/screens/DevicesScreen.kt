package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设备管理：每台设备一张独立卡片——左侧路由器图标，名称在上、地址与账号在下，
 * 当前设备右上角带「当前」胶囊并以 1px 蓝色描边区分（其余灰色描边）。
 * 卡片从右往左滑露出黄色「编辑」与红色「删除」。点击卡片切换当前设备。
 * 编辑/添加跳转独立的 DeviceEditActivity，结果经 ActivityResult 回传后落库。
 */
@Composable
fun DevicesScreen(
    viewModel: RouterViewModel,
    onOpenEditor: (RouterConfig, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RouterConfig?>(null) }
    var openSwipe by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun launchEditor(device: RouterConfig, isNew: Boolean) {
        onOpenEditor(device, isNew)
    }

    val colors = LocalAppColors.current

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = rememberTopBarPadding(), bottom = 96.dp)
        ) {
            item {
                Text(
                    "设备（${devices.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }
            if (devices.isEmpty()) {
                item {
                    Text(
                        "还没有设备。点击右下角「添加」，填入路由器地址与账号即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            items(devices, key = { it.id }) { device ->
                DeviceCard(
                    device = device,
                    active = device.id == activeId,
                    openSide = openSwipe?.takeIf { it.first == device.id }?.second,
                    onOpenChange = { side -> openSwipe = side?.let { device.id to it } },
                    onClick = { viewModel.selectDevice(device.id) },
                    onEdit = { launchEditor(device, isNew = false) },
                    onDelete = { pendingDelete = device }
                )
            }
            item {
                Text(
                    "点击设备即可切换当前连接；卡片往左滑删除、往右滑编辑。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        // 添加设备：右下角椭圆悬浮按钮。
        Surface(
            onClick = { launchEditor(RouterConfig(), isNew = true) },
            shape = AppShapes.pill,
            color = colors.primary,
            contentColor = colors.onPrimary,
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 96.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("添加", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    pendingDelete?.let { target ->
        AppDialog(
            title = "删除设备",
            message = "确定要删除「${target.displayName}」吗？删除后需要重新添加才能连接该路由器。",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = {
                viewModel.deleteDevice(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun DeviceCard(
    device: RouterConfig,
    active: Boolean,
    openSide: String?,
    onOpenChange: (String?) -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val actionPx = with(LocalDensity.current) { SwipeActionWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    val snapSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)

    LaunchedEffect(openSide) {
        if (openSide == null && offsetX.value != 0f) offsetX.animateTo(0f, snapSpec)
    }

    fun close() {
        scope.launch { offsetX.animateTo(0f, snapSpec) }
        onOpenChange(null)
    }

    val revealProgress = (kotlin.math.abs(offsetX.value) / actionPx).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
    ) {
        // 右滑露出左侧的编辑、左滑露出右侧的删除：色块比露出的部分更长，
        // 多出的部分一直延伸到卡片下面（被卡片盖住不可见），滑动过程中
        // 颜色始终从卡片下面连续露出，与卡片无缝衔接。
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SwipeAction(
                label = "编辑",
                container = SwipeEditColor,
                iconTint = SwipeEditIconColor,
                icon = Icons.Filled.Edit,
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                iconAtEnd = false,
                progress = revealProgress,
                modifier = Modifier.width(SwipeActionWidth + SwipeUnderCard)
            ) {
                close()
                onEdit()
            }
        }
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SwipeAction(
                label = "删除",
                container = SwipeDeleteColor,
                iconTint = Color.White,
                icon = Icons.Filled.Delete,
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                iconAtEnd = true,
                progress = revealProgress,
                modifier = Modifier.width(SwipeActionWidth + SwipeUnderCard)
            ) {
                close()
                onDelete()
            }
        }
        Surface(
            shape = AppShapes.card,
            color = colors.surface,
            border = BorderStroke(1.dp, if (active) colors.primary else colors.outline),
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-actionPx, actionPx))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val side = when {
                                    offsetX.value <= -actionPx / 2 -> "delete"
                                    offsetX.value >= actionPx / 2 -> "edit"
                                    else -> null
                                }
                                offsetX.animateTo(
                                    when (side) {
                                        "delete" -> -actionPx
                                        "edit" -> actionPx
                                        else -> 0f
                                    },
                                    snapSpec
                                )
                                onOpenChange(side)
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                val side = when {
                                    offsetX.value <= -actionPx / 2 -> "delete"
                                    offsetX.value >= actionPx / 2 -> "edit"
                                    else -> null
                                }
                                offsetX.animateTo(
                                    when (side) {
                                        "delete" -> -actionPx
                                        "edit" -> actionPx
                                        else -> 0f
                                    },
                                    snapSpec
                                )
                                onOpenChange(side)
                            }
                        }
                    )
                }
                .clickable {
                    if (offsetX.value != 0f) close() else onClick()
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Router,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${device.ip}:${device.port} · ${device.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (active) {
                    Surface(shape = AppShapes.pill, color = colors.primary) {
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeAction(
    label: String,
    container: Color,
    iconTint: Color,
    icon: ImageVector,
    shape: Shape,
    iconAtEnd: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(container)
            .clickable(onClick = onClick)
    ) {
        // 图标固定在露出的 72dp 区域中心，塞进卡片下面的部分保持纯色。
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(SwipeActionWidth)
                .align(if (iconAtEnd) Alignment.CenterEnd else Alignment.CenterStart),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        alpha = 0.4f + 0.6f * progress
                        scaleX = 0.6f + 0.4f * progress
                        scaleY = 0.6f + 0.4f * progress
                    }
            )
        }
    }
}

private val SwipeActionWidth = 72.dp
private val SwipeUnderCard = 48.dp

/** 纯红（删除）与柔和黄（编辑）的滑动操作底色，图标颜色随底色定。 */
private val SwipeDeleteColor = Color(0xFFE53935)
private val SwipeEditColor = Color(0xFFE1C16E)
private val SwipeEditIconColor = Color(0xFF1A1C1E)
