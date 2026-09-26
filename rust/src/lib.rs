//! winSignIn 的 Rust 侧：二维码/数字签到、点名列表、账号信息。
//! 通过 uniffi 暴露给 Kotlin，跨边界只传普通类型。

mod cas;
mod qr;
mod radar;

use std::sync::OnceLock;

uniffi::setup_scaffolding!();

/// 畅课的接口地址（明文 http，见 manifest 的 usesCleartextTraffic）。
pub(crate) const API_BASE: &str = "http://lms.tc.cqupt.edu.cn";

/// 伪装成畅课 App 的 UA（原项目 user_agent.dart 的模板）。
const USER_AGENT: &str = "Mozilla/5.0 (Linux; Android 12; 22021211RC Build/TKQ1.220807.001; wv) \
AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.199 Mobile Safari/537.36 \
TronClass/common";

/// 签到结果。[distance] 只在雷达签到被判"太远"时返回，单位米（服务器算的）。
#[derive(uniffi::Record)]
pub struct SignResult {
    pub success: bool,
    pub message: String,
    pub distance: Option<f64>,
}

/// 从点名里直接读出的数字签到码。
#[derive(uniffi::Record)]
pub struct NumberCodeResult {
    pub ok: bool,
    pub message: String,
    pub code: String,
}

/// 签到后回查的确认结果。[status] 是服务器给的本人状态。
#[derive(uniffi::Record)]
pub struct VerifyResult {
    pub ok: bool,
    pub message: String,
    pub status: String,
}

/// 一个经纬度点（雷达三边定位的探测点）。
#[derive(uniffi::Record)]
pub struct GeoPoint {
    pub lat: f64,
    pub lon: f64,
}

/// 三边定位算出的目标点。
#[derive(uniffi::Record)]
pub struct RadarTarget {
    pub ok: bool,
    pub message: String,
    pub lat: f64,
    pub lon: f64,
}

/// 自动登录结果。成功时 cookie 是 lms 域下的会话串，
/// cas_cookie 是 CAS 域 cookie（CASTGC 等），由调用方按账号持久化。
#[derive(uniffi::Record)]
pub struct LoginResult {
    pub ok: bool,
    pub message: String,
    pub cookie: String,
    pub cas_cookie: String,
}

/// 当前账号信息。拿不到时 ok=false，message 里是原因。
#[derive(uniffi::Record)]
pub struct AccountInfo {
    pub ok: bool,
    pub message: String,
    pub id: String,
    pub name: String,
}

/// 一条点名记录（只取界面要用的字段）。
#[derive(uniffi::Record)]
pub struct Rollcall {
    pub id: String,
    pub course_title: String,
    pub title: String,
    pub is_number: bool,
    pub is_radar: bool,
    pub is_expired: bool,
    pub status: String,
    pub rollcall_time: String,
}

/// 点名列表结果。
#[derive(uniffi::Record)]
pub struct RollcallsResult {
    pub ok: bool,
    pub message: String,
    pub rollcalls: Vec<Rollcall>,
}

fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| tokio::runtime::Runtime::new().expect("创建 tokio runtime 失败"))
}

/// 账号密码自动登录：走完整条金智 CAS 网关链，成功返回 lms 会话 cookie。
/// [remember_me] 勾选"记住我"时向 CAS 申请长效 TGT（14 天免登录）。
/// [cas_cookie] 传上次保存的 CAS cookie， CASTGC 还有效就能免密直接落回 lms。
#[uniffi::export]
pub fn login(
    username: String,
    password: String,
    bfp: String,
    remember_me: bool,
    cas_cookie: String,
) -> LoginResult {
    match runtime().block_on(cas::acquire_cookie(
        &username,
        &password,
        &bfp,
        remember_me,
        &cas_cookie,
    )) {
        Ok((cookie, cas)) => LoginResult {
            ok: true,
            message: String::new(),
            cookie,
            cas_cookie: cas,
        },
        Err(e) => LoginResult {
            ok: false,
            message: e,
            cookie: String::new(),
            cas_cookie: String::new(),
        },
    }
}

/// 用已保存的 CAS cookie 静默刷新会话（免密），CASTGC 过期就失败。
#[uniffi::export]
pub fn refresh_session(cas_cookie: String) -> LoginResult {
    match runtime().block_on(cas::refresh(&cas_cookie)) {
        Ok((cookie, cas)) => LoginResult {
            ok: true,
            message: String::new(),
            cookie,
            cas_cookie: cas,
        },
        Err(e) => LoginResult {
            ok: false,
            message: e,
            cookie: String::new(),
            cas_cookie: String::new(),
        },
    }
}

