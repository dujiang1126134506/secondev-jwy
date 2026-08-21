package com.weaver.seconddev.wecom.service.impl;

import com.weaver.allinone.boot.context.AllinoneSpringContext;
import com.weaver.common.base.entity.result.WeaResult;
import com.weaver.common.hrm.remote.HrmRemoteOpenEmployeeService;
import com.weaver.seconddev.wecom.client.WeComScheduleClient;
import com.weaver.seconddev.wecom.config.WeComConfig;
import com.weaver.seconddev.wecom.service.UserIdMapper;
import com.weaver.verupgrade.conn.RecordSet;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 手机号绑定模式下的 E10 账号 → 企业微信 userid 映射器。
 *
 * <p>适用于 E10 与企微按手机号自动绑定的场景。映射链路：</p>
 * <ol>
 *   <li>输入 E10 登录名（会议模块传入的参会人标识，对应 E10 人员表登录列）</li>
 *   <li>查询手机号（两级优先）：
 *     <ul>
 *       <li>① 优先调用 HRM 平台服务 <code>HrmRemoteOpenEmployeeService.findByAccountAndkey</code>
 *           （按账号+租户键查员工，返回 <code>WeaResult</code>，data 为运行期类型，反射读 mobile）</li>
 *       <li>② 服务不可用/未命中时，回退 <code>com.weaver.verupgrade.conn.RecordSet</code> 查询
 *           E10 原生人员主表 <code>eteams.employee</code> 的 <code>mobile</code></li>
 *     </ul>
 *   </li>
 *   <li>调用企微 <code>user/get_by_mobile</code> 换取企微 userid</li>
 * </ol>
 *
 * <p>回退 SQL 可通过 <code>wecom.userid.phone.sql</code> 配置，默认
 * <code>SELECT mobile FROM eteams.employee WHERE username = ? AND TENANT_KEY = ? AND delete_type = 0 AND STATUS = 'normal' AND TYPE = 'inside'</code>，
 * 两个 ? 顺序为登录名、租户键（由 <code>wecom.tenant.key</code> 提供）。</p>
 *
 * @author DuJiang
 */
@Slf4j
public class PhoneUserIdMapper implements UserIdMapper {

    private final WeComConfig config;
    private final WeComScheduleClient client;

