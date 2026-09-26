package org.example.winsignin

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 极简竖向滚动条：读 foundation 的 [androidx.compose.foundation.ScrollIndicatorState]，
 * 直接画在 draw phase（滚动时只重绘、不重组）。内容不超过一屏时自动不显示。
 */
@Composable
fun AppVerticalScrollbar(
    state: ScrollableState?,
    modifier: Modifier = Modifier,
    thickness: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
) {
    val indicator = state?.scrollIndicatorState ?: return
    Box(
        modifier = modifier
            .width(thickness)
            .drawBehind {
                val content = indicator.contentSize
                val viewport = indicator.viewportSize
                if (content <= 0 || viewport <= 0 || content <= viewport) return@drawBehind

                val thumbFraction = (viewport.toFloat() / content).coerceIn(0.08f, 1f)
                val thumbHeight = size.height * thumbFraction
                val maxScroll = (content - viewport).toFloat()
                val start = (indicator.scrollOffset / maxScroll).coerceIn(0f, 1f)

                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, start * (size.height - thumbHeight)),
                    size = Size(size.width, thumbHeight),
                    cornerRadius = CornerRadius(size.width / 2f),
                )
            },
    )
}
