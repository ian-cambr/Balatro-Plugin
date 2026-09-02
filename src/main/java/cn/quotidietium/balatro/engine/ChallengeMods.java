package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.i18n.Lang;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 挑战模式修饰加载器：从 {@code /challengemods.txt}（由 gen-golden 从原版 data.js 导出）
 * 读取每个挑战的 mods，应用到 {@link RunState}（{@link Mods}）。
 *
 * <p>handSize/handsSet 仅写入 Mods 字段，由 {@code applyVouchersPassive} 应用；
 * jokers/money 在 createRun 内立即生效；其余布尔/数值修饰直接置位。
 */
public final class ChallengeMods {

    private static final Map<String, List<String[]>> BY_KEY = new HashMap<>();

    static {
        load();
    }

    private ChallengeMods() {
    }

    public static boolean exists(String challengeKey) {
        return BY_KEY.containsKey(challengeKey);
    }

    /** 把指定挑战的 mods 应用到状态（在 createRun 内、buildFullDeck 之前调用）。 */
    public static void applyTo(RunState s, String challengeKey) {
        List<String[]> mods = BY_KEY.get(challengeKey);
        if (mods == null) return;
        for (String[] kv : mods) applyOne(s, kv[0], kv[1]);
    }

    private static void applyOne(RunState s, String k, String v) {
        Mods m = s.mods;
        try {
            switch (k) {
                case "noInterest" -> m.noInterest = bool(v);
                case "freeReroll" -> m.freeReroll = bool(v);
                case "doubleInterest" -> m.doubleInterest = bool(v);
                case "minRewardMoney" -> m.minRewardMoney = Integer.parseInt(v);
                case "blindMult" -> m.blindMult = Double.parseDouble(v);
                case "handSize" -> m.handSize = Integer.parseInt(v);
                case "handsSet" -> m.handsSet = Integer.parseInt(v);
                case "doubleBoss" -> m.doubleBoss = bool(v);
                case "jokerTax" -> m.jokerTax = Double.parseDouble(v);
                case "allEternal" -> m.allEternal = bool(v);
                case "shopDiscount" -> m.shopDiscount = Double.parseDouble(v);
                case "facesToStone" -> m.facesToStone = bool(v);
                case "checkered" -> m.checkered = bool(v);
                case "numbersToFaces" -> m.numbersToFaces = bool(v);
                case "glassDouble" -> m.glassDouble = bool(v);
                case "inflation" -> m.inflation = bool(v);
                case "allStone" -> m.allStone = bool(v);
                case "must5" -> m.must5 = bool(v);
                case "rewardMult" -> m.rewardMult = Double.parseDouble(v);
                case "smallBigRewardHalf" -> m.smallBigRewardHalf = bool(v);
                case "noJokers" -> m.noJokers = bool(v);
                case "jokers" -> { for (String jk : v.split(",")) if (!jk.isEmpty()) s.gainJoker(jk, null); }
                // 永恒小丑开局（真版十五分钟城市：永恒「乘公交」+「捷径」）——R102 对齐真版新增
                // R122 扩展：值可带版本后缀 "key|negative"（真版疯狂世界：永恒负片空想性错觉）
                case "eternalJokers" -> {
                    for (String jk : v.split(",")) {
                        if (jk.isEmpty()) continue;
                        String[] kv = jk.split("\\|");
                        var ji = cn.quotidietium.balatro.engine.joker.JokerRegistry.create(kv[0]);
                        if (ji != null && s.jokerSpace() > 0) {
                            ji.eternal = true;
                            if (kv.length > 1 && "negative".equals(kv[1])) {
                                ji.edition = cn.quotidietium.balatro.engine.Data.Edition.NEGATIVE;
                            }
                            s.jokers.add(ji);
                            s.msg(Lang.t("msg.eternal_joker", cn.quotidietium.balatro.engine.joker.JokerRegistry.nameOf(jk)));
                        }
                    }
                    Engine.recomputeFlags(s);
                }
                case "noBlindReward" -> m.noBlindReward = bool(v);
                case "noHandPay" -> m.noHandPay = bool(v);
                case "faceDouble" -> m.faceDouble = bool(v);
                case "xrayFacedown" -> m.xrayFacedown = bool(v);
                // 禁入清单（真版煎蛋卷：经济券/小丑不进商店与随机池）——R108 对齐真版新增
                case "banVouchers" -> { for (String bk : v.split(",")) if (!bk.isEmpty()) m.bannedVouchers.add(bk); }
                case "banJokers" -> { for (String bk : v.split(",")) if (!bk.isEmpty()) m.bannedJokers.add(bk); }
                // R122 真版疯狂世界：禁现 Boss + 牌组点数带（仅 2~9 共 32 张）
                case "banBosses" -> { for (String bk : v.split(",")) if (!bk.isEmpty()) m.bannedBosses.add(bk); }
                case "rankRange" -> {
                    String[] mm = v.split(",");
                    if (mm.length == 2) { m.rankMin = Integer.parseInt(mm[0]); m.rankMax = Integer.parseInt(mm[1]); }
                }
                // ---- R123：其余 13 挑战真版对齐 ----
                case "banTarots" -> { for (String bk : v.split(",")) if (!bk.isEmpty()) m.bannedTarots.add(bk); }
                case "banSpectrals" -> { for (String bk : v.split(",")) if (!bk.isEmpty()) m.bannedSpectrals.add(bk); }
                case "banPacks" -> { for (String bk : v.split(",")) if (!bk.isEmpty()) m.bannedPacks.add(bk); }
                case "banTags" -> { for (String bk : v.split(",")) if (!bk.isEmpty()) m.bannedTags.add(bk); }
                case "chipsCapByMoney" -> m.chipsCapByMoney = bool(v);
                case "playedDebuff" -> m.playedDebuff = bool(v);
                case "redSealDeck" -> m.redSealDeck = bool(v);
                case "glassDeck" -> m.glassDeck = bool(v);
                case "typecastTrigger" -> m.typecastTrigger = bool(v);
                case "luxuryTax" -> m.luxuryTax = bool(v);
                case "inflationPerBuy" -> m.inflationPerBuy = bool(v);
                case "discardCost" -> m.discardCost = bool(v);
                case "smallBigNoReward" -> m.smallBigNoReward = bool(v);
                case "discardsSet" -> m.discardsSet = Integer.parseInt(v);
                case "jokerSlotsSet" -> m.jokerSlotsSet = Integer.parseInt(v);
                // 开局持有券（富者愈富：种子基金+摇钱树；布拉姆：戏法+幻觉 等）
                case "vouchers" -> { for (String vk : v.split(",")) if (!vk.isEmpty()) s.vouchers.add(vk); }
                // 开局消耗品（布拉姆：皇帝+女皇）——格式 kind:key
                case "startConsumables" -> {
                    for (String ck : v.split(",")) {
                        int c = ck.indexOf(':');
                        if (c > 0) s.addConsumableKey(ck.substring(0, c), ck.substring(c + 1));
                    }
                }
                case "money" -> s.money = Long.parseLong(v);
                default -> { /* 未知 mod（如 hands=-99，被 handsSet 覆盖）忽略 */ }
            }
        } catch (RuntimeException ignored) {
            // 单个 mod 值非法（如非数字）只跳过该条，不让整局 createRun 崩溃
        }
    }

    private static boolean bool(String v) {
        return "true".equals(v);
    }

    private static void load() {
        try (InputStream in = ChallengeMods.class.getResourceAsStream("/challengemods.txt")) {
            if (in == null) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                String curKey = null;
                List<String[]> cur = null;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (line.startsWith("CHALLENGE ")) {
                        curKey = line.substring(10);
                        cur = new ArrayList<>();
                        BY_KEY.put(curKey, cur);
                    } else if (line.equals("END")) {
                        curKey = null;
                        cur = null;
                    } else if (cur != null) {
                        int eq = line.indexOf('=');
                        if (eq > 0) cur.add(new String[]{line.substring(0, eq), line.substring(eq + 1)});
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 挑战 mods 缺失/损坏时退化为无挑战（不阻断插件加载）
        }
    }
}
