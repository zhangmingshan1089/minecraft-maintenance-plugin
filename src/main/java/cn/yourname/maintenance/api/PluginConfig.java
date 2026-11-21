package cn.yourname.maintenance.api;

import cn.yourname.maintenance.config.BypassSnapshot;
import cn.yourname.maintenance.config.Settings;

import java.util.Map;

/** 一次 reload 产出的完整不可变视图；MaintenancePlugin 用 volatile 引用整体替换。 */
public record PluginConfig(Settings settings, BypassSnapshot bypass, Map<String, String> messages) {

    public PluginConfig {
        messages = Map.copyOf(messages);
    }

    /** 文案缺失时返回空串，由上层决定是否跳过，绝不让 null 流进 Adventure。 */
    public String msg(String key) {
        String value = messages.get(key);
        return value == null ? "" : value;
    }

    public boolean hasMessage(String key) {
        return messages.containsKey(key);
    }
}
