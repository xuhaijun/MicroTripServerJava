"""为模拟器走查准备云端口径的测试账号与轨迹数据。

与 verify_stats_contract.py 的区别：那个脚本用随机手机号做契约校验（跑完即弃），
本脚本用**固定账号**，让「我的」页登录后能看到一组有代表性的统计数字。

⚠️ 仅限本地开发环境：脚本硬编码 127.0.0.1:3000 与固定弱口令测试账号，
   会向目标实例写入演示数据。切勿指向预发 / 生产环境。
"""
import datetime
import json
import urllib.request
import urllib.error

API = "http://127.0.0.1:3000/api/v1"
PHONE = "13900000001"
PASSWORD = "Test123456"


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(API + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main():
    code, reg = req("POST", "/auth/register",
                    {"phone": PHONE, "password": PASSWORD, "nickname": "徐海君"})
    if code in (200, 201):
        token = reg.get("token")
        print(f"注册成功 {PHONE}")
    else:
        # 已存在则登录
        code, reg = req("POST", "/auth/login", {"phone": PHONE, "password": PASSWORD})
        if code not in (200, 201):
            raise SystemExit(f"登录失败 {code}: {reg}")
        token = reg.get("token")
        print(f"账号已存在，登录成功 {PHONE}")

    # 三条轨迹，数值有辨识度，便于肉眼核对卡片：
    #   累计 = 12.5 + 8.0 + 23.7 = 44.2 km，时长 0.5 + 1.0 + 2.5 = 4 小时
    # 时间用 2026 年的真实日期（不是硬编码 epoch —— 手写时间戳极易落到错误年份，
    # 走查截图里出现「上一年」的出行日期会让人怀疑是统计 bug）。
    specs = [
        ("trip_chengdu_01", "青城山徒步", 12500.0, 1800, 620.0, 615.0, (2026, 3, 15)),
        ("trip_chengdu_02", "锦江夜跑", 8000.0, 3600, 40.0, 45.0, (2026, 8, 22)),
        ("trip_emei_03", "峨眉山登顶", 23700.0, 9000, 1580.0, 1560.0, (2026, 9, 8)),
    ]
    for tid, title, dist, dur, asc, desc, d in specs:
        start = int(datetime.datetime(*d, 8, 30).timestamp() * 1000)
        pts = [{"lat": 30.65 + i * 0.001, "lng": 104.07 + i * 0.001,
                "ts": start + i * 60000} for i in range(30)]
        code, resp = req("POST", "/trajectory/sync", {
            "trajectory": {
                "id": tid, "title": title, "city": "成都", "start": start,
                "distance": dist, "duration": dur,
                "ascent": asc, "descent": desc, "pts": pts,
            }
        }, token=token)
        print(f"  同步 {title}: {code} points={resp.get('points') if isinstance(resp, dict) else resp}")

    code, stats = req("GET", "/trajectory/stats", token=token)
    print("\n云端统计:", json.dumps(stats, ensure_ascii=False, indent=2))
    print(f"\n账号：{PHONE} / {PASSWORD}")
    print(f"卡片将显示：累计 {stats['totalDistance']/1000:.1f} km、"
          f"{stats['totalDuration']//3600} 小时、{round(stats['totalAscent'])} m 爬升、"
          f"{stats['count']} 次出行、{stats['totalPoints']:,} 采样点")


if __name__ == "__main__":
    main()
