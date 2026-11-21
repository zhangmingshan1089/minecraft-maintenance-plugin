package cn.yourname.maintenance.text;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * PlaceholderAPI 软依赖钩子。
 *
 * <p>不编译期依赖 PAPI：plugin.yml 里只写 softdepend，通过反射探测并调用
 * PlaceholderAPI#setPlaceholders(Player, String)，让文案也能用 PAPI 原生占位符。
 * 没装 PAPI、反射失败、调用异常时一律原样返回，绝不阻断插件。
 */
public final class PlaceholderHook {

    private final Logger logger;
    private Method setPlaceholders;
    private boolean available;

    public PlaceholderHook(Logger logger) {
        this.logger = logger;
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            available = false;
            return;
        }
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholders = api.getMethod("setPlaceholders", Player.class, String.class);
            available = true;
            logger.info("[Maintenance] 检测到 PlaceholderAPI，已启用软依赖文案展开。");
        } catch (Throwable t) {
            available = false;
            logger.warning("[Maintenance] PlaceholderAPI 钩子初始化失败，已降级为不展开: " + t.getClass().getSimpleName());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 只在主线程、且消息要发给具体玩家时调用。 */
    public String expand(Player player, String input) {
        if (!available || player == null || setPlaceholders == null || input == null || input.isEmpty()) {
            return input;
        }
        try {
            Object result = setPlaceholders.invoke(null, player, input);
            return result instanceof String s ? s : input;
        } catch (Throwable t) {
            // 反射调用失败不能影响主流程
            return input;
        }
    }
}
