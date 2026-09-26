package org.example.winsignin

import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.winsignin.rust.getAccount
import org.example.winsignin.rust.refreshSession
import org.example.winsignin.rust.signQr
import org.example.winsignin.ui.theme.WinSignInTheme

/**
 * 页面集合。[topLevel] 为 true 的页面显示底部导航栏；其余是全屏详情页。
 */
private enum class Screen(val topLevel: Boolean) {
    Accounts(true),
    Home(true),
    Settings(true),
    Rollcalls(false),
    AddAccount(false),
    WebLogin(false),
    Scanner(false),
}

private data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val NAV_ITEMS = listOf(
    NavItem(
        Screen.Accounts,
        "账号",
        Icons.Outlined.ManageAccounts,
        Icons.Filled.ManageAccounts,
    ),
    NavItem(Screen.Home, "首页", Icons.Outlined.Home, Icons.Filled.Home),
    NavItem(Screen.Settings, "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

private const val PREFS = "winsignin"
private const val KEY_MIN_DELAY = "min_delay_seconds"
private const val KEY_MAX_DELAY = "max_delay_seconds"
private const val KEY_REMEMBER_ME = "remember_me"
private const val DEFAULT_MIN_DELAY = 0
private const val DEFAULT_MAX_DELAY = 1

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 开着它，电脑 Chrome 的 chrome://inspect 就能看到 WebView 里的页面
        WebView.setWebContentsDebuggingEnabled(true)
        setContent {
            WinSignInTheme {
                AppRoot()
            }
        }
    }
}

