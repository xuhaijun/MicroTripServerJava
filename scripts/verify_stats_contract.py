"""验证客户端 CloudStats.fromMap 的字段名与服务端 /trajectory/stats 实际返回是否一致。

存在的风险：客户端解析是按服务端 DTO 推导写的，若 JSON 字段名不一致，
「我的」页卡片会全部显示 0 而不报错 —— 这类错误在 UI 上很难发现。
本脚本把真实响应的 key 与客户端期望的 key 做集合比对。

⚠️ 自动化的契约守护已迁移到 JUnit：
    src/test/java/com/microtrip/server/StatsClientContractTest.java
    （`mvn test` 即会执行，字段名 / JSON 类型 / 空数据 NON_NULL 省略行为全覆盖）

本脚本保留的用途：对**真实运行中的实例**做端到端复现与人工排查
（打印卡片换算后的展示值、验证注册→同步→统计全链路、可指向预发环境）。
日常回归请直接跑 mvn test，不要依赖本脚本。
"""
import json
import time
import urllib.request
import urllib.error

API = "http://127.0.0.1:3000/api/v1"

# 与 lib/models/cloud_stats.dart 的 fromMap 逐字对应
CLIENT_KEYS = {
    "count", "totalDistance", "totalDuration", "totalAscent", "totalDescent",
    "maxDistance", "maxAvgSpeed", "firstStart", "lastStart", "lastSyncedAt",
    "totalPoints",
}
# 客户端会读、但服务端在无数据时可能省略的字段（NON_NULL）
OPTIONAL_KEYS = {"maxDistance", "maxAvgSpeed", "firstStart", "lastStart", "lastSyncedAt"}


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
    fails = []

    # ---------- 1. 注册 ----------
    phone = "139" + str(int(time.time()))[-8:]
    code, reg = req("POST", "/auth/register",
                    {"phone": phone, "password": "Test123456", "nickname": "走查用户"})
    print(f"[1] 注册 {phone} → {code}")
    if code not in (200, 201):
        print("    响应:", str(reg)[:300])
        raise SystemExit("注册失败，后续步骤无法进行")
    token = reg.get("token") or reg.get("data", {}).get("token")
    if not token:
        print("    响应结构:", list(reg.keys()))
        raise SystemExit("未取到 token")
    print(f"    token 长度 = {len(token)}")

    # ---------- 2. 空数据时的 stats（验证 NON_NULL 省略行为） ----------
    code, empty_stats = req("GET", "/trajectory/stats", token=token)
    print(f"\n[2] 空数据 GET /trajectory/stats → {code}")
    print("    响应:", json.dumps(empty_stats, ensure_ascii=False))
    if code != 200:
        fails.append(f"空数据 stats 返回 {code}")

    # ---------- 3. 同步两条轨迹 ----------
    trajs = [
        ("trs_alpha", 12345.0, 3600, 320.0, 280.0),
        ("trs_beta", 8000.0, 2400, 150.0, 160.0),
    ]
    for tid, dist, dur, asc, desc in trajs:
        # 注意两个易错点：
        #  1. 外层字段是 trajectory（与 Flutter 客户端 TrajectoryRecord.toMap 的包装一致）
        #  2. GPS 点数组的键是 **pts**（不是 points）—— 见 TrajectorySyncDto 的字段名。
        #     传错键名不会报错，只会静默保存 0 个点，是这类接口最阴的坑。
        code, resp = req("POST", "/trajectory/sync", {
            "trajectory": {
                "id": tid, "title": f"走查轨迹 {tid}", "city": "成都",
                "start": 1757000000000,
                "distance": dist, "duration": dur,
                "ascent": asc, "descent": desc,
                "pts": [
                    {"lat": 30.65, "lng": 104.07, "ts": 1757000000000},
                    {"lat": 30.66, "lng": 104.08, "ts": 1757000060000},
                ],
            }
        }, token=token)
        print(f"\n[3] 同步 {tid} → {code} {json.dumps(resp, ensure_ascii=False)[:160]}")
        if code != 200:
            fails.append(f"同步 {tid} 返回 {code}")
        elif resp.get("points") != 2:
            # 服务端返回的 points 是实际落库条数，可直接用来发现"静默丢点"
            fails.append(f"同步 {tid} 只落库 {resp.get('points')} 个点（应为 2）")

    # ---------- 4. 有数据时的 stats：核心字段名核对 ----------
    code, stats = req("GET", "/trajectory/stats", token=token)
    print(f"\n[4] 有数据 GET /trajectory/stats → {code}")
    print("    响应:", json.dumps(stats, ensure_ascii=False, indent=2))
    if code != 200:
        raise SystemExit("stats 接口异常，无法继续核对")

    server_keys = set(stats.keys())
    unknown = server_keys - CLIENT_KEYS
    missing = (CLIENT_KEYS - server_keys) - OPTIONAL_KEYS
    print(f"\n[5] 字段名核对")
    print(f"    服务端返回 key : {sorted(server_keys)}")
    print(f"    客户端期望 key : {sorted(CLIENT_KEYS)}")
    print(f"    客户端不认识的新字段（不影响，会被忽略）: {sorted(unknown) or '无'}")
    print(f"    客户端需要但服务端缺失的字段（危险，会退化为 0）: {sorted(missing) or '无'}")
    if missing:
        fails.append(f"字段缺失: {sorted(missing)}")

    # ---------- 6. 聚合数值正确性（客户端会原样展示，算错会直接暴露给用户） ----------
    exp = {
        "count": 2,
        "totalDistance": 20345.0,
        "totalDuration": 6000,
        "totalAscent": 470.0,
        "totalDescent": 440.0,
        "maxDistance": 12345.0,
        "totalPoints": 4,
    }
    print("\n[6] 聚合数值核对（客户端会原样展示，算错会直接暴露给用户）")
    for k, v in exp.items():
        actual = stats.get(k)
        ok = actual is not None and abs(float(actual) - float(v)) < 1e-6
        print(f"    {'OK  ' if ok else 'FAIL'} {k}: 期望 {v}, 实际 {actual}")
        if not ok:
            fails.append(f"{k} 期望 {v} 实际 {actual}")

    # 时间范围应为最早/最晚 start
    if stats.get("firstStart") != 1757000000000 or stats.get("lastStart") != 1757000000000:
        fails.append(f"时间范围异常 first={stats.get('firstStart')} last={stats.get('lastStart')}")
        print(f"    FAIL firstStart/lastStart: {stats.get('firstStart')} / {stats.get('lastStart')}")
    else:
        print("    OK   firstStart / lastStart 已返回")

    # ---------- 7. 客户端换算后的展示值（模拟卡片实际显示） ----------
    print("\n[7] 卡片实际展示（按 CloudStats 的换算规则）")
    d = float(stats["totalDistance"])
    print(f"    累计里程 : {d/1000:.1f} km")
    dur = int(stats["totalDuration"])
    h, m = dur // 3600, (dur % 3600) // 60
    print(f"    累计时长 : {h} 小时 {m} 分")
    print(f"    累计爬升 : {round(float(stats['totalAscent']))} m")
    print(f"    出行次数 : {stats['count']} 次")
    print(f"    采样点数 : {int(stats['totalPoints']):,}")

    print("\n" + ("=" * 56))
    if fails:
        print(f"结论：发现 {len(fails)} 处问题")
        for f in fails:
            print("  -", f)
        raise SystemExit(1)
    print("结论：客户端字段名与服务端响应完全对齐，聚合数值正确")


if __name__ == "__main__":
    main()
