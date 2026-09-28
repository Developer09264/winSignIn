package org.example.winsignin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private const val MAX_DELAY_LIMIT = 5

/**
 * 设置。顶部「使用自定义设置」是总开关：关闭时下面全部锁定、一律走推荐值；
 * 打开后才能逐项自定义。探测点只在「计算坐标」方案下可用。
 */
@Composable
fun SettingsScreen(
    minSeconds: Int,
    maxSeconds: Int,
    rememberMe: Boolean,
    checkLoginOnStart: Boolean,
    autoNumber: Boolean,
    radarScheme: String,
    probes: List<Pair<Double, Double>>,
    useCustom: Boolean,
    onChange: (Int, Int) -> Unit,
    onRememberMeChange: (Boolean) -> Unit,
    onCheckLoginOnStartChange: (Boolean) -> Unit,
    onAutoNumberChange: (Boolean) -> Unit,
    onRadarSchemeChange: (String) -> Unit,
    onProbesChange: (List<Pair<Double, Double>>) -> Unit,
    onUseCustomChange: (Boolean) -> Unit,
    demoMode: Boolean,
    onDemoModeChange: (Boolean) -> Unit,
) {
    // 拖动时先改本地，松手才落盘，别每动一下就写；换设置值/切自定义时重新同步
    var range by remember(minSeconds, maxSeconds) {
        mutableStateOf(
            minSeconds.coerceIn(0, MAX_DELAY_LIMIT).toFloat()..
                maxSeconds.coerceIn(0, MAX_DELAY_LIMIT).toFloat(),
        )
    }
    // 探测点编辑：本地文本框，点「保存」才落盘
    var probeText by remember(probes) {
        mutableStateOf(
            probes.take(DEFAULT_PROBES.size).map { it.first.toString() to it.second.toString() },
        )
    }

    val probesEnabled = useCustom && radarScheme == RADAR_SCHEME_TRILATERATION

    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar("设置")

        Box(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("使用自定义设置") },
                    supportingContent = {
                        Text("关闭时全部锁定、使用推荐设置；打开后才能逐项修改")
                    },
                    trailingContent = {
                        Switch(checked = useCustom, onCheckedChange = onUseCustomChange)
                    },
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("使用长效登录") },
                    supportingContent = {
                        Text("开启后向统一认证申请「记住我」，约两周内可免密自动换票据")
                    },
                    trailingContent = {
                        Switch(
                            checked = rememberMe,
                            onCheckedChange = onRememberMeChange,
                            enabled = useCustom,
                        )
                    },
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("启动时自动检查登录") },
                    supportingContent = {
                        Text("打开后，启动时检查所有账号的登录是否过期，过期的会弹窗提示重新登录")
                    },
                    trailingContent = {
                        Switch(
                            checked = checkLoginOnStart,
                            onCheckedChange = onCheckLoginOnStartChange,
                            enabled = useCustom,
                        )
                    },
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("自动数字签到") },
                    supportingContent = {
                        Text("开启后，数字点名弹窗可一键读出签到码签到；关闭则在弹窗里手动输入")
                    },
                    trailingContent = {
                        Switch(
                            checked = autoNumber,
                            onCheckedChange = onAutoNumberChange,
                            enabled = useCustom,
                        )
                    },
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "位置签到方案",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SchemeRow(
                        label = "空请求",
                        desc = "直接发空坐标请求，服务器直接判到场",
                        selected = radarScheme == RADAR_SCHEME_EMPTY,
                        enabled = useCustom,
                        onSelect = { onRadarSchemeChange(RADAR_SCHEME_EMPTY) },
                    )
                    SchemeRow(
                        label = "计算坐标",
                        desc = "先用三个探测点测距，三边定位算出目标坐标再签",
                        selected = radarScheme == RADAR_SCHEME_TRILATERATION,
                        enabled = useCustom,
                        onSelect = { onRadarSchemeChange(RADAR_SCHEME_TRILATERATION) },
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .alpha(if (probesEnabled) 1f else 0.45f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("探测点", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (useCustom) "填学校周边 3 个点的经纬度（GCJ02）"
                        else "需要「使用自定义设置」+「计算坐标」方案才可修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    probeText.forEachIndexed { i, pair ->
                        Text("探测点 ${i + 1}", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = pair.first,
                                onValueChange = {
                                    probeText = probeText.toMutableList()
                                        .also { list -> list[i] = it to list[i].second }
                                },
                                label = { Text("纬度") },
                                singleLine = true,
                                enabled = probesEnabled,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = pair.second,
                                onValueChange = {
                                    probeText = probeText.toMutableList()
                                        .also { list -> list[i] = list[i].first to it }
                                },
                                label = { Text("经度") },
                                singleLine = true,
                                enabled = probesEnabled,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    val parsed = probeText.map { (lat, lon) ->
                        val a = lat.trim().toDoubleOrNull()
                        val b = lon.trim().toDoubleOrNull()
                        if (a != null && b != null) a to b else null
                    }
                    TextButton(
                        enabled = probesEnabled && parsed.all { it != null },
                        onClick = { onProbesChange(parsed.filterNotNull()) },
                    ) { Text("保存探测点") }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("账号错峰间隔", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "最小 ${range.start.toInt()} 秒   最大 ${range.endInclusive.toInt()} 秒",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    RangeSlider(
                        value = range,
                        onValueChange = { range = it },
                        onValueChangeFinished = {
                            onChange(range.start.toInt(), range.endInclusive.toInt())
                        },
                        enabled = useCustom,
                        valueRange = 0f..MAX_DELAY_LIMIT.toFloat(),
                        steps = MAX_DELAY_LIMIT - 1,
                    )
                    Text(
                        "每个账号在 [最小,最大] 内随机延迟后发出；最小 = 最大 就是固定延迟。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (BuildConfig.DEBUG) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("演示点名（调试）") },
                        supportingContent = {
                            Text("签到列表里注入一条数字、一条位置点名，签到走本地假结果，用来预览界面")
                        },
                        trailingContent = {
                            Switch(checked = demoMode, onCheckedChange = onDemoModeChange)
                        },
                    )
                }
            }
            }
            AppVerticalScrollbar(
                state = scrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SchemeRow(
    label: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
