package com.weaver.custom.configcenter;

import com.weaver.framework.client.annotation.WeaverConfigCenter;

/**
 * 注册企业微信日程二开的 E10 自定义配置文件。
 *
 * @author DuJiang
 */
@WeaverConfigCenter(sources = {
        @WeaverConfigCenter.ConfigProperty(
                dataId = "weaver-secondev-wecom.properties",
                group = "DEFAULT_GROUP",
                refresh = "true")
})
public class SecondevWeComConfigCenter {
}
