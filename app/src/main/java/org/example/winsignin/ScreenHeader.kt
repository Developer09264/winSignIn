package org.example.winsignin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 统一页面顶栏（Material3 TopAppBar）：标题、可选返回箭头、可选右侧操作。
 * 外层 Scaffold 已处理状态栏内边距，所以清零 windowInsets 避免重复。
 *
 * [transparent] 用于盖在相机预览 / WebView 上的场景，容器透明、内容白色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    transparent: Boolean = false,
    // 全屏出血页（相机预览/WebView）自己处理状态栏内边距，外层不给它 padding
    inset: Boolean = false,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = if (transparent) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        )
    } else {
        TopAppBarDefaults.topAppBarColors()
    }

    TopAppBar(
        modifier = modifier,
        title = {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title)
                if (subtitle != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = { actions?.invoke(this) },
        colors = colors,
        windowInsets = if (inset) TopAppBarDefaults.windowInsets else WindowInsets(0, 0, 0, 0),
    )
}
