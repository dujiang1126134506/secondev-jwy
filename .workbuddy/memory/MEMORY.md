# 项目长期记忆（secondev-jwy / E10 后端二开）

## E10 后端二次开发规范（必须遵守）

来源：泛微 E10 后端二开规范文档（https://weapp.eteams.cn/ecode/playground/doc/share/view/974701716410515476 第二章）。
**用户明确要求：不使用云函数（serverless/func）方式，统一用「后端 Controller」方式二开。**

### 1. 包结构（规范 2.1）
- 所有二开代码必须放在 `com.weaver.seconddev`，可按模块再加一层包名。
- 本项目根包：`com.weaver.seconddev.wecom`（controller / service / client / config / model / util）。

### 2. HTTP 接口路由（规范 2.2）
- `/api`：需登录才能访问
- `/sapi`：内部服务间通信，无法通过 e10 地址访问，外部系统调用须走 `/sapi` + 开放平台认证
- 路由格式：`/(s)api/secondev/xxx/xxx`（移动端加 `/app`，后台加 `/bs`）
- **严禁随意声明 `/papi` 接口**
- 本项目接口：`/api/secondev/wecom/syncMeeting`、`/updateMeeting`、`/cancelMeeting`、`/health`

### 3. Controller 响应参数（规范 2.3）
- 返回类型必须为 `com.weaver.common.base.entity.result.WeaResult<T>`
- 成功：`WeaResult.success(data)`；失败：`WeaResult.fail(int code, String msg)` / `WeaResult.fail(String msg)`
- 用 `@RestController` + `@RequestMapping` + `@PostMapping`/`@GetMapping` + `@RequestBody` 等 Spring 注解。

### 4. 数据库操作（规范 2.4）
- 自定义 SQL **必须**通过数据源接口（如 `com.weaver.verupgrade.conn.RecordSet` 反射或 e-code 数据源 API），**禁止 Mybatis**。
- E10 为多库架构：原生人员主表在 **eteams 库**（`eteams.employee`），`RecordSet` 默认连接 ecology10 库，
  跨库查询 SQL 必须带库前缀（如 `SELECT ... FROM eteams.employee ...`）。
- E10 原生人员主表 = `eteams.employee`：登录列 `username`（唯一、有值）、手机号 `mobile`、租户列 `TENANT_KEY`、
  在职 `STATUS='normal'`、内部账号 `TYPE='inside'`、未删除 `delete_type=0`、工号 `JOB_NUM`（大多为空，勿作匹配列）。
- **不要**使用 E9 遗留写法 `HrmResource.loginid`；云桥表 `eb_hrm_user`（org_login_id/mobile/tenant_key）为
  云桥企微同步表，本环境 0 行，用户主数据在 `eteams.employee`。

### 5. XML 配置（规范 2.5）
- **严禁手动修改**服务器上的 `applicationContext-secondev-dubbo.xml`（升级会被覆盖）。
- 配置统一在 e-code 监控管理平台 `/ecode/monitor/loom/deploy/jar` 的配置文件编辑入口修改。

### 6. 二开 jar 包（规范 2.6）
- 命名统一以 `secondev-` 前缀（`settings.gradle` 中 `rootProject.name = 'secondev-jwy'`）。
- 打包需排除 resources 目录所有文件（`sourceSets.main.resources.exclude '**'`，已在 secondev-jwy.gradle 配置），
  二开配置通过外部文件提供（`WeComConfig` 支持 `-Dwecom.config.path` 或 `${user.home}/wecom-config.properties`）。

### 7. 部署（规范 2.7）
- 统一部署到 secondev 服务（weaver-secondev-service），不上传其他标准服务。
- 交付物为构建生成的 `build/libs/build.zip`（内含带 `META-INF/res.list`/`src.list` 与 `weaver-ecode-seconddev-id` 清单的 jar）。

### 8. 日志规范
- 所有类日志统一用 Lombok `@Slf4j` + `log` 对象打印，禁止 `System.out/err.println`。
- **开发阶段全链路 info 日志（用户要求）**：每个接口入口/出口（含耗时）、关键操作点（日历创建/复用、日程增改删、
  参会人映射、token 获取、HTTP 调用出入口）均打 info 日志，便于排查问题。
- **敏感信息脱敏**：不打印 `corpsecret`、`access_token` 值；URL 用 `maskUrl`（保留 scheme+host+path，隐藏 query，含 token）。

### 9. 工具类使用规范（用户明确要求）
- **尽量使用 Util 工具类**：通用逻辑（空安全取值、类型转换、字符串/集合处理、UUID、编码等）优先调用
  `com.weaver.seconddev.wecom.util.Util` 等已存在的 Util 类，禁止在业务类里重复造轮子（如自行写 `parseInt`、空判断等）。
- 新增通用能力时，优先沉淀到对应 `util/` 包下的工具类，而非散落在 controller/service 内。
- 例：配置解析的整数转换应调用 `Util.getIntValue(Object, int)`，不应自行实现私有 `parseInt`。
- **用户相关操作一律优先使用 `com.weaver.ebuilder.common.util.UserContext`**：涉及当前登录用户、用户身份/信息、
  用户上下文取值的场景（获取当前用户、判断登录态、取用户属性等），统一走该类工具方法，禁止在业务代码里自行
  从 request/session/ThreadLocal 中硬编码读取或重复封装。此约定适用于本项目及后续所有 E10 二开项目。

### 10. 平台工具类/服务（用户指定优先使用，来源 secDevLib 两个 jar）
**用户要求：后续生成代码可优先使用以下 jar 中的类。**

