# 泛微 E10 集成企业微信日程（会议提醒）实施文档

> 适用范围：泛微 E10（本地化部署 OA）会议模块 + 企业微信（WeCom）「日程 / OA 日历」能力
> 目标：在 E10 会议模块发起 / 审批通过会议后，自动在企业微信为参会人创建日程，并按设定提前提醒，避免漏会。
> 文档性质：实施指南。其中企业微信接口为官方公开 API；E10 表单 / 表名需结合你方实际环境核对（测试服务器 `https://oa.jiaweiyi.com`，即环境信息中的 `47.106.103.169`）。

---

## 1. 背景与目标

E10 会议模块本身**不自带**"推送企业微信日程"能力。标准做法是：

**会议创建 / 审批通过 → 触发同步动作 → 调用企业微信「OA 日程」接口为参会人创建日程 → 企业微信自动按设定提前提醒。**

建议采用「**E10 接口动作 + 自建轻量同步服务**」架构：access_token 缓存、userid 映射、重试与幂等都放在同步服务里，E10 侧只负责"触发"。

---

## 2. 前置条件

| 项 | 说明 |
| --- | --- |
| 企业微信自建应用 | 在企业微信后台「应用管理」创建自建应用，记录 `corpid:1000012`、`corpsecret:1lqdW8Q1aPVmkZVZRi3Pvzb5Th7xIVL7-xE4L4bvrQM` |
| OA 日程权限 | 自建应用需勾选「**企业微信日程（OA）**」相关接口权限 |
| 通讯录权限 | 应用需能读取参会人信息（用于映射与日历成员可见性） |
| E10 ↔ 企微映射 | E10 后台「系统设置 → 企业微信集成」中维护 E10 账号 ↔ 企业微信 `userid` 映射 |
| 网络可达 | 同步服务需能访问 `qyapi.weixin.qq.com`；E10 需能访问同步服务地址 |

> 环境信息中 E10 访问地址为 `http://47.106.103.169`（即测试服务器 `https://oa.jiaweiyi.com`），数据库 `ecology10` 等连接串见《E10常用信息》。

---

## 3. 总体架构

```
E10 会议模块（创建 / 审批通过）
        │ 触发（接口动作 / 自定义Action / 定时扫描）
        ▼
E10 接口动作  POST 会议信息
        │  {title, start, end, location, attendees(E10账号)}
        ▼
自建同步服务  /syncMeeting
   ├─ ① 查 E10→企微 userid 映射
   ├─ ② 换取 access_token（缓存 7200s）
   ├─ ③ 创建 / 复用共享日历，加参会人为成员
   └─ ④ oa/schedule/add 创建日程
        │
        ▼
企业微信日程（自动提醒参会人）
```

数据回流（可选）：将 `schedule_id` 回写到 E10 会议记录，便于后续「改期 / 取消」时调用 `update` / `del`。

---

## 4. 企业微信日程 API

基础域名：`https://qyapi.weixin.qq.com/cgi-bin/`
所有接口需携带 `access_token=ACCESS_TOKEN`（除获取 token 外）。

### 4.1 获取 access_token

```
GET /cgi-bin/gettoken?corpid=ID&corpsecret=SECRET
```

返回：
```json
{
  "errcode": 0,
  "errmsg": "ok",
  "access_token": "accesstoken000001",
  "expires_in": 7200
}
```

> 必须在服务端缓存，过期前复用；多实例部署建议用 Redis 统一缓存（环境信息中 Redis：`47.106.103.169:20100`）。

### 4.2 创建共享日历

为一次会议创建一个共享日历，把参会人都加为日历成员，后续日程都挂在该日历下，所有成员可见并收到提醒。

```
POST /cgi-bin/oa/calendar/add?access_token=TOKEN
```

请求体：
```json
{
  "title": "E10会议日历",
  "description": "来自 OA 的会议提醒",
  "shares": [
    {"userid": "zhangsan", "readonly": 0},
    {"userid": "lisi", "readonly": 0}
  ]
}
```

