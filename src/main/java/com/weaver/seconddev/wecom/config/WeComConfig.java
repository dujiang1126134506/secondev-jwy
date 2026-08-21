package com.weaver.seconddev.wecom.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import com.weaver.seconddev.wecom.util.Util;
import lombok.extern.slf4j.Slf4j;

/**
 * 企业微信集成配置。
 *
 * <p>配置加载优先级：</p>
 * <ol>
 *   <li>外部绝对路径配置文件（系统属性 <code>wecom.config.path</code> 指定，
 *       或默认 <code>${user.home}/wecom/wecom-config.properties</code>，便于运维按环境覆盖）</li>
 *   <li>classpath 下的 <code>wecom/wecom-config.properties</code></li>
 * </ol>
 *
 * @author DuJiang
 */
@Slf4j
public class WeComConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** classpath 内默认配置文件（带包路径，避免多 jar 同名资源冲突） */
    private static final String DEFAULT_CONFIG_PATH = "wecom/wecom-config.properties";
    /** 外部配置路径的系统属性名 */
    private static final String CONFIG_PATH_PROP = "wecom.config.path";

    /** corpid */
    private String corpid;
    /** corpsecret */
    private String corpsecret;
    /** token 提前刷新秒数 */
    private int tokenSafeSecs = 200;
    /** 企业微信 API 基础域名 */
    private String apiBase = "https://qyapi.weixin.qq.com/cgi-bin";
    /** 共享日历标题模板 */
    private String calendarTitle = "E10会议-{meetingId}";
    /** 共享日历描述 */
    private String calendarDescription = "来自 OA 的会议提醒";
    /** 日程提醒提前量（秒），有序 */
    private List<Integer> remindBeforeSecs = new ArrayList<>(Arrays.asList(3600, 600));
    /** userid 映射模式：direct / phone / mobile */
    private String useridMode = "direct";
    /**
     * phone 模式下查询手机号的 SQL（E10 版本）。
     * 使用 E10 原生人员主表 {@code eteams.employee}（E10 组织数据在 eteams 库），
     * 登录列 {@code username}，手机号 {@code mobile}，多租户过滤列 {@code TENANT_KEY}。
     * 因 {@code com.weaver.verupgrade.conn.RecordSet} 默认连接 ecology10 库，跨库查询必须带 {@code eteams.} 前缀。
     * 两个 ? 占位符顺序为：登录名、租户键。
     */
    private String useridPhoneSql = "SELECT mobile FROM eteams.employee WHERE username = ? AND TENANT_KEY = ? AND delete_type = 0 AND STATUS = 'normal' AND TYPE = 'inside'";
    /** E10 多租户组织识别码（组织识别码），用于 eteams.employee 等组织表过滤 */
    private String tenantKey = "";

    public String getCorpid() {
        return corpid;
    }

    public void setCorpid(String corpid) {
        this.corpid = corpid;
    }

    public String getCorpsecret() {
        return corpsecret;
    }

    public void setCorpsecret(String corpsecret) {
        this.corpsecret = corpsecret;
    }

    public int getTokenSafeSecs() {
        return tokenSafeSecs;
    }

    public void setTokenSafeSecs(int tokenSafeSecs) {
        this.tokenSafeSecs = tokenSafeSecs;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getCalendarTitle() {
        return calendarTitle;
    }

    public void setCalendarTitle(String calendarTitle) {
        this.calendarTitle = calendarTitle;
    }

    public String getCalendarDescription() {
        return calendarDescription;
    }

    public void setCalendarDescription(String calendarDescription) {
        this.calendarDescription = calendarDescription;
    }

    public List<Integer> getRemindBeforeSecs() {
        return remindBeforeSecs;
    }

    public void setRemindBeforeSecs(List<Integer> remindBeforeSecs) {
        this.remindBeforeSecs = remindBeforeSecs;
    }

    public String getUseridMode() {
        return useridMode;
    }

    public void setUseridMode(String useridMode) {
        this.useridMode = useridMode;
    }

    public String getUseridPhoneSql() {
        return useridPhoneSql;
    }

    public void setUseridPhoneSql(String useridPhoneSql) {
        this.useridPhoneSql = useridPhoneSql;
    }

    public String getTenantKey() {
        return tenantKey;
    }

    public void setTenantKey(String tenantKey) {
        this.tenantKey = tenantKey;
    }

    /**
     * 加载配置。
     *
     * @return 配置实例；加载失败时返回携带默认值的实例
     * @author DuJiang
     */
    public static WeComConfig load() {
        WeComConfig config = new WeComConfig();
        Properties props = new Properties();

        boolean loaded = loadFromClasspath(props);
        String externalPath = System.getProperty(CONFIG_PATH_PROP);
        if (externalPath != null && loadFromFile(props, externalPath)) {
            loaded = true;
        }
        File defaultExternal = new File(System.getProperty("user.home"), DEFAULT_CONFIG_PATH);
        if (defaultExternal.exists() && defaultExternal.isFile()) {
            loadFromFile(props, defaultExternal.getAbsolutePath());
            loaded = true;
        }

        config.apply(props);
        if (!loaded) {
            log.warn("[WeComConfig] 未找到任何配置文件，使用默认/占位配置，请检查 wecom-config.properties 是否就绪");
        }
        return config;
    }

    /**
     * 从 classpath 加载属性。
     *
     * @author DuJiang
     */
    private static boolean loadFromClasspath(Properties props) {
        try (InputStream in = WeComConfig.class.getClassLoader().getResourceAsStream(DEFAULT_CONFIG_PATH)) {
            if (in != null) {
                props.load(in);
                return true;
            }
        } catch (Exception e) {
            log.warn("[WeComConfig] 读取 classpath 配置失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从外部文件加载属性。
     *
     * @author DuJiang
     */
    private static boolean loadFromFile(Properties props, String path) {
        try (InputStream in = new FileInputStream(path)) {
            props.load(in);
            log.info("[WeComConfig] 已加载外部配置: {}", path);
            return true;
        } catch (Exception e) {
            log.error("[WeComConfig] 读取外部配置失败 {}: {}", path, e.getMessage());
        }
        return false;
    }

    /**
     * 将属性应用到配置实例。
     *
     * @author DuJiang
     */
    private void apply(Properties props) {
        setCorpid(props.getProperty("wecom.corpid", getCorpid()));
        setCorpsecret(props.getProperty("wecom.corpsecret", getCorpsecret()));
        setTokenSafeSecs(Util.getIntValue(props.getProperty("wecom.token.safe.secs"), getTokenSafeSecs()));
        setApiBase(props.getProperty("wecom.api.base", getApiBase()));
        setCalendarTitle(props.getProperty("wecom.calendar.title", getCalendarTitle()));
        setCalendarDescription(props.getProperty("wecom.calendar.description", getCalendarDescription()));
        setUseridMode(props.getProperty("wecom.userid.mode", getUseridMode()));
        setUseridPhoneSql(props.getProperty("wecom.userid.phone.sql", getUseridPhoneSql()));
        setTenantKey(props.getProperty("wecom.tenant.key", getTenantKey()));

        String remindSecs = props.getProperty("wecom.remind.before.secs");
        if (remindSecs != null && !remindSecs.trim().isEmpty()) {
            List<Integer> secs = new ArrayList<>();
            for (String part : remindSecs.split(",")) {
                try {
                    secs.add(Integer.valueOf(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略非法提醒配置项
                }
            }
            if (!secs.isEmpty()) {
                setRemindBeforeSecs(secs);
            }
        }
    }
}
