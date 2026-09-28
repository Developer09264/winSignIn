package org.example.winsignin

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.example.winsignin.rust.login
import kotlin.random.Random

/**
 * 并行跑一批账号：每个账号先随机等 [minSeconds, maxSeconds] 秒（相对这批开始的时刻错峰，
 * min==max 就是固定延迟），再执行 [block]。返回结果与 [accounts] 顺序一致。
 */
suspend fun <T> parallelAccounts(
    accounts: List<Account>,
    minSeconds: Int,
    maxSeconds: Int,
    block: suspend (Account) -> T,
): List<T> = coroutineScope {
    val lo = minSeconds.coerceAtLeast(0) * 1000L
    val hi = maxSeconds.coerceAtLeast(0) * 1000L
    accounts.map { account ->
        async(Dispatchers.IO) {
            val wait = if (hi > lo) Random.nextLong(lo, hi + 1) else lo
            if (wait > 0) delay(wait)
            block(account)
        }
    }.awaitAll()
}

/** 批量登录结果：更新后的账号列表、成功数、失败明细。 */
data class LoginBatch(
    val accounts: List<Account>,
    val okCount: Int,
    val errors: List<String>,
)

/**
 * 并行重登一批账号：每个账号用存下来的账密走一遍 CAS 自动登录，成功后换上新 cookie。
 * [targets] 一般已过滤为「有账密」的账号。
 */
suspend fun reloginAccounts(
    prefs: SharedPreferences,
    accounts: List<Account>,
    targets: List<Account>,
    rememberMe: Boolean,
): LoginBatch {
    val results = parallelAccounts(targets, 0, 0) { acc ->
        acc to login(acc.username, acc.password, bfpFor(prefs, acc.username), rememberMe, acc.casCookie)
    }
    var working = accounts
    var okCount = 0
    val errors = mutableListOf<String>()
    for ((acc, r) in results) {
        if (r.ok) {
            okCount++
            working = working.map {
                if (it.id == acc.id) it.copy(cookie = r.cookie, casCookie = r.casCookie) else it
            }
        } else {
            errors += "${acc.displayName}：${r.message}"
        }
    }
    return LoginBatch(working, okCount, errors)
}
