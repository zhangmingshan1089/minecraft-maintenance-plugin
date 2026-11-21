package cn.yourname.maintenance.config;

import cn.yourname.maintenance.MaintenancePlugin;
import cn.yourname.maintenance.util.IpUtil;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 维护状态存储：内存态为唯一真相，state.yml 只是可选镜像。
 *
 * <p>所有字段 volatile：异步预登录线程读、主线程写，不需要锁也不会读到半更新对象。
 * 写盘只在主线程发生（onDisable / 命令回调），异步事件里绝不碰 YamlConfiguration.save。
 */
public final class StateStore {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MaintenancePlugin plugin;
    private volatile boolean enabled;
    private volatile String reason = "";
    private volatile long since;
    private volatile boolean ipBypassEnabled;
    private volatile boolean trustForwardedIp;

    public StateStore(MaintenancePlugin plugin) {
        this.plugin = plugin;
    }

    public void load(Settings settings) {
        this.trustForwardedIp = settings.trustForwardedIp();
        this.ipBypassEnabled = settings.ipBypassEnabled();
        this.enabled = false;
        this.reason = "";
        this.since = 0L;
        if (!settings.persistState()) {
            plugin.getLogger().info("[Maintenance] persist-state=false，本次启动不读取 state.yml。");
            return;
        }
        File file = new File(plugin.getDataFolder(), "state.yml");
        if (!file.exists()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            enabled = yaml.getBoolean("maintenance.enabled", false);
            reason = yaml.getString("maintenance.reason", "");
            since = yaml.getLong("maintenance.since", 0L);
            ipBypassEnabled = yaml.getBoolean("maintenance.ip-bypass", settings.ipBypassEnabled());
        } catch (Throwable t) {
            enabled = false;
            reason = "";
            plugin.getLogger().warning("[Maintenance] state.yml 读取失败，已按未开启处理: " + t.getClass().getSimpleName());
        }
    }

    /** 只允许主线程调用。 */
    public void save() {
        if (!plugin.config().settings().persistState()) return;
        File file = new File(plugin.getDataFolder(), "state.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("maintenance.enabled", enabled);
        yaml.set("maintenance.reason", reason == null ? "" : reason);
        yaml.set("maintenance.since", since);
        yaml.set("maintenance.ip-bypass", ipBypassEnabled);
        ConfigManager.saveWithBackup(yaml, file);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String reason() {
        return reason == null ? "" : reason;
    }

    public long since() {
        return since;
    }

    public boolean isIpBypassEnabled() {
        return ipBypassEnabled;
    }

    public boolean isTrustForwardedIp() {
        return trustForwardedIp;
    }

    public void setMaintenance(boolean enabled, String reason) {
        this.enabled = enabled;
        this.reason = reason == null ? "" : reason;
        this.since = enabled ? System.currentTimeMillis() : 0L;
        save();
    }

    public void setIpBypass(boolean enabled) {
        this.ipBypassEnabled = enabled;
        save();
    }

    public void applySettings(Settings settings) {
        this.trustForwardedIp = settings.trustForwardedIp();
        // 注意：不覆盖 ipBypassEnabled —— /mt ip 的运行时选择由 state.yml 持有
    }

    /** IP 豁免判定：配置开关 + InetAddress 归一化，不做裸字符串比较。 */
    public boolean isIpBypassed(InetAddress address) {
        return ipBypassEnabled && IpUtil.isLoopback(address);
    }

    public String nowText() {
        return FORMAT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneId.systemDefault()));
    }

    public String sinceText() {
        if (since <= 0L) return "-";
        return FORMAT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(since), ZoneId.systemDefault()));
    }

    /** %until%：来自 config.yml 的预估恢复文本；留空显示「待公告」。 */
    public String untilText() {
        String value = plugin.config().settings().estimatedRestore();
        return value == null || value.isBlank() ? "待公告" : value;
    }
}