返回：
```json
{
  "errcode": 0,
  "errmsg": "ok",
  "cal_id": "wcjesse95we10saj919iapkjsadf"
}
```

> 首次创建后，`cal_id` 可复用（按会议ID或按天缓存），避免每次会议都新建日历。也可改用每个参会人各自的默认日历（`oa/calendar/list` 获取），但共享日历更易管理。

### 4.3 创建日程

```
POST /cgi-bin/oa/schedule/add?access_token=TOKEN
```

请求体：
```json
{
  "schedule": {
    "calendar_id": "wcjesse95we10saj919iapkjsadf",
    "title": "季度经营分析会",
    "description": "议题：Q2 复盘 / Q3 规划",
    "start_time": 1787000000,
    "end_time": 1787003600,
    "location": "3 楼大会议室",
    "attendees": [
      {"userid": "zhangsan", "response_status": 3},
      {"userid": "lisi", "response_status": 3}
    ],
    "reminders": [
      {"is_remind": 1, "remind_before_event_secs": 3600},
      {"is_remind": 1, "remind_before_event_secs": 600}
    ]
  }
}
```

返回：
```json
{
  "errcode": 0,
  "errmsg": "ok",
  "schedule_id": "schedule17937391935028528580"
}
```

> **`schedule_id` 务必回存**，用于后续改期 / 取消。

### 4.4 更新日程

```
POST /cgi-bin/oa/schedule/update?access_token=TOKEN
```

```json
{
  "schedule": {
    "schedule_id": "schedule17937391935028528580",
    "calendar_id": "wcjesse95we10saj919iapkjsadf",
    "title": "季度经营分析会（改期）",
    "start_time": 1787100000,
    "end_time": 1787103600,
    "attendees": [
      {"userid": "zhangsan", "response_status": 3}
    ]
  }
}
```

### 4.5 删除日程

```
POST /cgi-bin/oa/schedule/del?access_token=TOKEN
```

```json
{ "schedule_id": "schedule17937391935028528580" }
```

### 4.6 关键字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `start_time` / `end_time` | int | **秒级 Unix 时间戳**，时区按 Asia/Shanghai |
| `attendees[].userid` | string | 企业微信成员 userid |
| `attendees[].response_status` | int | `3` = 待定（未回应）；`1`= 接受，`2`= 拒绝 |
| `reminders[].is_remind` | int | `1` 开启提醒，`0` 关闭 |
| `reminders[].remind_before_event_secs` | int | 提前 N 秒提醒（如 3600=提前1小时，600=提前10分钟） |
| `location` | string | 会议地点 / 会议室 |

### 4.7 常见错误码

| errcode | 含义 | 处理 |
| --- | --- | --- |
| 40014 | 不合法的 access_token | 重新获取 token |
| 41001 | 缺少 access_token | 检查参数拼接 |
| 60020 | 应用无权访问日历 | 检查 OA 日程权限是否勾选 |
| 60123 | 日历 / 日程不存在 | 确认 `cal_id` / `schedule_id` |
| 45009 | 接口调用超限 | 加入限流 / 重试 |

---

## 5. E10 侧触发方式

### 5.1 表单建模「接口动作」（推荐，会议基于表单建模时）

1. 进入「**表单建模 → 会议表单 → 动作设置**」。
2. 新增一个 **RESTful / 接口动作**。
3. 触发时机选择「**提交后 / 审批通过后**」。
4. 动作地址填同步服务：
   ```
   http://<同步服务地址>/syncMeeting
   ```
5. 传参（示例，按表单字段名调整）：
   - 会议标题、开始时间、结束时间、地点、参会人（E10 账号数组）。

### 5.2 自定义 Java Action（独立会议模块 / 需要强控制时）

实现一个继承 E10 Action 接口的类，在会议保存后调用同步服务（或直接调用企微 API）。动作类打包后部署到 E10 `webapps/ROOT/WEB-INF/lib`，在会议模块「自定义动作」中挂接。

