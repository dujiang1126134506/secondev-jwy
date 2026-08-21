# secondev-jwy — E10 集成企业微信日程（会议提醒）

泛微 E10 会议模块（本地化部署 OA）+ 企业微信「OA 日程」能力集成：
**会议创建 / 审批通过后，自动在企业微信为参会人创建日程，并按设定提前提醒，避免漏会。**

> 本模块为 E10 **后端二开**项目，遵循泛微 E10 后端二次开发规范（采用 Spring Controller 方式，**不使用** serverless/云函数方式）。
> 构建产物为标准二开 jar + `build.zip`，上传到 secondev 服务后由平台 Spring 容器托管并自动暴露 HTTP 接口。
> 实现依据：`E10集成企业微信日程API说明文档.md`（随项目文档库提供）。

---

## 1. 总体架构

```
E10 会议模块（创建 / 审批通过）
        │ 触发（E10 会议模块「接口动作 / 自定义动作」配置 HTTP 调用）
        ▼
secondev 服务（Spring 容器托管本模块 jar 内的 Controller，自动暴露 /api/secondev/wecom/*）
   └─ MeetingWeComController（后端 Controller，返回 WeaResult<T>）
        │  POST /api/secondev/wecom/syncMeeting  {meetingId, title, start/endTime, location, attendees}
        ▼
MeetingSyncService（同步编排）
   ├─ ① 映射 E10→企微 userid
   ├─ ② 换取 access_token（缓存 7200s，提前 200s 刷新）
   ├─ ③ 创建 / 复用共享日历（按 meetingId 缓存 cal_id）
   └─ ④ oa/schedule/add 创建日程（幂等，重复触发走 update）
        ▼
企业微信日程（自动提醒参会人）
```

> ⚠️ 本模块**不**作为独立服务部署，打包后的 jar 直接上传到泛微 E10 的 **secondev 服务**（weaver-secondev-service），
> 由平台 Spring 容器扫描 `com.weaver.seconddev` 包并托管其中的 `@RestController`，无需自建 HttpServer / 独立进程。

## 2. 模块结构

```
src/main/java/com/weaver/seconddev/wecom/
├── config/WeComConfig.java            # 配置加载（classpath + 外部文件覆盖）
├── model/MeetingInfo.java             # 会议信息 DTO（秒级时间戳）
├── model/WeComResult.java             # 企业微信 API 统一响应
├── client/WeComScheduleClient.java    # 企业微信 OA 日程 API 客户端
├── service/MeetingSyncService.java    # 同步编排（映射/幂等/日历复用/CRUD）
├── service/UserIdMapper.java          # E10账号→企微userid 映射接口
├── service/impl/DirectUserIdMapper.java  # 直通映射（E10登录名=企微userid）
├── service/impl/PhoneUserIdMapper.java   # 手机号绑定映射（E10登录名→HrmResource.mobile→企微userid）
├── service/impl/MobileUserIdMapper.java  # 手机号直传映射（手机号→企微userid）
├── controller/MeetingWeComController.java  # 后端 Controller 入口（Spring，/api/secondev/wecom/*，返回 WeaResult）
└── util/HttpClientUtil.java           # HTTP 客户端封装（httpclient 4.5）

src/main/resources/wecom/weaver-secondev-wecom.properties  # 本地配置模板（构建时排除）
src/main/java/com/weaver/custom/configcenter/
└── SecondevWeComConfigCenter.java                 # 注册 E10 自定义配置文件
```

## 3. 配置说明

运行时配置文件固定命名为 `weaver-secondev-wecom.properties`。仓库中的同名
`src/main/resources/wecom/weaver-secondev-wecom.properties` 仅作为本地字段模板，构建时会被排除。

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `wecom.corpid` | 企业微信自建应用 corpid | 无，必须配置 |
| `wecom.corpsecret` | 企业微信自建应用 corpsecret | 无，必须配置 |
| `wecom.token.safe.secs` | token 提前刷新秒数 | 200 |
| `wecom.api.base` | 企微 API 基础域名 | https://qyapi.weixin.qq.com/cgi-bin |
| `wecom.calendar.title` | 共享日历标题模板，`{meetingId}` 替换 | `E10会议-{meetingId}` |
| `wecom.remind.before.secs` | 提醒提前量（秒，逗号分隔） | `3600,600` |
| `wecom.userid.mode` | 映射模式：`direct` 透传 / `phone` E10用户ID→手机号→企微userid / `mobile` 手机号直传 | direct |

**运行期配置（resources 已排除，必须使用 E10 配置中心）**：

1. 单体环境：将 `weaver-secondev-wecom.properties` 放到二开服务的
   `webapps/ROOT/WEB-INF/classes/weaver/config/config-center/` 目录，修改后重启服务。
2. 微服务组合环境：在 Nacos 的 `DEFAULT_GROUP` 下新增 Data ID
   `weaver-secondev-wecom.properties`。
3. 二开 Jar 内的 `SecondevWeComConfigCenter` 已注册该 Data ID，
   `WeComConfig` 通过 Spring `@Value` 获取属性。

未提供 `wecom.corpid` 或 `wecom.corpsecret` 时，启动日志会输出明确告警。

> ⚠️ `corpid` / `corpsecret` 为敏感凭证，请在实际部署时替换为企业微信后台实际值，勿外泄。

## 4. 部署与触发方式

### 步骤一：构建并上传

执行 `./gradlew :secondev-jwy:build`，将生成的 `build.zip`（内含 `secondev-jwy.jar`，已带
`META-INF/res.list` / `src.list` 与 `weaver-ecode-seconddev-id` 清单）上传到泛微 E10 的
**secondev 服务**（weaver-secondev-service）。平台 Spring 容器会扫描 `com.weaver.seconddev` 包，
自动注册 `MeetingWeComController` 并暴露 `/api/secondev/wecom/*` 接口，无需启动独立进程。