/// 扫码原文 -> 解析 -> 二维码签到接口 -> 结果。
#[uniffi::export]
pub fn sign_qr(cookie: String, device_id: String, raw_qr: String) -> SignResult {
    let parsed = match qr::extract_sign_data(&raw_qr) {
        Ok(p) => p,
        Err(e) => {
            return SignResult {
                success: false,
                message: e,
                distance: None,
            }
        }
    };

    let url = format!(
        "{API_BASE}/api/rollcall/{}/answer_qr_rollcall",
        parsed.rollcall_id
    );
    let body = serde_json::json!({ "data": parsed.data, "deviceId": device_id });
    let req = authed(reqwest::Client::new().put(&url), &cookie).json(&body);
    interpret(runtime().block_on(send(req)))
}

/// 数字签到：rollcall_id + 4 位密码。
#[uniffi::export]
pub fn sign_number(
    cookie: String,
    device_id: String,
    rollcall_id: String,
    number_code: String,
) -> SignResult {
    let url = format!("{API_BASE}/api/rollcall/{rollcall_id}/answer_number_rollcall");
    let body = serde_json::json!({ "numberCode": number_code, "deviceId": device_id });
    let req = authed(reqwest::Client::new().put(&url), &cookie).json(&body);
    interpret(runtime().block_on(send(req)))
}

/// 位置（雷达）签到：提交经纬度（按 GCJ02），服务器据此算出与点名位置的距离。
// ponytail: speed/altitude/heading 是抓包固定值，服务器只看经纬度算距离。
#[uniffi::export]
pub fn sign_radar(
    cookie: String,
    device_id: String,
    rollcall_id: String,
    latitude: f64,
    longitude: f64,
) -> SignResult {
    let url = format!("{API_BASE}/api/rollcall/{rollcall_id}/answer?api_version=1.1.2");
    let body = serde_json::json!({
        "deviceId": device_id,
        "latitude": latitude,
        "longitude": longitude,
        "speed": null,
        "accuracy": 30.0,
        "altitude": 0.0,
        "altitudeAccuracy": null,
        "heading": null,
    });
    let req = authed(reqwest::Client::new().put(&url), &cookie).json(&body);
    interpret(runtime().block_on(send(req)))
}

/// 雷达签到（空答案方案）：PUT body 为空 `{}`，不带坐标、不带 api_version。
/// 服务器可能直接判到场，调用方需再 verify_signed 确认。
#[uniffi::export]
pub fn sign_radar_empty(cookie: String, rollcall_id: String) -> SignResult {
    let url = format!("{API_BASE}/api/rollcall/{rollcall_id}/answer");
    let req = authed(reqwest::Client::new().put(&url), &cookie).json(&serde_json::json!({}));
    interpret(runtime().block_on(send(req)))
}

/// 直接从点名里读数字签到码：`GET /api/rollcall/{id}/student_rollcalls`。
#[uniffi::export]
pub fn get_number_code(cookie: String, rollcall_id: String) -> NumberCodeResult {
    let url = format!("{API_BASE}/api/rollcall/{rollcall_id}/student_rollcalls");
    let req = authed(reqwest::Client::new().get(&url), &cookie);
    match runtime().block_on(send(req)) {
        Ok((code, text)) => {
            if code == 401 || code == 403 {
                return NumberCodeResult {
                    ok: false,
                    message: "登录已过期，请重新登录".to_string(),
                    code: String::new(),
                };
            }
            match radar::extract_number_code(&parse_json(&text)) {
                Some(found) => NumberCodeResult {
                    ok: true,
                    message: String::new(),
                    code: found,
                },
                None => NumberCodeResult {
                    ok: false,
                    message: "没读到签到码".to_string(),
                    code: String::new(),
                },
            }
        }
        Err(e) => NumberCodeResult {
            ok: false,
            message: format!("网络错误：{e}"),
            code: String::new(),
        },
    }
}

/// 签到后回查：拉点名列表，找这场点名看本人 status 是否 on_call_fine。
#[uniffi::export]
pub fn verify_signed(cookie: String, rollcall_id: String) -> VerifyResult {
    let result = get_rollcalls(cookie);
    if !result.ok {
        return VerifyResult {
            ok: false,
            message: result.message,
            status: String::new(),
        };
    }
    match result.rollcalls.into_iter().find(|r| r.id == rollcall_id) {
        Some(rc) if rc.status == "on_call_fine" => VerifyResult {
            ok: true,
            message: "已确认签到".to_string(),
            status: rc.status,
        },
        Some(rc) => VerifyResult {
            ok: false,
            message: format!("已提交，但状态是「{}」", rc.status),
            status: rc.status,
        },
        None => VerifyResult {
            ok: false,
            message: "已提交，但列表里找不到这场点名".to_string(),
            status: String::new(),
        },
    }
}