```java
public class MeetingToWeComAction implements Action {
    private static final String SYNC_URL = "http://<同步服务地址>/syncMeeting";

    @Override
    public String execute(RequestInfo request) {
        // 1. 从 request 取会议字段（标题/时间/地点/参会人）
        // 2. HttpPost 调用 SYNC_URL
        // 3. 解析返回，将 schedule_id 回写会议记录
        return Action.SUCCESS;
    }
}
```

### 5.3 定时增量扫描（兜底，对 E10 零侵入）

同步服务起一个定时任务，按 `create_time` 增量扫描 `ecology10` 库会议表（表名依版本核实，一般为 `Meeting` 系列表），拉取新建 / 变更记录同步。适合无法改动表单动作的场景。

---

## 6. E10 ↔ 企业微信 userid 映射

参会人在 E10 中是账号（如工号），同步服务需转为企业微信 `userid`。来源二选一：

- **映射表**：E10 集成企微时生成的映射表（一般在 `ecology10` 库 `wechat` / `wx` 前缀表，或「企业微信集成」配置导出）。
- **直接透传**：若约定"E10 登录名 = 企业微信 userid"，可省去映射。

建议同步服务内置一个 `e10ToWeCom(userId)` 查询方法，并对查不到映射的人员记录告警（不阻断整体同步）。

---

## 7. 同步服务实现示例

### 7.1 Python（Flask）示例

```python
import time, json, requests
from flask import Flask, request
from functools import lru_cache

app = Flask(__name__)

CORPID = "your_corpid"
CORPSECRET = "your_corpsecret"
TOKEN_CACHE = {"token": None, "expire": 0}

# ---- 简单 token 缓存（生产建议用 Redis）----
def get_token():
    if TOKEN_CACHE["token"] and TOKEN_CACHE["expire"] > time.time():
        return TOKEN_CACHE["token"]
    r = requests.get("https://qyapi.weixin.qq.com/cgi-bin/gettoken",
                     params={"corpid": CORPID, "corpsecret": CORPSECRET}).json()
    TOKEN_CACHE["token"] = r["access_token"]
    TOKEN_CACHE["expire"] = time.time() + r["expires_in"] - 200
    return r["access_token"]

def call(api, payload):
    return requests.post(f"https://qyapi.weixin.qq.com/cgi-bin/{api}",
                         params={"access_token": get_token()},
                         json=payload).json()

# 按会议ID缓存日历，避免重复建日历
CAL_CACHE = {}
def get_calendar(meeting_id, attendees):
    if meeting_id in CAL_CACHE:
        return CAL_CACHE[meeting_id]
    r = call("oa/calendar/add", {
        "title": f"E10会议-{meeting_id}",
        "shares": [{"userid": u, "readonly": 0} for u in attendees]
    })
    CAL_CACHE[meeting_id] = r["cal_id"]
    return r["cal_id"]

@app.route("/syncMeeting", methods=["POST"])
def sync_meeting():
    data = request.json
    attendees = [e10ToWeCom(a) for a in data["attendees"]]   # E10账号→userid
    cal_id = get_calendar(data["meeting_id"], attendees)
    r = call("oa/schedule/add", {
        "schedule": {
            "calendar_id": cal_id,
            "title": data["title"],
            "description": data.get("description", ""),
            "start_time": int(data["start_time"]),   # 秒级时间戳
            "end_time": int(data["end_time"]),
            "location": data.get("location", ""),
            "attendees": [{"userid": u, "response_status": 3} for u in attendees],
            "reminders": [{"is_remind": 1, "remind_before_event_secs": 3600},
                          {"is_remind": 1, "remind_before_event_secs": 600}]
        }
    })
    # 将 r["schedule_id"] 回写 E10 会议记录（此处略）
    return json.dumps(r, ensure_ascii=False)

# 取消 / 改期
@app.route("/cancelMeeting", methods=["POST"])
def cancel_meeting():
    return json.dumps(call("oa/schedule/del", {"schedule_id": request.json["schedule_id"]}))

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
```

### 7.2 Java 示例（核心方法）