/**
 * 最外层：底部导航 + 当前页面 + 账号列表。
 *
 * 账号列表就是这个 App 的"登录状态"（多账号后不再有单一的登录态）。
 * 只有 7 个页面，用一个 enum + AnimatedContent 手写导航，不值得上导航库。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(loadAccounts(prefs)) }
    var screen by remember {
        mutableStateOf(if (accounts.isEmpty()) Screen.AddAccount else Screen.Home)
    }
    var minDelay by remember { mutableStateOf(prefs.getInt(KEY_MIN_DELAY, DEFAULT_MIN_DELAY)) }
    var maxDelay by remember { mutableStateOf(prefs.getInt(KEY_MAX_DELAY, DEFAULT_MAX_DELAY)) }
    var rememberMe by remember { mutableStateOf(prefs.getBoolean(KEY_REMEMBER_ME, true)) }
    var autoNumber by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_NUMBER, false)) }
    var radarScheme by remember {
        mutableStateOf(prefs.getString(KEY_RADAR_SCHEME, RADAR_SCHEME_EMPTY) ?: RADAR_SCHEME_EMPTY)
    }
    var probes by remember { mutableStateOf(loadProbes(prefs)) }
    // 演示点名只在 debug 包里生效，release 永远关闭
    var demoMode by remember {
        mutableStateOf(BuildConfig.DEBUG && prefs.getBoolean(KEY_DEMO_ROLLCALLS, false))
    }

    // 首页扫码签到：结果状态放这里，扫码页才能是独立路由。
    var signing by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<Pair<Boolean, String>>>(emptyList()) }

    fun updateAccounts(new: List<Account>) {
        accounts = new
        saveAccounts(prefs, new)
    }

    // 登录成功（自动登录或网页登录）后的统一入口：建账号，再后台补姓名/学号并去重
    fun addAccount(
        cookie: String,
        casCookie: String,
        username: String = "",
        password: String = "",
        passwordLogin: Boolean = false,
    ) {
        val added = newAccount(cookie, casCookie, username, password, passwordLogin)
        updateAccounts(accounts + added)
        screen = Screen.Accounts
        scope.launch {
            val info = withContext(Dispatchers.IO) { getAccount(cookie) }
            if (info.ok) {
                val duplicate = accounts.firstOrNull {
                    it.studentId == info.id && it.id != added.id
                }
                updateAccounts(
                    accounts.mapNotNull { acc ->
                        when {
                            acc.id == added.id ->
                                acc.copy(name = info.name, studentId = info.id)
                            // 同一学号已存在 -> 丢掉旧的，保留这次的新会话
                            duplicate != null && acc.id == duplicate.id -> null
                            else -> acc
                        }
                    },
                )
            }
        }
    }

    // 识别到二维码原文 -> 所有启用账号并行签到（按设置错峰），签完回首页看结果
    fun signAll(raw: String) {
        val enabled = accounts.filter { it.enabled }
        signing = true
        scanResults = emptyList()
        screen = Screen.Home
        scope.launch {
            scanResults = parallelAccounts(enabled, minDelay, maxDelay) { account ->
                val r = signQr(account.cookie, account.deviceId, raw)
                r.success to "${account.displayName}：${r.message}"
            }
            signing = false
        }
    }

    // 启动时逐个账号探活：补名字；会话失效且有 CAS cookie 就免密刷新
    LaunchedEffect(Unit) {
        var updated = accounts
        var changed = false
        for (acc in accounts) {
            if (acc.cookie.isBlank()) continue
            val info = withContext(Dispatchers.IO) { getAccount(acc.cookie) }
            if (info.ok) {
                if (acc.name != info.name || acc.studentId != info.id) {
                    updated = updated.map {
                        if (it.id == acc.id) it.copy(name = info.name, studentId = info.id) else it
                    }
                    changed = true
                }
                continue
            }
            if (acc.casCookie.isNotBlank()) {
                val refreshed = withContext(Dispatchers.IO) { refreshSession(acc.casCookie) }
                if (refreshed.ok) {
                    updated = updated.map {
                        if (it.id == acc.id) {
                            it.copy(cookie = refreshed.cookie, casCookie = refreshed.casCookie)
                        } else {
                            it
                        }
                    }
                    changed = true
                }
            }
        }
        if (changed) updateAccounts(updated)
    }

    // 系统返回：详情页回各自父页，顶层 Tab 回首页；首页则交给系统退出
    BackHandler(enabled = screen != Screen.Home) {
        screen = when (screen) {
            Screen.AddAccount, Screen.WebLogin -> Screen.Accounts
            else -> Screen.Home
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            AnimatedVisibility(
                visible = screen.topLevel,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(500)),
            ) {
                NavigationBar {
                    NAV_ITEMS.forEach { item ->
                        NavigationBarItem(
                            selected = screen == item.screen,
                            onClick = { screen = item.screen },
                            icon = {
                                Icon(
                                    if (screen == item.screen) item.selectedIcon else item.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // M3 fade-through：淡入 + 轻微放大，比整屏横向滑动更不易掉帧。
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.fillMaxSize(),
            // Material "fade through"：旧页先淡出，新页延迟后淡入，错开进行。
            // 比同时 crossfade 更抗打断：快速反悔切回时旧页已基本淡掉，能看到完整的淡入。
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 300, delayMillis = 200)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 200))
            },
            label = "screen",
        ) { target ->
            // 相机预览要全屏出血（自己带透明返回栏）；其余页面躲开底部导航栏。
            val fullBleed = target == Screen.Scanner
            Box(
                modifier = if (fullBleed) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                },
            ) {
                when (target) {
                    Screen.Home -> HomeScreen(
                        accounts = accounts,
                        signing = signing,
                        results = scanResults,
                        onOpenScanner = { screen = Screen.Scanner },
                        onOpenRollcalls = { screen = Screen.Rollcalls },
                    )

                    Screen.Rollcalls -> RollcallsScreen(
                        accounts = accounts,
                        minDelaySeconds = minDelay,
                        maxDelaySeconds = maxDelay,
                        autoNumber = autoNumber,
                        radarScheme = radarScheme,
                        probes = probes,
                        demoMode = demoMode,
                        onBack = { screen = Screen.Home },
                    )

                    Screen.Settings -> SettingsScreen(
                        minSeconds = minDelay,
                        maxSeconds = maxDelay,
                        rememberMe = rememberMe,
                        autoNumber = autoNumber,
                        radarScheme = radarScheme,
                        probes = probes,
                        onChange = { mn, mx ->
                            minDelay = mn
                            maxDelay = mx
                            prefs.edit().putInt(KEY_MIN_DELAY, mn).putInt(KEY_MAX_DELAY, mx).apply()
                        },
                        onRememberMeChange = {
                            rememberMe = it
                            prefs.edit().putBoolean(KEY_REMEMBER_ME, it).apply()
                        },
                        onAutoNumberChange = {
                            autoNumber = it
                            prefs.edit().putBoolean(KEY_AUTO_NUMBER, it).apply()
                        },
                        onRadarSchemeChange = {
                            radarScheme = it
                            prefs.edit().putString(KEY_RADAR_SCHEME, it).apply()
                        },
                        onProbesChange = {
                            probes = it
                            saveProbes(prefs, it)
                        },
                        demoMode = demoMode,
                        onDemoModeChange = {
                            demoMode = it
                            prefs.edit().putBoolean(KEY_DEMO_ROLLCALLS, it).apply()
                        },
                    )

                    Screen.Accounts -> AccountsScreen(
                        accounts = accounts,
                        rememberMe = rememberMe,
                        onToggle = { id, on ->
                            updateAccounts(accounts.map { if (it.id == id) it.copy(enabled = on) else it })
                        },
                        onDelete = { id -> updateAccounts(accounts.filterNot { it.id == id }) },
                        onAdd = { screen = Screen.AddAccount },
                        onUpdateAccounts = { updateAccounts(it) },
                    )

                    Screen.AddAccount -> AddAccountScreen(
                        rememberMe = rememberMe,
                        onBack = { screen = Screen.Accounts },
                        onUseWebLogin = {
                            // 网页登录前清掉 WebView 的 SSO 会话，否则会免密登进旧账号。
                            // 不用回调：removeAllCookies 的回调在部分 ROM 上不触发，会卡着不跳转。
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            screen = Screen.WebLogin
                        },
                        onLoggedIn = { cookie, casCookie, username, password ->
                            addAccount(cookie, casCookie, username, password, passwordLogin = true)
                        },
                    )

                    Screen.WebLogin -> LoginScreen(
                        onBack = { screen = Screen.AddAccount },
                        onLoggedIn = { cookie -> addAccount(cookie, "") },
                    )

                    Screen.Scanner -> QrScannerScreen(
                        onResult = { raw -> signAll(raw) },
                        onCancel = { screen = Screen.Home },
                    )
                }
            }
        }
    }
}
