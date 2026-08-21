package com.weaver.seconddev.wecom.service.impl;

import com.weaver.ebuilder.common.util.UserContext;
import com.weaver.ebuilder.common.vo.UserWrapper;
import com.weaver.seconddev.wecom.client.WeComScheduleClient;
import com.weaver.seconddev.wecom.config.WeComConfig;
import com.weaver.seconddev.wecom.service.UserIdMapper;
import com.weaver.seconddev.wecom.util.Util;
import lombok.extern.slf4j.Slf4j;

/**
 * 手机号绑定模式下的 E10 用户 → 企业微信 userid 映射器。
 *
 * <p>适用于 E10 与企微按手机号自动绑定的场景。映射链路：</p>
 * <ol>
 *   <li>输入 E10 用户 ID（会议模块传入的参会人标识）</li>
 *   <li>通过工具类 <code>com.weaver.ebuilder.common.util.UserContext</code>
 *       {@code getUser(userId).getMobile()} 获取手机号</li>
 *   <li>调用企微 <code>user/get_by_mobile</code> 换取企微 userid</li>
 * </ol>
 *
 * <p>手机号统一走工具类获取，不再执行 SQL（用户要求，避免依赖具体表结构）。</p>
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
    public String map(String e10UserId) {
        if (e10UserId == null || e10UserId.trim().isEmpty()) {
            log.warn("[PhoneUserIdMapper] map 入参 E10 用户 ID 为空，返回 null");
            return null;
        }
        String userIdStr = e10UserId.trim();
        log.info("[PhoneUserIdMapper] map 入口, userId={}", userIdStr);
        String mobile = queryMobile(userIdStr);
        if (mobile == null || mobile.trim().isEmpty()) {
            log.warn("[PhoneUserIdMapper] 未查询到 E10 用户ID[{}] 的手机号，跳过", userIdStr);
            return null;
        }
        String userid = client.getUserIdByMobile(mobile.trim());
        if (userid == null || userid.trim().isEmpty()) {
            log.warn("[PhoneUserIdMapper] 手机号[{}] 未匹配到企业微信 userid，跳过", mobile);
        }
        log.info("[PhoneUserIdMapper] map 出口, userId={}, mobile={}, userid={}", userIdStr, mobile, userid);
        return userid;
    }

    /**
     * 通过平台工具类 <code>com.weaver.ebuilder.common.util.UserContext</code> 获取手机号。
     *
     * <p>{@code UserContext.getUser(userId)} 返回 {@code UserWrapper}（编译期可见），
     * 其 {@code getMobile()} 即可取手机号。未命中或调用异常时返回 null。</p>
     *
     * @param e10UserId E10 用户 ID
     * @return 手机号；未命中返回 null
     * @author DuJiang
     */
    private String queryMobile(String e10UserId) {
        try {
            Long userId = Util.getLongValue(e10UserId);
            if (userId == null || userId <= 0) {
                log.warn("[PhoneUserIdMapper] E10 用户 ID 非法: {}", e10UserId);
                return null;
            }
            UserWrapper user = UserContext.getUser(userId);
            if (user == null) {
                log.warn("[PhoneUserIdMapper] UserContext 未查到用户, userId={}", e10UserId);
                return null;
            }
            String mobile = user.getMobile();
            log.info("[PhoneUserIdMapper] UserContext 查询结果, userId={}, mobile={}", e10UserId, mobile);
            return mobile == null ? null : mobile.trim();
        } catch (Exception e) {
            log.warn("[PhoneUserIdMapper] UserContext 获取手机号异常({}): {}", e10UserId, e.getMessage());
            return null;
        }
    }
}