    public PhoneUserIdMapper(WeComConfig config, WeComScheduleClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public String map(String e10Account) {
        if (e10Account == null || e10Account.trim().isEmpty()) {
            log.warn("[PhoneUserIdMapper] map 入参 E10 账号为空，返回 null");
            return null;
        }
        String account = e10Account.trim();
        log.info("[PhoneUserIdMapper] map 入口, account={}", account);
        String mobile = queryMobile(account);
        if (mobile == null || mobile.trim().isEmpty()) {
            log.warn("[PhoneUserIdMapper] 未查询到 E10 账号[{}] 的手机号，跳过", account);
            return null;
        }
        String userid = client.getUserIdByMobile(mobile.trim());
        if (userid == null || userid.trim().isEmpty()) {
            log.warn("[PhoneUserIdMapper] 手机号[{}] 未匹配到企业微信 userid，跳过", mobile);
        }
        log.info("[PhoneUserIdMapper] map 出口, account={}, mobile={}, userid={}", account, mobile, userid);
        return userid;
    }

    /**
     * 查询手机号（两级优先：HRM 服务 → SQL 回退）。
     *
     * @param e10Account E10 登录名
     * @return 手机号；均失败返回 null
     * @author DuJiang
     */
    private String queryMobile(String e10Account) {
        // ① 优先使用 HRM 平台服务（用户要求优先使用 secDevLib 提供的工具/服务能力）
        String mobile = queryMobileByHrmService(e10Account);
        if (mobile != null && !mobile.trim().isEmpty()) {
            log.info("[PhoneUserIdMapper] HRM 服务命中手机号, account={}, mobile={}", e10Account, mobile);
            return mobile.trim();
        }
        // ② 服务不可用/未命中时回退 SQL 查询
        String sqlMobile = queryMobileBySql(e10Account);
        if (sqlMobile != null && !sqlMobile.trim().isEmpty()) {
            log.info("[PhoneUserIdMapper] SQL 回退命中手机号, account={}, mobile={}", e10Account, sqlMobile);
        }
        return sqlMobile;
    }

    /**
     * 通过 HRM 平台服务 <code>HrmRemoteOpenEmployeeService</code> 查询手机号。
     *
     * <p>服务返回的 <code>WeaResult.data</code> 为平台运行期类型（如 SimpleOpenEmployee），
     * 本地编译期不可见，因此用反射读取 mobile。服务未注册或调用异常时返回 null（交由 SQL 回退）。</p>
     *
     * @param e10Account E10 登录名
     * @return 手机号；服务不可用或未命中返回 null
     * @author DuJiang
     */
    private String queryMobileByHrmService(String e10Account) {
        String tenantKey = config.getTenantKey();
        if (tenantKey == null || tenantKey.trim().isEmpty()) {
            log.warn("[PhoneUserIdMapper] wecom.tenant.key 未配置，跳过 HRM 服务查询");
            return null;
        }
        try {
            HrmRemoteOpenEmployeeService svc = AllinoneSpringContext.getBean(HrmRemoteOpenEmployeeService.class);
            log.info("[PhoneUserIdMapper] HRM 服务获取成功, 开始按账号查询, account={}, tenantKey={}", e10Account, tenantKey);
            WeaResult<?> result = svc.findByAccountAndkey(e10Account, tenantKey);
            if (result == null || result.getData() == null) {
                log.warn("[PhoneUserIdMapper] HRM 服务未查到账号[{}] 用户: code={}, msg={}",
                        e10Account, result == null ? null : result.getCode(), result == null ? null : result.getMsg());
                return null;
            }
            String mobile = readMobileFromData(result.getData());
            log.info("[PhoneUserIdMapper] HRM 服务查询结果, account={}, dataType={}, mobile={}",
                    e10Account, result.getData().getClass().getName(), mobile);
            return mobile;
        } catch (Exception e) {
            log.warn("[PhoneUserIdMapper] HRM 服务查询手机号异常({}): {}", e10Account, e.getMessage());
            return null;
        }
    }

    /**
     * 从服务返回的数据对象中读取手机号（兼容 Map 与反射 getMobile）。
     *
     * @param data 服务返回的 data 对象
     * @return 手机号；读取失败返回 null
     * @author DuJiang
     */
    private String readMobileFromData(Object data) {
        try {
            if (data instanceof Map) {
                Object v = ((Map<?, ?>) data).get("mobile");
                if (v != null) {
                    return v.toString();
                }
            }
            Method m = data.getClass().getMethod("getMobile");
            Object v = m.invoke(data);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            log.warn("[PhoneUserIdMapper] 读取服务返回数据 mobile 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 使用 E10 RecordSet 查询手机号（SQL 回退方案）。
     *
     * <p>SQL 中的 ? 占位符按出现顺序填充：登录名、租户键。
     * 默认 SQL 查询 E10 原生人员主表 {@code eteams.employee}（E10 多租户模型），
     * 对应 E9 的 {@code HrmResource.loginid} 写法已被废弃。</p>
     *
     * @param e10Account E10 登录名
     * @return 手机号；查询失败或未配置租户键返回 null
     * @author DuJiang
     */
    private String queryMobileBySql(String e10Account) {
        String sql = config.getUseridPhoneSql();
        if (sql == null || sql.trim().isEmpty()) {
            log.warn("[PhoneUserIdMapper] 未配置 wecom.userid.phone.sql，无法查询手机号");
            return null;
        }
        // 统计占位符数量，按序填充（登录名、租户键）
        int placeholderCount = countPlaceholder(sql);
        if (placeholderCount < 1) {
            log.warn("[PhoneUserIdMapper] SQL 必须包含占位符 ?，当前: {}", sql);
            return null;
        }
        String tenantKey = config.getTenantKey();
        if (placeholderCount >= 2 && (tenantKey == null || tenantKey.trim().isEmpty())) {
            log.warn("[PhoneUserIdMapper] SQL 含多占位符但 wecom.tenant.key 未配置，无法查询 eteams.employee（多租户需 TENANT_KEY）");
            return null;
        }
        try {
            String safeAccount = sanitize(e10Account);
            if (safeAccount.isEmpty()) {
                log.warn("[PhoneUserIdMapper] E10 账号包含非法字符，无法拼接 SQL");
                return null;
            }
            String[] values;
            if (placeholderCount >= 2) {
                values = new String[]{safeAccount, sanitize(tenantKey)};
            } else {
                values = new String[]{safeAccount};
            }
            String realSql = fillSql(sql, values);
            log.info("[PhoneUserIdMapper] SQL 回退查询手机号, account={}, sql={}", e10Account, realSql);
            RecordSet rs = new RecordSet();
            if (rs.executeSql(realSql) && rs.next()) {
                String mobile = rs.getString("mobile");
                log.info("[PhoneUserIdMapper] SQL 回退查询命中, account={}, mobile={}", e10Account, mobile);
                return mobile;
            }
            log.warn("[PhoneUserIdMapper] SQL 回退查询未命中, account={}", e10Account);
        } catch (Exception e) {
            log.error("[PhoneUserIdMapper] 查询手机号异常({}): {}", e10Account, e.getMessage());
        }
        return null;
    }

    /** 统计 SQL 中未转义的 ? 占位符数量 */
    private int countPlaceholder(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    /** 按序填充占位符：仅允许字母数字及常用符号，统一加单引号包裹，避免拼接注入 */
    private String fillSql(String sql, String[] values) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && idx < values.length) {
                sb.append('\'').append(values[idx++]).append('\'');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 防注入：仅保留字母数字、空格及常用符号（E10 登录名 username 可能含空格，如 Elaine Guo） */
    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^a-zA-Z0-9 _.@-]", "");
    }
}
