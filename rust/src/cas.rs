//! 重邮的金智 CAS 网关登录链（从原 Flutter 项目 `lib/data/service/login.dart`
//! 的 `loginViaCasGateway` 移植）。
//!
//! 链路：Keycloak CAS 端点 → 金智 authserver 登录页 → 提交账密 → Keycloak
//! → lms/user/index。会话是 cookie（session + role_token），没有 x-session-id。
//!
//! 一个关键点：整条链在浏览器里都是**文档导航**，落回 lms 那一跳也必须用导航头，
//! 否则畅课会当成 XHR 返回 401。所以这里每跳手工跟，并区分导航/XHR 头。
//!
//! 另外：CAS 域的 cookie（CASTGC 等）会被抓下来交给调用方持久化，下次登录先灌回
//! 去。这样只要 CASTGC 还在有效期内，就能免密直接落回 lms。

use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use aes::cipher::{block_padding::Pkcs7, BlockEncryptMut, KeyIvInit};
use aes::Aes128;
use base64::Engine;
use rand::Rng;
use reqwest::cookie::{CookieStore, Jar};
use reqwest::header::HeaderValue;
use reqwest::{Client, RequestBuilder, Url};

/// 实测可用的 CAS 入口（sumrise.md 7.1）。
const CAS_LOGIN_ENDPOINT: &str =
    "http://identity.tc.cqupt.edu.cn/auth/realms/cqupt/protocol/cas/login";
/// 登录成功后要落回的页面；host 就是"到家"的判据。
const HOME: &str = "http://lms.tc.cqupt.edu.cn/user/index";
const HOME_HOST: &str = "lms.tc.cqupt.edu.cn";

/// CAS 页面的 WebView UA（原项目 user_agent.dart 的模板）。
const CAS_UA: &str = "Mozilla/5.0 (Linux; Android 12; 22021211RC Build/TKQ1.220807.001; ) \
AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/92.0.4515.105 Mobile Safari/537.36\
TronClass/common;webank/h5face;webank/1.0;netType:NETWORK_4G;appVersion:1000022;\
packageName:com.wisdomgarden.trpc";
const ACCEPT_DOCUMENT: &str = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,\
image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";
const ACCEPT_XHR: &str = "application/json, text/javascript, */*; q=0.01";
const ACCEPT_LANGUAGE: &str = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7";
/// 浏览器导航里带的 WebView 包名标记。
const REQUESTED_WITH_WEBVIEW: &str = "com.wisdomgarden.trpc";

