package com.weaver.seconddev.wecom.service.impl;

import com.weaver.seconddev.wecom.service.UserIdMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 直通映射：约定「E10 登录名 = 企业微信 userid」。
 *
 * <p>适用于 E10 账号与企微 userid 已对齐的环境，免维护映射表。
 * 若需查映射表，请实现 {@link UserIdMapper} 并按实际表结构扩展
 * （参考 ecology10 库 wechat/wx 前缀表或「企业微信集成」配置导出）。</p>
 *
 * @author DuJiang
 */
@Slf4j
public class DirectUserIdMapper implements UserIdMapper {

    @Override
    public String map(String e10UserId) {
        log.info("[DirectUserIdMapper] map 直通映射, e10UserId={} → userid={}", e10UserId, e10UserId);
        return e10UserId;
    }
}