```java
public class WeComScheduleClient {
    private static final String BASE = "https://qyapi.weixin.qq.com/cgi-bin/";
    private final String corpid, corpsecret;
    private String token; private long expireAt;

    private String getToken() {
        if (token != null && System.currentTimeMillis() < expireAt) return token;
        String url = BASE + "gettoken?corpid=" + corpid + "&corpsecret=" + corpsecret;
        JSONObject r = HttpUtil.get(url);
        token = r.getString("access_token");
        expireAt = System.currentTimeMillis() + (r.getIntValue("expires_in") - 200) * 1000L;
        return token;
    }

    public String addSchedule(String calId, Meeting m) {
        JSONObject sch = new JSONObject()
            .fluentPut("calendar_id", calId)
            .fluentPut("title", m.getTitle())
            .fluentPut("start_time", m.getStartSeconds())
            .fluentPut("end_time", m.getEndSeconds())
            .fluentPut("location", m.getLocation())
            .fluentPut("attendees", m.getAttendeeUsers())        // List<{userid,response_status:3}>
            .fluentPut("reminders", Arrays.asList(
                new JSONObject().fluentPut("is_remind", 1).fluentPut("remind_before_event_secs", 3600),
                new JSONObject().fluentPut("is_remind", 1).fluentPut("remind_before_event_secs", 600)));
        JSONObject r = HttpUtil.postJson(BASE + "oa/schedule/add?access_token=" + getToken(),
                                         new JSONObject().fluentPut("schedule", sch));
        return r.getString("schedule_id");   // 回存
    }
}
```

---

## 8. 边界与异常处理

- **token 缓存**：所有请求共用缓存，过期自动刷新，禁止每次请求都换 token。
- **幂等**：同一会议重复触发时，按 `meeting_id` 复用 `cal_id` 与 `schedule_id`，避免重复建日程。
- **改期 / 取消**：E10 会议变更时调用 `oa/schedule/update` / `oa/schedule/del`（需回存 `schedule_id`）。
- **时区**：`start_time` / `end_time` 为秒级时间戳，按 Asia/Shanghai 计算。
- **权限**：日历成员若超出应用通讯录可见范围会失败，需确认应用可见范围覆盖全部参会人。
- **会议室冲突**：企业微信日程本身不做会议室预订，冲突校验仍由 E10 会议模块负责。
- **告警**：userid 映射缺失、API 调用失败应记录日志并告警，不阻断其他参会人同步。

---

## 9. 部署与配置清单

- [ ] 企业微信自建应用已创建，记录 `corpid` / `corpsecret`
- [ ] 应用已勾选「OA 日程」权限，通讯录可见范围覆盖参会人
- [ ] E10 ↔ 企微 userid 映射已就绪
- [ ] 同步服务部署并可通过 E10 访问（建议 `47.106.103.169` 内网可达）
- [ ] access_token 缓存（Redis：`47.106.103.169:20100`）已配置
- [ ] E10 会议模块已挂接触发动作（接口动作 / 自定义 Action）
- [ ] `schedule_id` 回写机制已验证
- [ ] 取消 / 改期链路已验证

---

## 10. 常见问题（FAQ）

**Q1：企业微信会主动推送提醒吗？**
会。日程创建后，企业微信会在 `reminders` 设定的提前量自动提醒所有日历成员（应用内 + 手机系统通知）。

**Q2：参会人没收到提醒？**
检查：① 应用是否有 OA 日程权限；② 参会人是否在日历 `shares` 中；③ `reminders.is_remind=1`；④ 应用通讯录可见范围是否覆盖该人。

**Q3：E10 会议模块不是表单建模怎么办？**
用第 5.2 节自定义 Action 或 5.3 节定时扫描兜底。

**Q4：一个会议要建多个日历吗？**
不必。建议按会议 `meeting_id` 复用同一共享日历（缓存 `cal_id`），所有参会人挂在同一个日历下。

---

*文档生成说明：企业微信接口基于官方公开 API；E10 表单 / 表名 / 动作路径需结合你方实际环境（测试服务器 `https://oa.jiaweiyi.com`）核对。*
