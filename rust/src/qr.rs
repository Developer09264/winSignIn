//! 畅课二维码串的解析（从原 Flutter 项目 `lib/data/util/changke.dart` 移植）。
//!
//! 扫码得到的通常是一个 URL，形如 `http://lms.tc.cqupt.edu.cn/j?p=<压缩串>`；
//! 压缩串用自创的编码：`!` 分字段、`~` 分 key/value，key 是字段名的 base36
//! 下标，value 用控制字符标记布尔/数字/转义。这里就是把这套还原成普通 JSON。

use serde_json::{Map, Value};

/// 控制字符标记（原项目用 String.fromCharCode(n) 定义）。
const TA: char = '\u{1e}'; // 30
const EA: char = '\u{1f}'; // 31
const NA: char = '\u{1a}'; // 26
const RA: char = '\u{10}'; // 16
const IA: &str = "\u{1a}1"; // 真
const OA: &str = "\u{1a}0"; // 假

/// base36 下标 -> 字段名（原项目 aa 的 key 列表，顺序不能动）。
const AA_KEYS: [&str; 11] = [
    "courseId",
    "activityId",
    "activityType",
    "data",
    "rollcallId",
    "groupSetId",
    "accessCode",
    "action",
    "enableGroupRollcall",
    "createUser",
    "joinCourse",
];

/// 「唯一值」表的候选，编码成 NA + base36(下标 + 2)。
const UA_KEYS: [&str; 3] = ["classroom-exam", "feedback", "vote"];

/// 解析结果：签到只需要这两个字段。
pub struct SignData {
    pub rollcall_id: String,
    pub data: Value,
}

/// 扫描得到的原始字符串 -> {rollcallId, data}。
pub fn extract_sign_data(raw: &str) -> Result<SignData, String> {
    let map = scan_url_analysis(raw)?;

    let rollcall_id = map
        .get("rollcallId")
        .filter(|v| !v.is_null())
        .map(value_to_string)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "二维码格式错误：缺少 rollcallId".to_string())?;

    let data = map
        .get("data")
        .filter(|v| !v.is_null())
        .cloned()
        .ok_or_else(|| "二维码格式错误：缺少 data".to_string())?;

    Ok(SignData { rollcall_id, data })
}

fn value_to_string(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// 把扫码结果还原成一个字段 map；不是畅课的签到二维码就报错。
fn scan_url_analysis(raw: &str) -> Result<Map<String, Value>, String> {
    // 相对路径（/j?p=...）补成学校地址
    let mut e = raw.to_string();
    if e.contains("/j?p=") && !e.starts_with("http") {
        e = format!("{}{}", crate::API_BASE, e);
    }
    if !e.starts_with("http") {
        return Err("二维码格式错误".to_string());
    }

    let url = url::Url::parse(&e).map_err(|_| "二维码格式错误".to_string())?;
    if url.path() != "/j" && url.path() != "/scanner-jumper" {
        return Err("二维码格式错误".to_string());
    }

    // 优先 _p（直接是 JSON），否则 p（压缩串）
    if let Some(p) = query_param(&url, "_p") {
        if let Ok(Value::Object(m)) = serde_json::from_str::<Value>(&p) {
            if !m.is_empty() {
                return Ok(m);
            }
        }
    }
    if let Some(p) = query_param(&url, "p") {
        let m = parse_sign_qr_code(&p);
        if !m.is_empty() {
            return Ok(m);
        }
    }
    Err("二维码格式错误".to_string())
}

fn query_param(url: &url::Url, key: &str) -> Option<String> {
    url.query_pairs()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.into_owned())
}

/// 压缩串 -> 字段 map。
pub fn parse_sign_qr_code(t: &str) -> Map<String, Value> {
    let mut result = Map::new();
    if t.is_empty() {
        return result;
    }

    for part in t.split('!').filter(|p| !p.is_empty()) {
        let splitted: Vec<&str> = part.split('~').collect();
        if splitted.len() < 2 {
            continue;
        }
        let r = splitted[0];
        let i_value = splitted[1..].join("~");
        let key = field_name(r);

        let value = if i_value.starts_with(NA) {
            if i_value == IA {
                Value::Bool(true)
            } else if i_value == OA {
                Value::Bool(false)
            } else if let Some(name) = unique_value_name(&i_value) {
                Value::String(name.to_string())
            } else {
                Value::String(i_value.clone())
            }
        } else if i_value.starts_with(RA) {
            base36_number(&i_value)
        } else {
            // 转义还原：EA -> ~，TA -> !
            Value::String(i_value.replace(EA, "~").replace(TA, "!"))
        };

        result.insert(key, value);
    }

    result
}

