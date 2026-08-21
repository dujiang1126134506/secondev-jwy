package com.weaver.seconddev.wecom.service;

/**
 * E10 账号 → 企业微信 userid 映射器。
 *
 * <p>参会人在 E10 中是账号（如工号），同步前需转为企业微信 userid。
 * 映射来源两种：</p>
 * <ul>
 *   <li>direct：约定「E10 登录名 = 企业微信 userid」，直接透传</li>
 *   <li>map：查询 E10 集成企微时生成的映射表（一般在 ecology10 库 wechat/wx 前缀表，
 *       或「企业微信集成」配置导出），可通过实现本接口扩展</li>
 * </ul>
 *
 * @author DuJiang
 */
public interface UserIdMapper {

    /**
     * 将 E10 账号映射为企业微信 userid。
     *
     * @param e10UserId E10 账号
     * @return 企业微信 userid；映射不到时返回 null（上层记录告警，不阻断整体同步）
     * @author DuJiang
     */
    String map(String e10UserId);
}
