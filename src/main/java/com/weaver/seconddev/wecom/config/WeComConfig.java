package com.weaver.seconddev.wecom.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 企业微信集成配置。
 *
 * <p>配置来源为 E10 配置中心中的
 * {@code weaver-secondev-wecom.properties}。配置文件由
 * {@code com.weaver.custom.configcenter.SecondevWeComConfigCenter}
 * 注册，属性通过 Spring {@link Value} 注入。</p>
 *
 * @author DuJiang
 */
@Slf4j
@Configuration
@RefreshScope
public class WeComConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** corpid */
    @Value("${wecom.corpid:}")
    private String corpid;
    /** corpsecret */
    @Value("${wecom.corpsecret:}")
    private String corpsecret;
    /** token 提前刷新秒数 */
    @Value("${wecom.token.safe.secs:200}")
    private int tokenSafeSecs;
    /** 企业微信 API 基础域名 */
    @Value("${wecom.api.base:https://qyapi.weixin.qq.com/cgi-bin}")
    private String apiBase;
    /** 共享日历标题模板 */
    @Value("${wecom.calendar.title:}")
    private String calendarTitle;
    /** 共享日历描述 */
    @Value("${wecom.calendar.description:来自 OA 的会议提醒}")
    private String calendarDescription;
    /** 日程提醒提前量（秒），逗号分隔 */
    @Value("${wecom.remind.before.secs:3600,600}")
    private String remindBeforeSecsValue;
    /** userid 映射模式：direct / phone / mobile */
    @Value("${wecom.userid.mode:direct}")
    private String useridMode;
    /** E10 多租户组织识别码，用于 eteams.employee 等组织表过滤 */
    @Value("${wecom.tenant.key:}")
    private String tenantKey;

    /** 输出不包含敏感值的加载结果，便于部署后确认配置中心绑定状态。 */
    @PostConstruct
    public void afterPropertiesSet() {
        if (!hasText(corpid) || !hasText(corpsecret)) {
            log.warn("[WeComConfig] 企业微信凭证未配置，请检查 weaver-secondev-wecom.properties 中的 wecom.corpid/wecom.corpsecret");
            return;
        }
        log.info("[WeComConfig] E10 配置中心加载完成, useridMode={}, tenantKeyConfigured={}",
                useridMode, hasText(tenantKey));
    }

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
        return hasText(calendarTitle) ? calendarTitle : "E10会议-{meetingId}";
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
        List<Integer> secs = new ArrayList<>();
        if (hasText(remindBeforeSecsValue)) {
            for (String part : remindBeforeSecsValue.split(",")) {
                try {
                    secs.add(Integer.valueOf(part.trim()));
                } catch (NumberFormatException ignored) {
                    log.warn("[WeComConfig] 忽略非法日程提醒配置项: {}", part);
                }
            }
        }
        return secs.isEmpty() ? new ArrayList<>(Arrays.asList(3600, 600)) : secs;
    }

    public void setRemindBeforeSecs(List<Integer> remindBeforeSecs) {
        if (remindBeforeSecs == null || remindBeforeSecs.isEmpty()) {
            this.remindBeforeSecsValue = "";
            return;
        }
        StringBuilder value = new StringBuilder();
        for (Integer seconds : remindBeforeSecs) {
            if (seconds == null) {
                continue;
            }
            if (value.length() > 0) {
                value.append(',');
            }
            value.append(seconds);
        }
        this.remindBeforeSecsValue = value.toString();
    }

    public String getUseridMode() {
        return useridMode;
    }

    public void setUseridMode(String useridMode) {
        this.useridMode = useridMode;
    }

    public String getTenantKey() {
        return tenantKey;
    }

    public void setTenantKey(String tenantKey) {
        this.tenantKey = tenantKey;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