/// 三边定位：给 3 个探测点和对应距离，算出目标经纬度。
#[uniffi::export]
pub fn solve_radar_target(points: Vec<GeoPoint>, distances: Vec<f64>) -> RadarTarget {
    let pts: Vec<(f64, f64)> = points.iter().map(|p| (p.lat, p.lon)).collect();
    match radar::trilaterate(&pts, &distances) {
        Some((lat, lon)) => RadarTarget {
            ok: true,
            message: String::new(),
            lat,
            lon,
        },
        None => RadarTarget {
            ok: false,
            message: "三边定位失败（点太少或几何太差）".to_string(),
            lat: 0.0,
            lon: 0.0,
        },
    }
}

/// 取当前账号的点名列表。
#[uniffi::export]
pub fn get_rollcalls(cookie: String) -> RollcallsResult {
    let url = format!("{API_BASE}/api/radar/rollcalls?api_version=1.1.0");
    let req = authed(reqwest::Client::new().get(&url), &cookie);
    match runtime().block_on(send(req)) {
        Ok((code, text)) => parse_rollcalls(code, &text),
        Err(e) => RollcallsResult {
            ok: false,
            message: format!("网络错误：{e}"),
            rollcalls: Vec::new(),
        },
    }
}

/// 取当前账号信息：`GET /api/profile`，顺便验证会话还有效。
#[uniffi::export]
pub fn get_account(cookie: String) -> AccountInfo {
    let url = format!("{API_BASE}/api/profile");
    let req = authed(reqwest::Client::new().get(&url), &cookie);
    match runtime().block_on(send(req)) {
        Ok((code, text)) => parse_account(code, &text),
        Err(e) => AccountInfo {
            ok: false,
            message: format!("网络错误：{e}"),
            id: String::new(),
            name: String::new(),
        },
    }
}

/// 发请求并取回 (状态码, 响应体文本)。
async fn send(req: reqwest::RequestBuilder) -> Result<(u16, String), reqwest::Error> {
    let resp = req.send().await?;
    let code = resp.status().as_u16();
    let text = resp.text().await?;
    Ok((code, text))
}

/// 签到响应统一解读：200 且（status 是 on_call/on_call_fine 或 success=true）算成功。
/// 雷达失败时把服务器算的 distance 带回去（三边定位要用）。
fn interpret(result: Result<(u16, String), reqwest::Error>) -> SignResult {
    match result {
        Ok((code, text)) => {
            let value = parse_json(&text);
            let status = value.get("status").and_then(|v| v.as_str()).unwrap_or("");
            let success_flag = value.get("success").and_then(|v| v.as_bool()).unwrap_or(false);
            let distance = value.get("distance").and_then(|v| v.as_f64());
            let ok = code == 200
                && (matches!(status, "on_call" | "on_call_fine") || success_flag);
            if ok {
                SignResult {
                    success: true,
                    message: "签到成功".to_string(),
                    distance: None,
                }
            } else {
                SignResult {
                    success: false,
                    message: failure_message(code, &value),
                    distance,
                }
            }
        }
        Err(e) => SignResult {
            success: false,
            message: format!("网络错误：{e}"),
            distance: None,
        },
    }
}

fn parse_account(code: u16, text: &str) -> AccountInfo {
    let value = parse_json(text);
    let name = value
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let id = value.get("id").map(json_to_string).unwrap_or_default();

    if code == 401 || code == 403 {
        return AccountInfo {
            ok: false,
            message: "登录已过期，请重新登录".to_string(),
            id,
            name,
        };
    }
    if name.is_empty() && id.is_empty() {
        return AccountInfo {
            ok: false,
            message: format!("获取账号信息失败（HTTP {code}）"),
            id,
            name,
        };
    }
    AccountInfo {
        ok: true,
        message: String::new(),
        id,
        name,
    }
}

