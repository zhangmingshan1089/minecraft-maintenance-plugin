package cn.yourname.maintenance.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文案渲染：自动探测 MiniMessage / 旧色码，转换、解析、兜底三件套。
 *
 * <p>策略（为什么这么做）：
 * <ol>
 *   <li>原文若已含 MiniMessage 标签，就不做 & 码转换——否则 URL 查询串里的 {@code &b} 会被误换成颜色。</li>
 *   <li>否则把 &c / &x&f&f&0&0&0&0 转成等价 MiniMessage 标签，统一交给一个解析器。</li>
 *   <li>{@code %key%} 占位符换成 {@code <mm_key>} 并用 Placeholder.component 注入，
 *       这样玩家名里带 &lt; &gt; 也不会破坏标签结构。</li>
 *   <li>用宽松 MiniMessage（非法标签按字面渲染，不抛异常）；仍包一层 catch，
 *       失败时降级为「剥掉所有尖括号标签 + 旧色码序列化」，保证插件永不因文案崩。</li>
 * </ol>
 */
public final class TextUtil {

    private static final Pattern LEGACY_CODE = Pattern.compile("&([0-9a-fA-Fk-orK-OR])");
    private static final Pattern LEGACY_HEX = Pattern.compile("&x(&[0-9a-fA-F]){6}");
    private static final Pattern MINI_TAG = Pattern.compile("<[a-zA-Z_][^<>]{0,120}>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^<>]{0,160}>");

    private static final Map<String, String> LEGACY_TO_MINI = Map.ofEntries(
            Map.entry("0", "<black>"), Map.entry("1", "<dark_blue>"),
            Map.entry("2", "<dark_green>"), Map.entry("3", "<dark_aqua>"),
            Map.entry("4", "<dark_red>"), Map.entry("5", "<dark_purple>"),
            Map.entry("6", "<gold>"), Map.entry("7", "<gray>"),
            Map.entry("8", "<dark_gray>"), Map.entry("9", "<blue>"),
            Map.entry("a", "<green>"), Map.entry("b", "<aqua>"),
            Map.entry("c", "<red>"), Map.entry("d", "<light_purple>"),
            Map.entry("e", "<yellow>"), Map.entry("f", "<white>"),
            Map.entry("k", "<obfuscated>"), Map.entry("l", "<bold>"),
            Map.entry("m", "<strikethrough>"), Map.entry("n", "<underlined>"),
            Map.entry("o", "<italic>"), Map.entry("r", "<reset>")
    );

    private final Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public TextUtil(Logger logger) {
        this.logger = logger;
    }

    public Component render(String raw, Map<String, Component> placeholders) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        Map<String, Component> ph = placeholders == null ? Map.of() : placeholders;

        boolean alreadyMini = MINI_TAG.matcher(raw).find();
        String s = alreadyMini ? raw : legacyToMini(raw);

        // %key% -> <mm_key>；只替换调用方显式提供的键，未知占位符原样留给玩家看到（不泄露内部信息）
        List<TagResolver> resolvers = new ArrayList<>();
        for (Map.Entry<String, Component> entry : ph.entrySet()) {
            String token = "%" + entry.getKey() + "%";
            if (s.contains(token)) {
                s = s.replace(token, "<mm_" + entry.getKey() + ">");
                resolvers.add(Placeholder.component("mm_" + entry.getKey(), entry.getValue()));
            }
        }

        if (MINI_TAG.matcher(s).find()) {
            try {
                return MiniMessage.miniMessage().deserialize(
                        s, TagResolver.resolver(resolvers.toArray(new TagResolver[0])));
            } catch (Throwable t) {
                warnOnce(raw, t);
                return fallback(raw, ph);
            }
        }
        // 无任何标签：旧色码已在上面转换，这里再走一次 legacy 序列化是幂等的
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    /** 给 version-text 这种只有 String API 的地方用。 */
    public String plain(String raw, Map<String, Component> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(render(raw, placeholders));
    }

    private Component fallback(String raw, Map<String, Component> ph) {
        String s = raw;
        for (Map.Entry<String, Component> entry : ph.entrySet()) {
            String plain = PlainTextComponentSerializer.plainText().serialize(entry.getValue());
            s = s.replace("%" + entry.getKey() + "%", plain);
        }
        s = ANY_TAG.matcher(s).replaceAll("");
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private static String legacyToMini(String input) {
        StringBuffer sb = new StringBuffer();
        Matcher hex = LEGACY_HEX.matcher(input);
        while (hex.find()) {
            // "&x&f&f&0&0&0&0" -> "xff0000" -> "ff0000"
            String value = hex.group().replace("&", "").substring(1);
            hex.appendReplacement(sb, Matcher.quoteReplacement("<color:#" + value + ">"));
        }
        hex.appendTail(sb);
        String step = sb.toString();

        sb = new StringBuffer();
        Matcher code = LEGACY_CODE.matcher(step);
        while (code.find()) {
            String tag = LEGACY_TO_MINI.get(code.group(1).toLowerCase(Locale.ROOT));
            code.appendReplacement(sb, Matcher.quoteReplacement(tag == null ? code.group() : tag));
        }
        code.appendTail(sb);
        return sb.toString();
    }

    private void warnOnce(String raw, Throwable t) {
        String key = raw.length() > 80 ? raw.substring(0, 80) : raw;
        if (warned.add(key)) {
            logger.warning("[Maintenance] 文案解析失败，已降级为纯文本: " + key + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** 轻量占位符构造器，避免每个调用点都 new HashMap。 */
    public static final class Placeholders {
        private final Map<String, Component> map = new LinkedHashMap<>();

        public static Placeholders of() {
            return new Placeholders();
        }

        public Placeholders put(String key, Object value) {
            map.put(key, Component.text(value == null ? "" : String.valueOf(value)));
            return this;
        }

        public Placeholders component(String key, Component value) {
            map.put(key, value == null ? Component.empty() : value);
            return this;
        }

        public Map<String, Component> build() {
            return Map.copyOf(map);
        }
    }
}
