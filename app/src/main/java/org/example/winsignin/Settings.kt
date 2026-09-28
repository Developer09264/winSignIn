package org.example.winsignin

import android.content.SharedPreferences

/** 数字签到是否自动取码（关 = 手输）。 */
const val KEY_AUTO_NUMBER = "auto_number_sign"

/** 演示点名（调试用）：列表里注入一条数字、一条位置点名，签到返回本地假结果。 */
const val KEY_DEMO_ROLLCALLS = "demo_rollcalls"

/** 启动时自动检查登录：过期则弹窗提示重新登录。 */
const val KEY_CHECK_LOGIN_ON_START = "check_login_on_start"

/** 位置签到方案。 */
const val KEY_RADAR_SCHEME = "radar_scheme"
const val RADAR_SCHEME_EMPTY = "empty"
const val RADAR_SCHEME_TRILATERATION = "trilateration"

/** 使用自定义设置：关闭时一律用下面这套推荐值。 */
const val KEY_USE_CUSTOM_SETTINGS = "use_custom_settings"

/** 三边定位用的默认探测点（学校周边，可改）。 */
val DEFAULT_PROBES = listOf(
    29.54057 to 106.607061,
    29.521378 to 106.596161,
    29.522124 to 106.617533,
)

/** 推荐设置：「使用自定义设置」关闭时生效，用户不用管其它设置。 */
const val RECOMMENDED_REMEMBER_ME = true
const val RECOMMENDED_CHECK_LOGIN_ON_START = true
const val RECOMMENDED_AUTO_NUMBER = true
const val RECOMMENDED_MIN_DELAY = 0
const val RECOMMENDED_MAX_DELAY = 0
const val RECOMMENDED_RADAR_SCHEME = RADAR_SCHEME_EMPTY
val RECOMMENDED_PROBES = DEFAULT_PROBES

private const val PROBE_PREFIX = "probe"

fun loadProbes(prefs: SharedPreferences): List<Pair<Double, Double>> {
    val saved = DEFAULT_PROBES.indices.map { i ->
        val lat = prefs.getString("${PROBE_PREFIX}${i}_lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("${PROBE_PREFIX}${i}_lon", null)?.toDoubleOrNull()
        if (lat != null && lon != null) lat to lon else null
    }
    return if (saved.all { it != null }) saved.filterNotNull() else DEFAULT_PROBES
}

fun saveProbes(prefs: SharedPreferences, probes: List<Pair<Double, Double>>) {
    val edit = prefs.edit()
    probes.take(DEFAULT_PROBES.size).forEachIndexed { i, (lat, lon) ->
        edit.putString("${PROBE_PREFIX}${i}_lat", lat.toString())
            .putString("${PROBE_PREFIX}${i}_lon", lon.toString())
    }
    edit.apply()
}