#### 10.1 `secDevLib/weaver-ebuilder-common-sdk-2.110.0.RELEASE.jar`
| 类 | 关键方法 | 用途 |
|---|---|---|
| `com.weaver.ebuilder.common.util.UserContext` | `getUser()`→UserWrapper（当前用户）、`getUser(Long)`、`currUser()`→SimpleEmployee、`getUserId()`→Long、`getLoginAccount`、`isSysAdmin()`、`isEbuilderAdmin()`、`getSubordinate()`/`getAllSubordinate()` | 当前登录用户/上下文、用户属性、权限判断、下属/部门范围 |
| `com.weaver.ebuilder.common.vo.UserWrapper` | `getId()`、`getLoginAccount()`、`getMobile()`、`getWorkCode()`、`getLastName()`、`getDepartmentId()`、`getSubCompanyId()`、`isAdmin()` | 用户包装 VO（UserContext 返回），可直接取手机号/登录名/工号 |
| `com.weaver.ebuilder.common.util.TenantContext` | `getCurrentTenantKey()`、`getCurrentTenant()` | 当前租户键（多租户过滤） |
| `com.weaver.ebuilder.common.util.EbAssert` | 继承 Spring `Assert` | 参数/状态断言 |
| `com.weaver.ebuilder.common.util.RestTemplateUtil` | HTTP 调用封装 | 便捷 RestTemplate |
| `com.weaver.ebuilder.common.util.DatabaseUtil` | 数据库访问封装 | 数据访问辅助 |
| `com.weaver.ebuilder.common.exception.BusinessException` | 构造业务异常 | 业务异常抛出 |
| `com.weaver.ebuilder.common.util.EbuilderCommonUtil` | `getDomain()`/`getBackUrl()` 等 | 通用工具 |

#### 10.2 `secDevLib/weaver-common-hrm-3.21.0.RELEASE.hotfix1.jar`
| 类 | 关键方法 | 用途 |
|---|---|---|
| `com.weaver.teams.domain.service.employee.RemoteOpenEmployeeService` | `getByWechatUserId(wechatUserId, tenantKey)`、`findByAccountAndkey(account, key)`、`findUserByCondition(...)`、`findAllUsersByTenantKey(key, type)`、`get(Long)` | **E10 用户/员工查询核心服务**：按企微 userid 反查、按账号查、按租户查员工 |
| `com.weaver.common.hrm.service.employee.HrmEmployeeResourceService` | `pageQueryByCondition(page, ...)` | 员工分页/条件查询（MyBatis-Plus Page） |
| `com.weaver.common.hrm.service.employee.HrmEmployeeExtensionService` | 员工扩展信息 | 人员扩展属性 |
| `com.weaver.common.hrm.service.relation.HrmCommonUserRelationService` | 用户关系 | 上下级/同事关系 |

#### 10.3 用法示例（后续代码优先采用）
```java
// 获取当前登录用户（替代自行从 request/session 读取）
UserWrapper user = UserContext.getUser();
Long userId = UserContext.getUserId();
String mobile = user.getMobile();
String loginAccount = user.getLoginAccount();

// 当前租户键（多租户过滤）
String tenantKey = TenantContext.getCurrentTenantKey();

// 按账号查员工（替代手写 SELECT mobile FROM eteams.employee ...）
// 服务 bean 统一用 AllinoneSpringContext.getBean(接口.class) 获取（平台标准入口）
import com.weaver.allinone.boot.context.AllinoneSpringContext;
import com.weaver.common.hrm.remote.HrmRemoteOpenEmployeeService;
HrmRemoteOpenEmployeeService svc = AllinoneSpringContext.getBean(HrmRemoteOpenEmployeeService.class);
WeaResult<?> result = svc.findByAccountAndkey(account, tenantKey);
Object data = result.getData();          // 运行期类型（SimpleOpenEmployee），编译期不可见，需反射读字段
```

> 注：`SimpleEmployee`/`SimpleOpenEmployee`/`SimpleUser` 等 teams 域类未在 secDevLib 直出，由平台运行期提供，编译期引用即可（同 `RecordSet`）。读取其字段需反射（如 `getMethod("getMobile").invoke(data)`），或转 `Map` 取 `mobile`。
>
> 服务获取规范：`com.weaver.allinone.boot.context.AllinoneSpringContext.getBean(Class)`（已在 secDevClasses）。
>
> 已落地：`PhoneUserIdMapper` 查询手机号改为「① HRM 服务 `HrmRemoteOpenEmployeeService.findByAccountAndkey`（反射读 mobile）→ ② 失败回退 SQL 查 `eteams.employee`」。

## 集成企业微信日程实现要点（本模块）
- 入口：`controller/MeetingWeComController`（Spring 后端方式，替代原云函数 `MeetingToWeComAction`）。
- 同步编排：`service/MeetingSyncService`（按 meetingId 进程内缓存 cal_id/schedule_id 实现幂等与共享日历复用）。
- 企微 API：`client/WeComScheduleClient`（gettoken 缓存 7200s、遇 40014 自动刷新重试）。
- userid 映射：默认 `phone` 模式（E10 与企微按手机号绑定，查 `eteams.employee.username→mobile` → user/get_by_mobile）；
  另有 `direct` / `mobile` 模式，接口为 `service/UserIdMapper`。
- 部署前替换 `corpid`/`corpsecret` 为企微后台实际值；应用需开通「通讯录」读权限 + 「企业微信日程（OA）」权限。

## 环境约定
- 所有 `127.0.0.1` 指测试服务器 `192.168.10.240`。
- 测试服务器 IP：`192.168.10.240`（MySQL `192.168.10.240:3382`，库 `ecology10`/`eteams`）。
- secDevClasses / secDevLib 为 E10 运行期类与 jar（平台自带，编译期 `implementation` 引用，不打进二开 jar）。
- 构建：`./gradlew :secondev-jwy:build`；产物 `secondev-jwy/build/libs/build.zip`。