/// 走完整条链，成功返回 (lms 会话 cookie, 更新后的 CAS cookie 串)。
///
/// [remember_me] 为 true 时提交 `rememberMe=true`，CAS 会下发长效 `CASTGC`
/// （实测 14 天），之后能靠它静默换票据，不用再输密码；false 则只给会话级 TGT。
/// [cas_cookie] 是上次抓下来的 CAS cookie，先灌回 jar——如果 CASTGC 还有效，
/// 这条链会**免密**直接落回 lms。
pub async fn acquire_cookie(
    username: &str,
    password: &str,
    bfp: &str,
    remember_me: bool,
    cas_cookie: &str,
) -> Result<(String, String), String> {
    let session = Session::new(cas_cookie)?;

    // 1. 从 CAS 入口跟跳：落金智登录页，或直接落回 lms（已有 SSO 会话）
    let entry = format!("{CAS_LOGIN_ENDPOINT}?service={}", urlencode(HOME));
    let first = session.walk(&entry).await?;
    check_re_auth(&first.url)?;
    if first.url.host_str() == Some(HOME_HOST) {
        return Ok((session.lms_cookie(), session.blob()));
    }
    if first.url.path().contains("/improveInfo/") {
        return Err(IMPROVE_INFO_TIP.to_string());
    }
    if !first.url.path().ends_with("/authserver/login") {
        return Err(format!("登录失败：未预期的落点 {}", first.url));
    }

    // 2. 从登录页抠密钥和 execution
    let html = first.body;
    let aes_key = extract_input_value(&html, "id=\"pwdEncryptSalt\"")
        .ok_or_else(|| "登录页解析失败：找不到 pwdEncryptSalt".to_string())?;
    let execution = extract_input_value(&html, "name=\"execution\"")
        .or_else(|| extract_input_value(&html, "id=\"execution\""))
        .ok_or_else(|| "登录页解析失败：找不到 execution".to_string())?;
    let origin = first.url.origin().ascii_serialization();

    // 3. 指纹上报（缺了更易被判异常；bfp 由调用方按账号持久化）
    let bfp_url = format!("{origin}/authserver/bfp/info?bfp={bfp}&_={}", now_ms());
    session.get_xhr(&bfp_url).await?;

    // 4. 验证码判断：正常不需要，需要就明确报错（不在这里弹框）
    let check_url = format!(
        "{origin}/authserver/checkNeedCaptcha.htl?username={}&_={}",
        urlencode(username),
        now_ms()
    );
    let check_body = session.get_xhr(&check_url).await?.body;
    let need_captcha = serde_json::from_str::<serde_json::Value>(&check_body)
        .ok()
        .and_then(|v| v.get("isNeed").and_then(|x| x.as_bool()))
        .unwrap_or(false);
    if need_captcha {
        return Err("本次登录需要验证码，请改用「网页登录」".to_string());
    }

    // 5. 提交账号密码。service 用登录页 URL 里那份，execution 和 ticket 都跟它绑定
    let service = query_param(&first.url, "service").unwrap_or_else(|| HOME.to_string());
    let post_url = format!("{origin}/authserver/login?service={}", urlencode(&service));
    let encrypted = encrypt_password(password, &aes_key)?;

    // 勾了"记住我"才带 rememberMe（跟浏览器勾选框语义一致）；不带就是会话级 TGT
    let mut form: Vec<(&str, &str)> = vec![
        ("username", username),
        ("password", encrypted.as_str()),
    ];
    if remember_me {
        form.push(("rememberMe", "true"));
    }
    form.extend_from_slice(&[
        ("captcha", ""),
        ("_eventId", "submit"),
        ("cllt", "userNameLogin"),
        ("dllt", "generalLogin"),
        ("lt", ""),
        ("execution", execution.as_str()),
    ]);

    let posted = session.post_form(&post_url, &form).await?;
    check_re_auth(&posted.url)?;
    // 提交后仍停在登录页 = 认证没过（重邮账号/密码错都是 401）
    if posted.status == 401
        || (posted.status == 200 && posted.url.path().ends_with("/authserver/login"))
    {
        return Err(error_tip(&posted.body));
    }

    // 6. 继续跟跳直到落回学校主页
    let landed = session.walk(&post_url).await?;
    check_re_auth(&landed.url)?;
    if landed.url.host_str() != Some(HOME_HOST) {
        if landed.url.path().contains("/improveInfo/") {
            return Err(IMPROVE_INFO_TIP.to_string());
        }
        return Err(format!("登录失败：CAS 链没有回到 {HOME_HOST}"));
    }
    Ok((session.lms_cookie(), session.blob()))
}

/// 用已保存的 CAS cookie 静默刷新会话（免密）。CASTGC 还有效就成功。
pub async fn refresh(cas_cookie: &str) -> Result<(String, String), String> {
    let session = Session::new(cas_cookie)?;
    let entry = format!("{CAS_LOGIN_ENDPOINT}?service={}", urlencode(HOME));
    let first = session.walk(&entry).await?;
    if first.url.host_str() == Some(HOME_HOST) {
        Ok((session.lms_cookie(), session.blob()))
    } else {
        Err("统一认证会话已过期，请重新输入密码登录".to_string())
    }
}

const IMPROVE_INFO_TIP: &str =
    "统一认证要求先绑定手机号：请用手机浏览器登录一次网页版完成绑定后再试";

fn check_re_auth(url: &Url) -> Result<(), String> {
    if url.as_str().contains("/authserver/reAuthCheck/reAuthLoginView.do") {
        Err("需要短信二次验证，请改用「网页登录」".to_string())
    } else {
        Ok(())
    }
}

struct RawResp {
    status: u16,
    url: Url,
    body: String,
    location: Option<String>,
}

/// 一次登录/刷新过程：带 cookie 的 client + 抓下来的 CAS cookie。
struct Session {
    client: Client,
    jar: Arc<Jar>,
    /// 每个响应的 (URL, Set-Cookie 原始行)，用来持久化 CAS cookie。
    captured: Mutex<Vec<(String, String)>>,
}