> ⚠️ **严禁手动修改服务器上的 `applicationContext-secondev-dubbo.xml`**（规范 2.5）。该文件升级会被覆盖，
> 会导致二开功能失效。配置管理由主团队管理员访问
> `/ecode/monitor/config/manage`；Jar 版本管理入口为 `/ecode/monitor/loom/jar/versionList`。

### 步骤二：E10 会议模块挂接触发

在 E10「表单建模 → 会议表单 → 动作设置」新增 **接口动作（RESTful）**，触发时机选「提交后 / 审批通过后」，
动作地址填本模块 Controller 暴露的 HTTP 接口（POST，Content-Type: application/json）：

| 场景 | 调用地址 |
| --- | --- |
| 创建 / 更新日程 | `POST /api/secondev/wecom/syncMeeting` |
| 变更日程（改期 / 调参会人） | `POST /api/secondev/wecom/updateMeeting` |
| 取消日程 | `POST /api/secondev/wecom/cancelMeeting` |
| 健康检查 | `GET /api/secondev/wecom/health` |

请求体（JSON）：
```json
{
  "meetingId": "会议ID",
  "title": "会议标题",
  "description": "议题",
  "startTime": 1787000000,
  "endTime": 1787003600,
  "location": "3楼大会议室",
  "attendees": ["zhangsan", "lisi"]
}
```

> 若需供外部系统调用，须将接口声明为 `/sapi/secondev/wecom/*` 并通过开放平台认证（规范 2.2），严禁声明 `/papi` 接口。

### 兜底方案：定时增量扫描（可选扩展）

如无法改表单动作，可另写一个后端 Controller 或 Spring 定时任务（`@Scheduled`），按 `create_time` 增量扫描
`ecology10` 库会议表（表名依版本核实，一般为 `Meeting` 系列表），将记录组装为 `MeetingInfo` 后调用
`MeetingSyncService.syncMeeting`。本模块未内置该扫描任务（依赖实际表结构），需按环境补充。

## 5. 后端接口（WeaResult<T>）

所有接口返回类型均为 `com.weaver.common.base.entity.result.WeaResult<T>`，`data` 字段携带业务明细：

| 接口 | 方法 | 入参 JSON 关键字段 | 说明 |
| --- | --- | --- | --- |
| `/api/secondev/wecom/syncMeeting` | POST | meetingId, title, startTime, endTime, attendees(可选), location(可选), description(可选) | 创建或更新会议日程（幂等：同一会议重复触发自动走 update） |
| `/api/secondev/wecom/updateMeeting` | POST | 同 syncMeeting | 变更日程（改期 / 调整参会人），等价幂等更新 |
| `/api/secondev/wecom/cancelMeeting` | POST | meetingId | 取消会议日程，按 meetingId 删除对应日程 |
| `/api/secondev/wecom/health` | GET | 无 | 健康检查 |

成功响应示例：
```json
{
  "code": 0,
  "status": true,
  "msg": "ok",
  "data": {
    "success": true,
    "errcode": 0,
    "errmsg": "ok",
    "scheduleId": "schedule17937391935028528580",
    "calId": "wcjesse95we10saj919iapkjsadf"
  }
}
```

失败响应：`status=false`，`code` 为非零错误码，`msg` 为错误描述。

## 6. 关键实现说明

- **token 缓存**：`WeComScheduleClient` 内存缓存 access_token，过期前自动刷新；并发下双检锁保证只刷新一次。
  多实例部署建议改用 Redis 统一缓存（环境 Redis：`47.106.103.169:20100`），扩展点见 `getToken()`。
- **幂等**：`MeetingSyncService` 按 `meetingId` 缓存 `cal_id` 与 `schedule_id`，重复触发复用，避免重复建日程。
- **userid 映射**：默认 `phone` 模式，先通过反射调用 E10 `weaver.conn.RecordSet` 按配置 SQL 查手机号，
  再调用企微 `user/get_by_mobile` 换 userid；如 E10 动作已能直接传手机号，可改 `wecom.userid.mode=mobile`。
  映射缺失者记录告警并跳过，不阻断整体同步。
- **schedule_id 回写**：`MeetingSyncService.setScheduleIdBackWriter(...)` 提供回写钩子，
  可在回调中按实际会议表结构执行 update SQL，供改期 / 取消使用。
- **错误码处理**：遇 40014 / 41001（token 失效）自动刷新重试一次；45009（超限）记录告警建议限流退避。
- **时区**：`start_time` / `end_time` 为秒级 Unix 时间戳，按 Asia/Shanghai 计算。

## 7. 部署清单检查

- [ ] 企业微信自建应用已创建，`corpid` / `corpsecret` 已在配置中替换为实际值
- [ ] 应用已勾选「企业微信日程（OA）」接口权限，以及「通讯录」读取权限（phone/mobile 模式需调用 user/get_by_mobile）
- [ ] E10 ↔ 企微 userid 映射已确认（`phone` 手机号绑定 / `mobile` 手机号直传 / `direct` 登录名直传）
- [ ] 已通过 `gradle :secondev-jwy:build` 打包，并将 `build.zip` 上传到 secondev 服务（无需独立进程）
- [ ] E10 会议模块已挂接触发动作（接口动作，POST /api/secondev/wecom/*）
- [ ] `schedule_id` 回写机制已验证（改期 / 取消链路）
- [ ] 已用 `gradle :secondev-jwy:build` 打包并在测试环境验证

## 8. 构建

```bash
cd d:/secondev/secondev-demo
./gradlew :secondev-jwy:build
# 产物：secondev-jwy/build/libs/secondev-jwy.jar 与 build.zip
```
