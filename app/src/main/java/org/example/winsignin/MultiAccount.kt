package org.example.winsignin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