/// base36 的字段名（`3` -> `data`），认不出就原样返回。
fn field_name(r: &str) -> String {
    for (i, name) in AA_KEYS.iter().enumerate() {
        if to_base36(i as i64) == r {
            return (*name).to_string();
        }
    }
    r.to_string()
}

/// NA + base36(下标+2) -> 唯一值名，认不出返回 None。
fn unique_value_name(v: &str) -> Option<&'static str> {
    let rest = v.strip_prefix(NA)?;
    UA_KEYS
        .iter()
        .enumerate()
        .find(|(i, _)| to_base36((i + 2) as i64) == rest)
        .map(|(_, name)| *name)
}

/// `\x10` 前缀后面是 base36，可能带 `.` 表示小数。
fn base36_number(i_value: &str) -> Value {
    let substr = &i_value[RA.len_utf8()..];
    let mut nums: Vec<i64> = Vec::new();
    for p in substr.split('.') {
        match i64::from_str_radix(p, 36) {
            Ok(n) => nums.push(n),
            Err(_) => {
                // 原项目：任何一个解析失败就整段作废
                nums.clear();
                break;
            }
        }
    }

    if nums.len() > 1 {
        let s = format!("{}.{}", nums[0], nums[1]);
        match s.parse::<f64>() {
            Ok(f) => serde_json::json!(f),
            Err(_) => Value::String(s),
        }
    } else if !nums.is_empty() {
        serde_json::json!(nums[0])
    } else {
        Value::String(i_value.to_string())
    }
}

fn to_base36(mut num: i64) -> String {
    const CHARS: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyz";
    if num < 0 {
        return format!("-{}", to_base36(-num));
    }
    if num < 36 {
        return (CHARS[num as usize] as char).to_string();
    }
    let mut result = String::new();
    while num > 0 {
        let rem = (num % 36) as usize;
        num /= 36;
        result.insert(0, CHARS[rem] as char);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_basic_fields() {
        // key "3" = data，key "4" = rollcallId
        let m = parse_sign_qr_code("3~hello!4~12345");
        assert_eq!(m.get("data"), Some(&json!("hello")));
        assert_eq!(m.get("rollcallId"), Some(&json!("12345")));
    }

    #[test]
    fn maps_boolean_markers() {
        // \x1a1 = 真，\x1a0 = 假
        let m = parse_sign_qr_code("8~\u{1a}1!7~\u{1a}0");
        assert_eq!(m.get("enableGroupRollcall"), Some(&json!(true)));
        assert_eq!(m.get("action"), Some(&json!(false)));
    }

    #[test]
    fn maps_unique_values() {
        // \x1a3 = ua 表下标 1(feedback) 的编码
        let m = parse_sign_qr_code("2~\u{1a}3");
        assert_eq!(m.get("activityType"), Some(&json!("feedback")));
    }

    #[test]
    fn decodes_base36_numbers() {
        // \x10a：base36 的 a = 10
        let m = parse_sign_qr_code("6~\u{10}a");
        assert_eq!(m.get("accessCode"), Some(&json!(10)));
    }

    #[test]
    fn unescapes_control_chars() {
        // \x1f -> ~
        let m = parse_sign_qr_code("3~a\u{1f}b");
        assert_eq!(m.get("data"), Some(&json!("a~b")));
    }

    #[test]
    fn extracts_from_absolute_url() {
        let d = extract_sign_data("http://lms.tc.cqupt.edu.cn/j?p=3~hello!4~99").unwrap();
        assert_eq!(d.rollcall_id, "99");
        assert_eq!(d.data, json!("hello"));
    }

    #[test]
    fn extracts_from_relative_url() {
        let d = extract_sign_data("/j?p=3~hello!4~99").unwrap();
        assert_eq!(d.rollcall_id, "99");
    }

    #[test]
    fn extracts_from_json_param() {
        let d = extract_sign_data(
            "http://lms.tc.cqupt.edu.cn/j?_p={\"rollcallId\":\"7\",\"data\":\"xyz\"}",
        )
        .unwrap();
        assert_eq!(d.rollcall_id, "7");
        assert_eq!(d.data, json!("xyz"));
    }

    #[test]
    fn rejects_junk() {
        assert!(extract_sign_data("https://example.com/foo").is_err());
        assert!(extract_sign_data("just some text").is_err());
    }
}