impl Session {
    fn new(seed: &str) -> Result<Self, String> {
        let jar = Arc::new(Jar::default());
        // 先把上次存的 CAS cookie 灌回 jar
        for (url, line) in parse_cookie_blob(seed) {
            if let (Ok(u), Ok(hv)) = (Url::parse(&url), HeaderValue::from_str(&line)) {
                jar.set_cookies(&mut std::iter::once(&hv), &u);
            }
        }
        let client = Client::builder()
            .cookie_provider(jar.clone())
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|e| format!("创建 HTTP 客户端失败：{e}"))?;
        Ok(Self {
            client,
            jar,
            captured: Mutex::new(Vec::new()),
        })
    }

    async fn walk(&self, start: &str) -> Result<RawResp, String> {
        let mut current = Url::parse(start).map_err(|e| format!("URL 解析失败：{e}"))?;
        for _ in 0..12 {
            let resp = self.get_nav(&current).await?;
            if (300..400).contains(&resp.status) {
                if let Some(loc) = &resp.location {
                    current = current
                        .join(loc)
                        .map_err(|e| format!("跳转地址解析失败：{e}"))?;
                    continue;
                }
            }
            return Ok(resp);
        }
        Err("CAS 跳转链过长，可能被风控拦了".to_string())
    }

    async fn get_nav(&self, url: &Url) -> Result<RawResp, String> {
        let req = self
            .client
            .get(url.clone())
            .header("User-Agent", CAS_UA)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Accept", ACCEPT_DOCUMENT)
            .header("X-Requested-With", REQUESTED_WITH_WEBVIEW)
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-User", "?1")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Referer", url.as_str());
        self.send(req).await
    }

    async fn get_xhr(&self, url: &str) -> Result<RawResp, String> {
        let parsed = Url::parse(url).map_err(|e| format!("URL 解析失败：{e}"))?;
        let req = self
            .client
            .get(parsed.clone())
            .header("User-Agent", CAS_UA)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Accept", ACCEPT_XHR)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Referer", parsed.as_str());
        self.send(req).await
    }

    async fn post_form(&self, url: &str, form: &[(&str, &str)]) -> Result<RawResp, String> {
        let parsed = Url::parse(url).map_err(|e| format!("URL 解析失败：{e}"))?;
        let req = self
            .client
            .post(parsed.clone())
            .header("User-Agent", CAS_UA)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Accept", ACCEPT_DOCUMENT)
            .header("X-Requested-With", REQUESTED_WITH_WEBVIEW)
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-User", "?1")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Referer", parsed.as_str())
            .form(form);
        self.send(req).await
    }

    async fn send(&self, req: RequestBuilder) -> Result<RawResp, String> {
        let resp = req.send().await.map_err(|e| format!("网络错误：{e}"))?;
        let status = resp.status().as_u16();
        let url = resp.url().clone();
        let location = resp
            .headers()
            .get("location")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        // 抓 Set-Cookie（含属性）用于持久化
        if let Ok(mut cap) = self.captured.lock() {
            for hv in resp.headers().get_all("set-cookie") {
                if let Ok(line) = hv.to_str() {
                    cap.push((url.as_str().to_string(), line.to_string()));
                }
            }
        }
        let body = resp.text().await.map_err(|e| format!("读取响应失败：{e}"))?;
        Ok(RawResp {
            status,
            url,
            body,
            location,
        })
    }

    /// 取 lms 域下的 cookie 串（session=...; role_token=...）。
    fn lms_cookie(&self) -> String {
        let url = Url::parse("http://lms.tc.cqupt.edu.cn/").expect("固定 URL 不会错");
        self.jar
            .cookies(&url)
            .and_then(|v| v.to_str().ok().map(str::to_string))
            .unwrap_or_default()
    }

    /// 抓下来的 CAS cookie 序列化：每行 `url<TAB>Set-Cookie`。
    fn blob(&self) -> String {
        let cap = match self.captured.lock() {
            Ok(c) => c,
            Err(_) => return String::new(),
        };
        cap.iter()
            .map(|(u, c)| format!("{u}\t{c}"))
            .collect::<Vec<_>>()
            .join("\n")
    }
}

fn parse_cookie_blob(blob: &str) -> Vec<(String, String)> {
    blob.lines()
        .filter_map(|line| {
            let (url, cookie) = line.split_once('\t')?;
            Some((url.to_string(), cookie.to_string()))
        })
        .collect()
}