fn parse_rollcalls(code: u16, text: &str) -> RollcallsResult {
    if code == 401 || code == 403 {
        return RollcallsResult {
            ok: false,
            message: "登录已过期，请重新登录".to_string(),
            rollcalls: Vec::new(),
        };
    }
    let value = parse_json(text);
    let rollcalls = value
        .get("rollcalls")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().map(parse_rollcall).collect())
        .unwrap_or_default();
    RollcallsResult {
        ok: true,
        message: String::new(),
        rollcalls,
    }
}

fn parse_rollcall(v: &serde_json::Value) -> Rollcall {
    let s = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
    let b = |k: &str| v.get(k).and_then(|x| x.as_bool()).unwrap_or(false);
    Rollcall {
        id: v.get("rollcall_id").map(json_to_string).unwrap_or_default(),
        course_title: s("course_title"),
        title: s("title"),
        is_number: b("is_number"),
        is_radar: b("is_radar"),
        is_expired: b("is_expired"),
        status: s("status"),
        rollcall_time: s("rollcall_time"),
    }
}

fn parse_json(text: &str) -> serde_json::Value {
    serde_json::from_str(text).unwrap_or(serde_json::Value::Null)
}

fn json_to_string(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// 失败时挑一句能看懂的文案（映射表来自原项目 common.dart）。
fn failure_message(status_code: u16, value: &serde_json::Value) -> String {
    if status_code == 401 || status_code == 403 {
        return "登录已过期，请重新登录".to_string();
    }
    // 雷达签到失败没有 status，只有 error_code / status_name。
    if let Some(code) = value.get("error_code").and_then(|v| v.as_str()) {
        if let Some(m) = radar_error_message(code, value) {
            return m;
        }
    }
    let status = value.get("status").and_then(|v| v.as_str()).unwrap_or("");
    if let Some(m) = status_message(status) {
        return m.to_string();
    }
    if let Some(m) = value.get("message").and_then(|v| v.as_str()) {
        if !m.is_empty() {
            return m.to_string();
        }
    }
    format!("签到失败（HTTP {status_code}）")
}

/// 雷达签到的错误码文案（响应字段是 error_code，不是 status）。
fn radar_error_message(code: &str, value: &serde_json::Value) -> Option<String> {
    Some(match code {
        "radar_out_of_rollcall_scope" => match value.get("distance").and_then(|v| v.as_f64()) {
            Some(d) => format!("不在签到范围内（距离约 {} 米）", d.round() as i64),
            None => "不在签到范围内".to_string(),
        },
        "radar_rollcall_finished" => "点名已结束".to_string(),
        _ => return None,
    })
}

fn status_message(status: &str) -> Option<&'static str> {
    Some(match status {
        "rollcall_closed" => "二维码签到已结束",
        "device_used" => "该设备已签到，请更换设备重新扫描",
        "deviceAlreadyInUse" => "已有学生使用该设备签到，请更换设备再试",
        "QR_code_expired" => "签到二维码已过期",
        "unknown_student" => "您还不是本课学生，请先加入课程",
        "rollcallFinished" => "签到失败，点名已结束",
        "wrongNumberCode" => "签到密码错误，请重试",
        "absent" => "未到",
        "outofScope" => "当前定位信息获取异常，可能导致签到失败，请尝试重新签到",
        _ => return None,
    })
}

/// 给请求补上访问畅课接口要带的头（原项目 apiHeaders + XHR 标记）。
fn authed(req: reqwest::RequestBuilder, cookie: &str) -> reqwest::RequestBuilder {
    req.header("Cookie", cookie)
        .header("X-Requested-With", "XMLHttpRequest")
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json, text/plain, */*")
        .header("Referer", "http://localhost/")
        .header("Origin", "http://localhost")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 抓包里那条雷达失败响应（超范围）。
    const OUT_OF_SCOPE: &str = r#"{"distance":598990.2518034368,
        "error_code":"radar_out_of_rollcall_scope","id":null,
        "message":"out of rollcall scope","status_name":"on_call_fine"}"#;

    #[test]
    fn radar_out_of_scope_reads_chinese_distance() {
        let msg = failure_message(400, &parse_json(OUT_OF_SCOPE));
        assert_eq!(msg, "不在签到范围内（距离约 598990 米）");
    }

    #[test]
    fn radar_without_distance_still_readable() {
        let msg = failure_message(400, &parse_json(r#"{"error_code":"radar_out_of_rollcall_scope"}"#));
        assert_eq!(msg, "不在签到范围内");
    }

    #[test]
    fn unknown_error_code_falls_back_to_message() {
        let msg = failure_message(400, &parse_json(r#"{"error_code":"whatever","message":"boom"}"#));
        assert_eq!(msg, "boom");
    }
}
