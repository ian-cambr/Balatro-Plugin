package cn.quotidietium.balatro.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 运行期文案（i18n）查表。
 *
 * <p>刻意不依赖 Bukkit：{@code engine/} 层是零 Bukkit 依赖的纯逻辑且要可单测，
 * 而文案在 engine 内也要用，因此这里只用 JDK 的 {@link Properties} 读 UTF-8 资源，
 * 不用 {@code YamlConfiguration}。
 *
 * <p>查表顺序：当前语言 → {@link #DEFAULT_LOCALE} 兜底 → 返回 key 本身
 * （缺键在游戏内直接可见，便于发现漏翻）。
 *
 * <p>加载顺序（后者覆盖前者）：classpath {@code /lang/<locale>.properties}
 * → 数据目录 {@code plugins/Balatro/lang/<locale>.properties}（服主自定义覆盖）。
 *
 * <p>语言在服务器启动时定一次（config 的 {@code language}），之后只读；
 * 单测可直接调 {@link #load(String, Path)} 或 {@link #reset()}。
 */
public final class Lang {

    /** 内置兜底语言：任何语言缺键都回落到它。 */
    public static final String DEFAULT_LOCALE = "en_US";

    private static volatile Map<String, String> active = null;
    private static volatile Map<String, String> fallback = null;
    private static volatile String locale = DEFAULT_LOCALE;

    private Lang() {
    }

    /** 当前生效的语言标识（如 {@code en_US}）。 */
    public static String locale() {
        ensureLoaded();
        return locale;
    }

    /**
     * 未显式 {@link #load} 过就先装内置 {@link #DEFAULT_LOCALE}。
     *
     * <p>engine 层是纯逻辑、单测里不经插件启动流程，没有这层兜底文案会全变成裸 key，
     * 断言消息文本的用例会失去意义。插件启动时 {@code onEnable} 仍会按 config 重载。
     */
    private static void ensureLoaded() {
        if (active != null) return;
        synchronized (Lang.class) {
            if (active != null) return;
            Map<String, String> def = Collections.unmodifiableMap(readAll(DEFAULT_LOCALE, null));
            fallback = def;
            active = def;
            locale = DEFAULT_LOCALE;
        }
    }

    /**
     * 载入语言表。
     *
     * @param wanted    语言标识；null/空回落到 {@link #DEFAULT_LOCALE}
     * @param dataDir   插件数据目录（其下的 {@code lang/} 可覆盖内置文案）；可为 null
     */
    public static void load(String wanted, Path dataDir) {
        String loc = normalize(wanted);
        Map<String, String> def = readAll(DEFAULT_LOCALE, dataDir);
        Map<String, String> cur = DEFAULT_LOCALE.equals(loc) ? def : readAll(loc, dataDir);
        if (cur.isEmpty()) {
            // config 里写了个不存在的语言：整体回落，locale() 也要如实报兜底语言。
            cur = def;
            loc = DEFAULT_LOCALE;
        }
        fallback = Collections.unmodifiableMap(def);
        active = Collections.unmodifiableMap(cur);
        locale = loc;
    }

    /** 回到未加载状态（下次取用会重新装内置 {@link #DEFAULT_LOCALE}），供单测隔离用。 */
    public static void reset() {
        synchronized (Lang.class) {
            active = null;
            fallback = null;
            locale = DEFAULT_LOCALE;
        }
    }

    /** 该 key 是否有文案（当前语言或兜底语言任一即可）。 */
    public static boolean has(String key) {
        ensureLoaded();
        return active.containsKey(key) || fallback.containsKey(key);
    }

    /**
     * 取文案并填参：文案里的 <code>{0}</code>、<code>{1}</code>… 依次替换为 {@code args}。
     *
     * <p>不用 {@code MessageFormat}：它对单引号有转义规则（{@code 'x'} 会被吃掉），
     * 文案里写撇号很容易踩坑；这里只做位置替换，无转义规则。
     */
    public static String t(String key, Object... args) {
        ensureLoaded();
        String raw = active.get(key);
        if (raw == null) raw = fallback.get(key);
        if (raw == null) return key;
        if (args == null || args.length == 0) return raw;
        return fill(raw, args);
    }

    private static String fill(String raw, Object[] args) {
        StringBuilder sb = new StringBuilder(raw.length() + 16 * args.length);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '{') {
                sb.append(c);
                continue;
            }
            int close = raw.indexOf('}', i + 1);
            int idx = close < 0 ? -1 : parseIndex(raw, i + 1, close);
            if (idx < 0 || idx >= args.length) {
                sb.append(c);
                continue;
            }
            sb.append(args[idx]);
            i = close;
        }
        return sb.toString();
    }

    /** 解析 {@code {n}} 中的下标；非纯数字或空返回 -1（原样保留花括号）。 */
    private static int parseIndex(String raw, int from, int to) {
        if (from >= to) return -1;
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
            if (v > 999) return -1;
        }
        return v;
    }

    private static String normalize(String wanted) {
        if (wanted == null) return DEFAULT_LOCALE;
        String s = wanted.trim();
        if (s.isEmpty()) return DEFAULT_LOCALE;
        // 只留 [A-Za-z0-9_-]，避免 config 里的怪字符拼出越界资源路径。
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return DEFAULT_LOCALE;
        }
        return s;
    }

    /** 内置资源 + 数据目录覆盖，合并成一张表。 */
    private static Map<String, String> readAll(String loc, Path dataDir) {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = Lang.class.getResourceAsStream("/lang/" + loc + ".properties")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    merge(out, r);
                }
            }
        } catch (IOException ignored) {
            // 资源读不出就当没有：调用方会回落到兜底语言或 key 本身。
        }
        if (dataDir != null) {
            Path override = dataDir.resolve("lang").resolve(loc + ".properties");
            if (Files.isRegularFile(override)) {
                try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                    merge(out, r);
                } catch (IOException ignored) {
                    // 服主自定义文件坏了不该拖垮插件：保留内置文案。
                }
            }
        }
        return out;
    }

    private static void merge(Map<String, String> out, Reader r) throws IOException {
        Properties p = new Properties();
        p.load(r);
        for (String k : p.stringPropertyNames()) {
            out.put(k, p.getProperty(k));
        }
    }

    /** 当前语言表的一份快照（只读），供自检/测试断言用。 */
    public static Map<String, String> snapshot() {
        ensureLoaded();
        return new HashMap<>(active);
    }
}
