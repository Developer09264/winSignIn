package org.example.winsignin

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

// 畅课的域名。整条 CAS 链最后会 302 落回这里，落回来 = 用户操作完成。
private const val LMS_HOST = "lms.tc.cqupt.edu.cn"

// 实测可用的 CAS 入口（sumrise.md 7.1）。`service` 是登录成功后要送回的页面。
private const val CAS_ENTRY =
    "http://identity.tc.cqupt.edu.cn/auth/realms/cqupt/protocol/cas/login" +
        "?service=http://lms.tc.cqupt.edu.cn/user/index"

/**
 * 登录页：直接把一个真 WebView 摆到界面上，让用户在网页里自己登录。
 *
 * 我们完全不管验证码、密码加密、30x 跳转——那些都是浏览器的本职工作。
 * 唯一要做的两件事：
 *   1. 监听页面跳转，判断「已经落回 lms 域名」= 登录操作完成；
 *   2. 那一刻用 CookieManager 把会话 cookie 捞出来，回调给外面。
 *
 * @param onLoggedIn 拿到 cookie 时调用一次（只会调一次）。
 * @param onBack 点左上角返回箭头时调用（离开登录页）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onLoggedIn: (String) -> Unit,
    onBack: () -> Unit,
) {
    // 存下 WebView 引用，给返回键用。remember 保证重建时不会被覆盖。
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    // WebViewClient 只在首次组合创建，必须读最新回调，否则登录成功后加的是旧账号列表快照
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)

    // 网页里能返回时，系统返回键应该是「回上一页」而不是退出 App。
    // canGoBack 用 state 存：普通变量读不出变化，返回键的 enabled 就不会更新。
    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    // 不用透明顶栏：CAS 页面是白底，白色图标会看不见
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(title = "网页登录", onBack = onBack)

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            // factory 只跑一次：负责 new 出 WebView 并做初始配置
            factory = { ctx ->
                WebView(ctx).apply {
                    // 金智登录页要靠 JS 跑，DOM storage 不开可能白屏
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 链路是 https(ids) → http(lms)，放开混合内容限制
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    // CAS 要跨 identity.tc / ids.cqupt.edu.cn / lms.tc 多个域，
                    // 第三方 cookie 必须开，否则跳转过程中会话 cookie 会被丢掉。
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            canGoBack = view.canGoBack()

                            // 用 onPageFinished（页面加载完）而不是 onPageStarted：
                            // 后者页面还没拿到 Set-Cookie。
                            if (finished || !isLms(url)) return

                            // 会话凭据全在这一串里：session=...; role_token=...
                            val cookie = CookieManager.getInstance().getCookie(url) ?: return
                            finished = true
                            currentOnLoggedIn(cookie)
                        }
                    }

                    loadUrl(CAS_ENTRY)
                    webView = this
                }
            },
        )
    }
}

private fun isLms(url: String): Boolean =
    Uri.parse(url).host == LMS_HOST
