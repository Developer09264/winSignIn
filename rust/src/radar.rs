//! 雷达（位置）签到相关的纯计算：数字码解析 + 三边定位。
//! 和网络无关，方便离线单测。

use serde_json::Value;

/// 和服务器一致的球半径（fake_tron_server 用 haversine R=6371000）。
const EARTH_RADIUS_M: f64 = 6_371_000.0;

/// 从 `student_rollcalls` 响应里挖出 4 位数字码。
/// 兼容几种形态：顶层、`data` 里、`student_rollcalls[]`/`data[]` 里、裸数组。
pub fn extract_number_code(value: &Value) -> Option<String> {
    let from_obj = |obj: &Value| -> Option<String> {
        obj.get("number_code").and_then(coerce_code)
    };

    if let Some(code) = from_obj(value) {
        return Some(code);
    }
    if let Some(data) = value.get("data") {
        if let Some(code) = from_obj(data) {
            return Some(code);
        }
        if let Some(items) = data.as_array() {
            if let Some(code) = items.iter().find_map(from_obj) {
                return Some(code);
            }
        }
    }
    for key in ["student_rollcalls", "data"] {
        if let Some(items) = value.get(key).and_then(|v| v.as_array()) {
            if let Some(code) = items.iter().find_map(from_obj) {
                return Some(code);
            }
        }
    }
    if let Some(items) = value.as_array() {
        return items.iter().find_map(from_obj);
    }
    None
}

fn coerce_code(v: &Value) -> Option<String> {
    match v {
        Value::Number(n) => {
            let i = n.as_i64()?;
            if (0..=9999).contains(&i) {
                Some(format!("{i:04}"))
            } else {
                None
            }
        }
        Value::String(s) => {
            let t = s.trim();
            if t.len() == 4 && t.chars().all(|c| c.is_ascii_digit()) {
                Some(t.to_string())
            } else {
                None
            }
        }
        _ => None,
    }
}

/// 三边定位：3 个探测点 + 到目标的距离（米） -> 目标经纬度。
///
/// 在参考点附近摊成局地平面（等距圆柱近似，R 与服务器一致），
/// 再解两圆差得到的 2×2 线性方程。点少或接近共线时返回 None。
// ponytail: 只解前 3 个点、只认 3 点，够用；要更多点最小二乘再说。
pub fn trilaterate(points: &[(f64, f64)], distances: &[f64]) -> Option<(f64, f64)> {
    if points.len() < 3 || distances.len() < 3 {
        return None;
    }
    let lat0 = points.iter().map(|p| p.0).sum::<f64>() / points.len() as f64;
    let lon0 = points.iter().map(|p| p.1).sum::<f64>() / points.len() as f64;
    let cos0 = (lat0.to_radians()).cos();

    let to_local = |p: (f64, f64)| -> (f64, f64) {
        let x = EARTH_RADIUS_M * (p.1 - lon0).to_radians() * cos0;
        let y = EARTH_RADIUS_M * (p.0 - lat0).to_radians();
        (x, y)
    };

    let (x1, y1) = to_local(points[0]);
    let (x2, y2) = to_local(points[1]);
    let (x3, y3) = to_local(points[2]);
    let (d1, d2, d3) = (distances[0], distances[1], distances[2]);

    let a1 = 2.0 * (x2 - x1);
    let b1 = 2.0 * (y2 - y1);
    let c1 = d1 * d1 - d2 * d2 + (x2 * x2 + y2 * y2) - (x1 * x1 + y1 * y1);
    let a2 = 2.0 * (x3 - x1);
    let b2 = 2.0 * (y3 - y1);
    let c2 = d1 * d1 - d3 * d3 + (x3 * x3 + y3 * y3) - (x1 * x1 + y1 * y1);

    let det = a1 * b2 - a2 * b1;
    if det.abs() < 1e-6 {
        return None;
    }
    let x = (c1 * b2 - c2 * b1) / det;
    let y = (a1 * c2 - a2 * c1) / det;

    let lat = lat0 + (y / EARTH_RADIUS_M).to_degrees();
    let lon = lon0 + (x / (EARTH_RADIUS_M * cos0)).to_degrees();
    Some((lat, lon))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn haversine(a: (f64, f64), b: (f64, f64)) -> f64 {
        let (la, loa) = (a.0.to_radians(), a.1.to_radians());
        let (lb, lob) = (b.0.to_radians(), b.1.to_radians());
        let h = ((lb - la) / 2.0).sin().powi(2)
            + la.cos() * lb.cos() * ((lob - loa) / 2.0).sin().powi(2);
        EARTH_RADIUS_M * 2.0 * h.sqrt().atan2((1.0 - h).sqrt())
    }

    #[test]
    fn trilaterates_target_within_a_meter() {
        let target = (29.535538, 106.610997); // 八教
        let probes = [
            (29.54057, 106.607061),
            (29.521378, 106.596161),
            (29.522124, 106.617533),
        ];
        let distances: Vec<f64> = probes
            .iter()
            .map(|p| haversine(*p, target))
            .collect();
        let solved = trilaterate(&probes, &distances).expect("应能解出");
        assert!(
            haversine(solved, target) < 1.0,
            "误差太大: {} m -> {:?}",
            haversine(solved, target),
            solved
        );
    }

    #[test]
    fn rejects_degenerate_input() {
        assert!(trilaterate(&[(1.0, 1.0), (2.0, 2.0)], &[1.0, 2.0]).is_none());
        // 三点重合 -> 行列式为 0
        let same = [(29.5, 106.6), (29.5, 106.6), (29.5, 106.6)];
        assert!(trilaterate(&same, &[100.0, 120.0, 90.0]).is_none());
    }

    #[test]
    fn reads_number_code_from_flat_payload() {
        let v = json!({"number_code": "0837", "status": "on_call"});
        assert_eq!(extract_number_code(&v).as_deref(), Some("0837"));
    }

    #[test]
    fn reads_number_code_from_nested_and_list_shapes() {
        assert_eq!(
            extract_number_code(&json!({"data": {"number_code": 12}})).as_deref(),
            Some("0012")
        );
        assert_eq!(
            extract_number_code(&json!({"student_rollcalls": [{"user_no": "x"}, {"number_code": "0042"}]}))
                .as_deref(),
            Some("0042")
        );
        assert_eq!(
            extract_number_code(&json!([{"number_code": "9999"}])).as_deref(),
            Some("9999")
        );
    }

    #[test]
    fn ignores_bad_codes() {
        assert_eq!(extract_number_code(&json!({"number_code": "12"})), None);
        assert_eq!(extract_number_code(&json!({"number_code": "abcd"})), None);
        assert_eq!(extract_number_code(&json!({"number_code": true})), None);
        assert_eq!(extract_number_code(&json!({"other": 1})), None);
    }
}
