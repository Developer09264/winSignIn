package org.example.winsignin

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * 一个已登录的畅课账号。
 *
 * [deviceId] 必须每个账号各不相同：签到是按设备去重的，两个账号共用一个
 * deviceId 的话，第二个会被判 "device_used" 签不上。
 *
 * [passwordLogin] 为 true 表示是账号密码自动登录（[username]/[password] 有值），
 * 可以用来批量登录；网页登录的账号为 false，没有账密。
 */
@Immutable
data class Account(
    val id: String,
    val cookie: String,
    val deviceId: String,
    val name: String,
    val studentId: String,
    val enabled: Boolean,
    /// CAS 域的 cookie（CASTGC 等），用于会话过期后免密刷新；网页登录的账号为空。
    val casCookie: String = "",
    val username: String = "",
    val password: String = "",
    val passwordLogin: Boolean = false,
) {
    val displayName: String
        get() = when {
            name.isNotBlank() -> name
            studentId.isNotBlank() -> studentId
            username.isNotBlank() -> username
            else -> "账号 ${id.take(4)}"
        }

    val loginMethodLabel: String
        get() = if (passwordLogin) "账号密码登录" else "网页登录"
}

private const val KEY_ACCOUNTS = "accounts"
private const val KEY_LEGACY_COOKIE = "lms_cookie"
private const val KEY_LEGACY_DEVICE = "device_id"

fun loadAccounts(prefs: SharedPreferences): List<Account> {
    val raw = prefs.getString(KEY_ACCOUNTS, null)
    if (raw.isNullOrBlank()) return migrateLegacyAccount(prefs)
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Account(
                id = o.getString("id"),
                cookie = o.getString("cookie"),
                deviceId = o.getString("deviceId"),
                name = o.optString("name"),
                studentId = o.optString("studentId"),
                enabled = o.optBoolean("enabled", true),
                casCookie = o.optString("casCookie"),
                username = o.optString("username"),
                password = o.optString("password"),
                passwordLogin = o.optBoolean("passwordLogin", false),
            )
        }
    }.getOrDefault(emptyList())
}

fun saveAccounts(prefs: SharedPreferences, accounts: List<Account>) {
    val array = JSONArray()
    accounts.forEach { a ->
        array.put(
            JSONObject().apply {
                put("id", a.id)
                put("cookie", a.cookie)
                put("deviceId", a.deviceId)
                put("name", a.name)
                put("studentId", a.studentId)
                put("enabled", a.enabled)
                put("casCookie", a.casCookie)
                put("username", a.username)
                put("password", a.password)
                put("passwordLogin", a.passwordLogin)
            },
        )
    }
    prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
}

fun newAccount(
    cookie: String,
    casCookie: String,
    username: String = "",
    password: String = "",
    passwordLogin: Boolean = false,
): Account = Account(
    id = UUID.randomUUID().toString(),
    cookie = cookie,
    deviceId = UUID.randomUUID().toString(),
    name = "",
    studentId = "",
    enabled = true,
    casCookie = casCookie,
    username = username,
    password = password,
    passwordLogin = passwordLogin,
)

/// 金智的浏览器指纹 bfp：16 字节 hex（大写），按账号持久化，登录链要带。
fun bfpFor(prefs: SharedPreferences, username: String): String {
    val key = "bfp_$username"
    prefs.getString(key, null)?.let { return it }
    val generated = buildString {
        repeat(16) { append("%02X".format(Random.nextInt(256))) }
    }
    prefs.edit().putString(key, generated).apply()
    return generated
}

/// 老的单账号数据一次性搬进来，别让用户白登一次。
private fun migrateLegacyAccount(prefs: SharedPreferences): List<Account> {
    val cookie = prefs.getString(KEY_LEGACY_COOKIE, null) ?: return emptyList()
    val device = prefs.getString(KEY_LEGACY_DEVICE, null) ?: UUID.randomUUID().toString()
    val account = Account(
        id = UUID.randomUUID().toString(),
        cookie = cookie,
        deviceId = device,
        name = "",
        studentId = "",
        enabled = true,
    )
    saveAccounts(prefs, listOf(account))
    prefs.edit().remove(KEY_LEGACY_COOKIE).remove(KEY_LEGACY_DEVICE).apply()
    return listOf(account)
}
