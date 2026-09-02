package cn.quotidietium.balatro.engine.joker;

import cn.quotidietium.balatro.engine.Joker;
import cn.quotidietium.balatro.engine.JokerInstance;
import cn.quotidietium.balatro.i18n.Lang;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 小丑注册表：key → {@link Joker}。启动时注册 {@link BasicJoker} 全部，
 * 并从 {@code /jokermeta.txt}（由 {@code tools/gen-golden.mjs} 从原版 jokers.js 导出）
 * 加载权威的稀有度/售价/名称元数据，供商店生成使用。
 *
 * <p>其他模块/插件可经 {@link #register(Joker)} 追加自定义小丑。
 */
public final class JokerRegistry {

    // register() 是公开 API（第三方插件可在运行期追加），BY_KEY 与主线程的 byKey/allJokers
    // 读并发——用 ConcurrentHashMap 防第三方异步注册时的丢条目/迭代异常（长期高负载加固）。
    // RARITY/COST/NAME 仅在静态初始化（loadMeta）写入、之后只读，HashMap 即安全。
    private static final Map<String, Joker> BY_KEY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Integer> RARITY = new HashMap<>();
    private static final Map<String, Integer> COST = new HashMap<>();
    private static final Map<String, String> NAME = new HashMap<>();
    private static final List<Joker> ORDERED = new ArrayList<>(); // 按原版 jokers.js 定义顺序

    static {
        for (BasicJoker j : BasicJoker.values()) {
            register(j);
        }
        loadMeta();
    }

    private JokerRegistry() {
    }

    /**
     * 注册（覆盖同名）。线程安全（任意线程可调；与主线程的 byKey/allJokers 并发安全）。
     *
     * <p>注意：注册的小丑可经 {@link #byKey}/{@link #create} 获取（供第三方自行发放），
     * 但**不会自动进入商店池/gainRandomJoker**（二者按 ORDERED=原版元数据顺序收录）。
     */
    public static void register(Joker joker) {
        BY_KEY.put(joker.key(), joker);
    }

    /** 按 key 取定义，不存在返回 null。 */
    public static Joker byKey(String key) {
        return BY_KEY.get(key);
    }

    /** 按 key 创建一个运行时实例，不存在返回 null。 */
    public static JokerInstance create(String key) {
        Joker def = BY_KEY.get(key);
        return def == null ? null : new JokerInstance(def);
    }

    public static boolean exists(String key) {
        return BY_KEY.containsKey(key);
    }

    /** 全部已注册小丑。 */
    public static Collection<Joker> allJokers() {
        return BY_KEY.values();
    }

    /** 按原版 jokers.js 定义顺序返回（供商店 pick 复现）。 */
    public static List<Joker> allJokersOrdered() {
        return ORDERED;
    }

    /** 稀有度（0 普通 / 1 罕见 / 2 稀有 / 3 传奇）；未登记返回 0。 */
    public static int rarityOf(String key) {
        return RARITY.getOrDefault(key, 0);
    }

    public static int costOf(String key) {
        return COST.getOrDefault(key, 0);
    }

    /**
     * 显示名：优先取语言文件 {@code joker.<key>.name}；未翻译时退回元数据里的名字，
     * 再退回 key（漏翻在游戏内直接可见）。
     */
    public static String nameOf(String key) {
        String tk = "joker." + key + ".name";
        if (Lang.has(tk)) return Lang.t(tk);
        String n = NAME.get(key);
        return n != null ? n : key;
    }

    private static void loadMeta() {
        try (InputStream in = JokerRegistry.class.getResourceAsStream("/jokermeta.txt")) {
            if (in == null) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    String[] p = line.split("\\|", -1);
                    if (p.length >= 5 && p[0].equals("JOKER")) {
                        // 单行损坏（非数字稀有度/售价）只跳过该行，不让整张表加载失败
                        try {
                            RARITY.put(p[1], Integer.parseInt(p[2]));
                            COST.put(p[1], Integer.parseInt(p[3]));
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        NAME.put(p[1], p[4]);
                        Joker j = BY_KEY.get(p[1]);
                        if (j != null) ORDERED.add(j); // 按元数据(=原版)顺序收录
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 元数据缺失/损坏时退化为默认值（稀有度 0 / 售价 0 / 名称=key），不阻断插件加载
        }
    }
}
