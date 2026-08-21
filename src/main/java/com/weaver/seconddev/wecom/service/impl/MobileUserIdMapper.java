package com.weaver.seconddev.wecom.service.impl;

import com.weaver.seconddev.wecom.client.WeComScheduleClient;
import com.weaver.seconddev.wecom.service.UserIdMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 手机号直传模式：参会人标识直接是手机号，调用企微 <code>user/get_by_mobile</code> 换取 userid。
 *
 * <p>适用于 E10 会议动作已将参会人手机号作为参数传入的场景，
 * 无需再查询 E10 人员表。</p>
 *
 * @author DuJiang
 */
@Slf4j
public class MobileUserIdMapper implements UserIdMapper {

    private final WeComScheduleClient client;

    public MobileUserIdMapper(WeComScheduleClient client) {
        this.client = client;
    }

    @Override
    public String map(String mobile) {
        if (mobile == null || mobile.trim().isEmpty()) {
            log.warn("[MobileUserIdMapper] map 入参手机号为空，返回 null");
            return null;
        }
        String cleanMobile = mobile.trim();
        log.info("[MobileUserIdMapper] map 入口, mobile={}", cleanMobile);
        String userid = client.getUserIdByMobile(cleanMobile);
        if (userid == null || userid.trim().isEmpty()) {
            log.warn("[MobileUserIdMapper] 手机号[{}] 未匹配到企业微信 userid，跳过", cleanMobile);
        }
        log.info("[MobileUserIdMapper] map 出口, mobile={}, userid={}", cleanMobile, userid);
        return userid;
    }
}
