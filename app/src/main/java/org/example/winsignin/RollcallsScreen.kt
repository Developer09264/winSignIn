package org.example.winsignin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.winsignin.rust.GeoPoint
import org.example.winsignin.rust.Rollcall
import org.example.winsignin.rust.SignResult
import org.example.winsignin.rust.getNumberCode
import org.example.winsignin.rust.getRollcalls
import org.example.winsignin.rust.signNumber
import org.example.winsignin.rust.signRadar
import org.example.winsignin.rust.signRadarEmpty
import org.example.winsignin.rust.solveRadarTarget
import org.example.winsignin.rust.verifySigned

/**
 * 签到页：并行拉所有启用账号的点名列表（按 rollcallId 去重）。列表只展示，
 * 点某项才进弹窗：数字点名按设置自动取码或手输；雷达点名按设置里的方案签。
 */
@Composable
fun RollcallsScreen(
    accounts: List<Account>,
    minDelaySeconds: Int,
    maxDelaySeconds: Int,
    autoNumber: Boolean,
    radarScheme: String,
    probes: List<Pair<Double, Double>>,
    demoMode: Boolean,
    onBack: () -> Unit,
) {
    val enabled = accounts.filter { it.enabled }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var rollcalls by remember { mutableStateOf<List<Rollcall>>(emptyList()) }
    var signTarget by remember { mutableStateOf<Rollcall?>(null) }
    var radarTarget by remember { mutableStateOf<Rollcall?>(null) }
    var autoRefresh by remember { mutableStateOf(true) }

    // 进页面先拉一次；开着自动刷新就每 10s 再拉，关掉就停。多账号并行。
    // ponytail: 只有首屏显示整屏转圈，之后静默刷新，避免列表每 10s 闪一下。
    LaunchedEffect(autoRefresh) {
        while (true) {
            if (rollcalls.isEmpty()) loading = true
            val perAccount = parallelAccounts(enabled, 0, 0) { account ->
                account to getRollcalls(account.cookie)
            }
            val merged = LinkedHashMap<String, Rollcall>()
            var firstError: String? = null
            for ((account, r) in perAccount) {
                if (r.ok) {
                    r.rollcalls.forEach { merged.putIfAbsent(it.id, it) }
                } else if (firstError == null) {
                    firstError = "${account.displayName}：${r.message}"
                }
            }
            // 内容没变就不换引用，避免每 10s 全量重组列表
            val fresh = merged.values.toList() + if (demoMode) demoRollcallList() else emptyList()
            if (fresh != rollcalls) rollcalls = fresh
            error = firstError
            loading = false
            if (!autoRefresh) break
            delay(10_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar("签到", onBack) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动刷新", style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = autoRefresh,
                    onCheckedChange = { autoRefresh = it },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        when {
            loading -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            rollcalls.isEmpty() -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        error ?: "当前没有进行中的点名",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rollcalls, key = { it.id }) { rc ->
                        RollcallRow(
                            rollcall = rc,
                            modifier = Modifier.animateItem(),
                            // 数字点名填密码；雷达点名走设置里的方案；二维码这里不做
                            onClick = {
                                when {
                                    rc.isNumber && !rc.isRadar -> signTarget = rc
                                    rc.isRadar && !rc.isNumber -> radarTarget = rc
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    signTarget?.let { rc ->
        NumberSignDialog(
            rollcall = rc,
            accounts = enabled,
            autoNumber = autoNumber,
            minSeconds = minDelaySeconds,
            maxSeconds = maxDelaySeconds,
            onDismiss = { signTarget = null },
        )
    }

    radarTarget?.let { rc ->
        RadarSignDialog(
            rollcall = rc,
            accounts = enabled,
            scheme = radarScheme,
            probes = probes,
            minSeconds = minDelaySeconds,
            maxSeconds = maxDelaySeconds,
            onDismiss = { radarTarget = null },
        )
    }
}

// ponytail: 演示模式，本地假结果只为预览界面；不需要时删掉本块 + 设置里的开关即可。
private const val DEMO_PREFIX = "demo-"

private fun Rollcall.isDemo() = id.startsWith(DEMO_PREFIX)

private fun demoRollcallList(): List<Rollcall> = listOf(
    Rollcall("demo-number", "演示课程（数字签到）", "", true, false, false, "on_call", "刚刚"),
    Rollcall("demo-radar", "演示课程（位置签到）", "", false, true, false, "on_call", "刚刚"),
)

private suspend fun demoNumberResults(accounts: List<Account>, auto: Boolean): List<String> {
    delay(600)
    val head = if (auto) listOf("演示课程（数字签到）：自动签到（码 0001）") else emptyList()
    return head + accounts.map { "${it.displayName}：✅ 签到成功" }
}

private suspend fun demoRadarResults(accounts: List<Account>, scheme: String): List<String> {
    delay(900)
    val head = if (scheme == RADAR_SCHEME_TRILATERATION) {
        listOf(
            "探测距离：854.3 m / 2110.7 m / 1602.1 m",
            "算出目标：29.535538, 106.610997",
        )
    } else {
        emptyList()
    }
    return head + accounts.map { "${it.displayName}：✅ 签到成功" }
}

/** 自动数字签到：码是这场点名统一的，先取一次，再并行签所有账号。 */
private suspend fun autoSignNumber(
    rollcall: Rollcall,
    accounts: List<Account>,
    minSeconds: Int,
    maxSeconds: Int,
): List<String> {
    val title = rollcall.courseTitle.ifBlank { rollcall.title }
    var code = ""
    for (account in accounts) {
        val r = getNumberCode(account.cookie, rollcall.id)
        if (r.ok) {
            code = r.code
            break
        }
    }
    if (code.isBlank()) return listOf("$title：自动取码失败，可在设置里关掉「自动数字签到」后手动输入")
    return listOf("$title：自动签到（码 $code）") +
        signNumberAll(rollcall, accounts, code, minSeconds, maxSeconds)
}

private suspend fun signNumberAll(
    rollcall: Rollcall,
    accounts: List<Account>,
    code: String,
    minSeconds: Int,
    maxSeconds: Int,
): List<String> = parallelAccounts(accounts, minSeconds, maxSeconds) { account ->
    val r = signNumber(account.cookie, account.deviceId, rollcall.id, code)
    "${account.displayName}：${if (r.success) "✅" else "❌"} ${r.message}"
}

/** 雷达签到：按设置的方案分流。 */
private suspend fun runRadar(
    rollcall: Rollcall,
    accounts: List<Account>,
    scheme: String,
    probes: List<Pair<Double, Double>>,
    minSeconds: Int,
    maxSeconds: Int,
): List<String> = when (scheme) {
    RADAR_SCHEME_TRILATERATION ->
        runRadarTrilateration(rollcall, accounts, probes, minSeconds, maxSeconds)

    else -> parallelAccounts(accounts, minSeconds, maxSeconds) { account ->
        radarLine(account, rollcall, signRadarEmpty(account.cookie, rollcall.id))
    }
}

/** 空请求/坐标请求签完都要回查确认。 */
private fun radarLine(account: Account, rollcall: Rollcall, sign: SignResult): String {
    if (!sign.success) return "${account.displayName}：❌ ${sign.message}"
    val v = verifySigned(account.cookie, rollcall.id)
    return if (v.ok) {
        "${account.displayName}：✅ 签到成功"
    } else {
        "${account.displayName}：⚠️ ${v.message}"
    }
}

/**
 * 数学计算方案：用第一个可用账号对 3 个探测点测距 -> 三边定位 -> 所有账号签算出的坐标。
 */
private suspend fun runRadarTrilateration(
    rollcall: Rollcall,
    accounts: List<Account>,
    probes: List<Pair<Double, Double>>,
    minSeconds: Int,
    maxSeconds: Int,
): List<String> {
    if (accounts.isEmpty()) return listOf("没有启用的账号")
    if (probes.size < 3) return listOf("探测点不足 3 个，请去设置里填写")

    val log = mutableListOf<String>()
    var target: Pair<Double, Double>? = null
    for (account in accounts) {
        val points = mutableListOf<Pair<Double, Double>>()
        val distances = mutableListOf<Double>()
        var alreadySigned = false
        var usable = true
        for (p in probes.take(3)) {
            val r = signRadar(account.cookie, account.deviceId, rollcall.id, p.first, p.second)
            if (r.success) {
                alreadySigned = true
                break
            }
            val d = r.distance
            if (d == null) {
                usable = false // 网络/会话问题，换个账号再试
                break
            }
            points += p
            distances += d
        }
        if (alreadySigned) {
            val v = verifySigned(account.cookie, rollcall.id)
            log += "探测点已在范围内，直接签到"
            return log + "${account.displayName}：${if (v.ok) "✅ 签到成功" else "⚠️ ${v.message}"}"
        }
        if (points.size == 3) {
            log += "探测距离：" + distances.joinToString(" / ") { "%.1f m".format(it) }
            val solved = solveRadarTarget(
                points.map { GeoPoint(it.first, it.second) },
                distances,
            )
            if (solved.ok) {
                target = solved.lat to solved.lon
                log += "算出目标：%.6f, %.6f".format(solved.lat, solved.lon)
            } else {
                log += "三边定位失败：${solved.message}"
            }
            break
        }
        if (!usable) log += "${account.displayName} 取不到距离，换下一个账号"
    }

    val (lat, lon) = target ?: return log + "没能算出目标坐标"
    return log + parallelAccounts(accounts, minSeconds, maxSeconds) { account ->
        radarLine(account, rollcall, signRadar(account.cookie, account.deviceId, rollcall.id, lat, lon))
    }
}

@Composable
private fun RollcallRow(rollcall: Rollcall, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (label, container, content) = when {
        rollcall.isNumber && !rollcall.isRadar -> Triple(
            "数字签到",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        rollcall.isRadar && !rollcall.isNumber -> Triple(
            "雷达签到",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        else -> Triple(
            "二维码签到",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                rollcall.courseTitle.ifBlank { rollcall.title },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = container,
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = content,
                    )
                }
                Text(
                    rollcall.rollcallTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 输入 4 位密码 -> 并行签所有启用账号，显示每个账号的结果。 */
@Composable
private fun NumberSignDialog(
    rollcall: Rollcall,
    accounts: List<Account>,
    autoNumber: Boolean,
    minSeconds: Int,
    maxSeconds: Int,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<String>?>(null) }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(rollcall.courseTitle.ifBlank { "数字签到" }) },
        text = {
            when {
                results != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    results!!.forEach { Text(it) }
                }

                autoNumber -> Text("将自动读出签到码并签到所有启用账号。")

                else -> OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.filter { it.isDigit() }.take(4) },
                    label = { Text("签到密码（4 位）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            if (results == null) {
                TextButton(
                    enabled = (autoNumber || code.length == 4) && !running,
                    onClick = {
                        running = true
                        scope.launch {
                            results = when {
                                rollcall.isDemo() -> demoNumberResults(accounts, autoNumber)
                                autoNumber -> autoSignNumber(rollcall, accounts, minSeconds, maxSeconds)
                                else -> signNumberAll(rollcall, accounts, code, minSeconds, maxSeconds)
                            }
                            running = false
                        }
                    },
                ) {
                    Text(
                        when {
                            running -> "签到中…"
                            autoNumber -> "自动签到"
                            else -> "签到"
                        },
                    )
                }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (results == null) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 位置（雷达）签到弹窗：按设置的方案签，显示过程与每个账号结果。 */
@Composable
private fun RadarSignDialog(
    rollcall: Rollcall,
    accounts: List<Account>,
    scheme: String,
    probes: List<Pair<Double, Double>>,
    minSeconds: Int,
    maxSeconds: Int,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<String>?>(null) }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(rollcall.courseTitle.ifBlank { "位置签到" }) },
        text = {
            if (results == null) {
                Text(
                    if (scheme == RADAR_SCHEME_TRILATERATION) {
                        "先用三个探测点测距，三边定位算出目标坐标，再同时签到。"
                    } else {
                        "直接发空坐标请求，发完回查确认。"
                    },
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    results!!.forEach { Text(it) }
                }
            }
        },
        confirmButton = {
            if (results == null) {
                TextButton(
                    enabled = !running,
                    onClick = {
                        running = true
                        scope.launch {
                            results = if (rollcall.isDemo()) {
                                demoRadarResults(accounts, scheme)
                            } else {
                                runRadar(rollcall, accounts, scheme, probes, minSeconds, maxSeconds)
                            }
                            running = false
                        }
                    },
                ) { Text(if (running) "签到中…" else "签到") }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (results == null) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