/// AES-128-CBC / PKCS7：明文 = 64 随机字符 + 密码，IV = 16 个 ASCII 随机字符。
fn encrypt_password(password: &str, salt: &str) -> Result<String, String> {
    const CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::thread_rng();
    let mut pick = |n: usize| -> Vec<u8> {
        (0..n)
            .map(|_| CHARS[rng.gen_range(0..CHARS.len())])
            .collect()
    };
    let iv = pick(16);
    let mut plaintext = pick(64);
    plaintext.extend_from_slice(password.as_bytes());

    let key = salt.as_bytes();
    if key.len() != 16 {
        return Err(format!("密码盐长度不支持：{}", key.len()));
    }
    let cipher = cbc::Encryptor::<Aes128>::new(key.into(), iv.as_slice().into());

    let len = plaintext.len();
    plaintext.resize(len + 16, 0);
    let ciphertext = cipher
        .encrypt_padded_mut::<Pkcs7>(&mut plaintext, len)
        .map_err(|e| format!("密码加密失败：{e}"))?;
    Ok(base64::engine::general_purpose::STANDARD.encode(ciphertext))
}

/// 从登录页里抠某个 input 的 value；[key] 形如 `id="pwdEncryptSalt"`。
fn extract_input_value(html: &str, key: &str) -> Option<String> {
    let idx = html.find(key)?;
    let start = html[..idx].rfind('<')?;
    let end = idx + html[idx..].find('>')?;
    let tag = &html[start..end];
    let vstart = tag.find("value")? + "value".len();
    let rest = tag[vstart..].trim_start();
    let rest = rest.strip_prefix('=')?.trim_start();
    let rest = rest.strip_prefix('"')?;
    let vend = rest.find('"')?;
    Some(rest[..vend].to_string())
}

/// 抠错误提示文案，抠不到给通用文案。
fn error_tip(html: &str) -> String {
    for id in ["showErrorTip", "formErrorTip2", "formErrorTip"] {
        if let Some(t) = extract_element_text(html, id) {
            return t;
        }
    }
    "账号或密码不正确".to_string()
}

fn extract_element_text(html: &str, id: &str) -> Option<String> {
    let key = format!("id=\"{id}\"");
    let idx = html.find(&key)?;
    let open_end = idx + html[idx..].find('>')? + 1;
    let close = open_end + html[open_end..].find('<')?;
    let text = strip_tags(&html[open_end..close]);
    let text = text.trim();
    if text.is_empty() {
        None
    } else {
        Some(text.to_string())
    }
}

fn strip_tags(s: &str) -> String {
    let mut out = String::new();
    let mut in_tag = false;
    for c in s.chars() {
        match c {
            '<' => in_tag = true,
            '>' => in_tag = false,
            _ if !in_tag => out.push(c),
            _ => {}
        }
    }
    out
}

fn query_param(url: &Url, key: &str) -> Option<String> {
    url.query_pairs()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.into_owned())
}

fn urlencode(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_input_value() {
        let html = r#"<input type="hidden" name="execution" value="e1s1" />
                      <input id="pwdEncryptSalt" value="AbC1234567890xyz" />"#;
        assert_eq!(
            extract_input_value(html, "id=\"pwdEncryptSalt\"").as_deref(),
            Some("AbC1234567890xyz")
        );
        assert_eq!(
            extract_input_value(html, "name=\"execution\"").as_deref(),
            Some("e1s1")
        );
    }

    #[test]
    fn extracts_error_tip() {
        let html = r#"<div id="showErrorTip">用户名或密码错误</div>"#;
        assert_eq!(error_tip(html), "用户名或密码错误");
        assert_eq!(error_tip("<html></html>"), "账号或密码不正确");
    }

    #[test]
    fn encrypts_password_to_base64() {
        let out = encrypt_password("secret", "0123456789abcdef").unwrap();
        let raw = base64::engine::general_purpose::STANDARD.decode(out).unwrap();
        assert!(!raw.is_empty());
        assert_eq!(raw.len() % 16, 0);
    }

    #[test]
    fn round_trips_cookie_blob() {
        let blob = "https://ids.cqupt.edu.cn/authserver/login\tCASTGC=TGT-1; Path=/authserver\n\
                    https://ids.cqupt.edu.cn/\tJSESSIONID=abc; Path=/";
        let parsed = parse_cookie_blob(blob);
        assert_eq!(parsed.len(), 2);
        assert_eq!(parsed[0].0, "https://ids.cqupt.edu.cn/authserver/login");
        assert!(parsed[0].1.starts_with("CASTGC=TGT-1"));
    }
}
