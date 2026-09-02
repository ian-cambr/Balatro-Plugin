package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.i18n.Lang;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 游戏引擎，移植自 {@code REF/balatro/js/engine.js}（纯逻辑，零 Bukkit 依赖）。
 *
 * <p>完整实现 Balatro 玩法：标准/变体牌组、8 赌注、20 挑战、牌型判定 + chips×mult 计分 +
 * 抽/出/弃 + small/big/boss 盲注 + 28 Boss 效果 + 24 跳过标签 + 商店/补充包/优惠券 +
 * 150+ 小丑（钩子框架）+ 塔罗/星球/幻灵消耗品 + 增强/版本/蜡封 + 8 底注通关 + 无尽模式。
 *
 * <p>移植正确性红线：全部随机走 {@link Rng} 命名流，{@code stream(name)} 调用顺序须与 REF 逐字一致
 * （否则种子不复现）。6 处对 REF 的有意修正（按真版/wiki）见 note/release/审计里程碑总结.md。
 */
public final class Engine {

    private static final List<Data.Boss> BOSSES = List.of(Data.Boss.values());

    /** P12 性能：numbersToFaces 的随机人头点数池（原每次 List.of(11,12,13) 新建）。 */
    private static final List<Integer> FACE_RANKS = List.of(11, 12, 13);

    /** P8 性能：Boss 抽取池静态化（常规 23 个 / 终结者 5 个）——原实现每底注新建
     *  ArrayList 逐个筛选；池内容仅取决于 showdown，且 pick 只读，可全局共享。 */
    private static final List<Data.Boss> BOSS_POOL_REGULAR;
    private static final List<Data.Boss> BOSS_POOL_FINISHERS;

    static {
        List<Data.Boss> regular = new ArrayList<>();
        List<Data.Boss> finishers = new ArrayList<>();
        for (Data.Boss b : BOSSES) {
            (Data.FINISHERS.contains(b.key) ? finishers : regular).add(b);
        }
        BOSS_POOL_REGULAR = List.copyOf(regular);
        BOSS_POOL_FINISHERS = List.copyOf(finishers);
    }

    private Engine() {
    }

    // ================= 创建一局 =================

    public static RunState createRun(String deckKey, int stakeIdx, String seed) {
        return createRun(deckKey, stakeIdx, seed, null);
    }

    public static RunState createRun(String deckKey, int stakeIdx, String seed, String challenge) {
        if (deckKey == null || deckKey.isEmpty()) deckKey = "red";
        if (seed == null || seed.isEmpty()) seed = Rng.randomSeedString();
        RunState s = new RunState(seed);
        s.deckKey = deckKey;
        s.stakeIdx = stakeIdx;
        s.challenge = challenge;
        s.money = 4;
        s.ante = 1;

        // 赌注效果（累加）
        if (stakeIdx >= 1) s.mods.redStake = true;
        if (stakeIdx >= 2) s.mods.greenStake = true;
        if (stakeIdx >= 3) s.mods.blackStake = true;
        if (stakeIdx >= 5) s.mods.purpleStake = true;
        if (stakeIdx >= 6) s.mods.orangeStake = true;
        if (stakeIdx >= 7) s.mods.goldStake = true;

        // 牌组效果（逐字对齐 engine.js createRun 牌组段）
        if ("yellow".equals(deckKey)) s.money += 10;
        if ("green".equals(deckKey)) s.mods.noInterest = true;
        if ("plasma".equals(deckKey)) s.mods.plasma = true;
        if ("magic".equals(deckKey)) {
            // 魔法牌组：开局拥有「水晶球」+ 2 张「愚人」（此时 consumableSlots=2，恰好放下）
            s.vouchers.add("crystal");
            s.addConsumableKey("tarot", "fool");
            s.addConsumableKey("tarot", "fool");
        }
        if ("nebula".equals(deckKey)) {
            // 星云牌组：开局拥有「望远镜」（消耗品槽 -1 已由 applyVouchersPassive 处理）
            s.vouchers.add("telescope");
        }
        if ("ghost".equals(deckKey)) {
            // 幽灵牌组：幻灵牌进商店 + 开局拥有 1 张「妖术」
            s.mods.spectralInShop = true;
            s.addConsumableKey("spectral", "hex");
        }
        if ("zodiac".equals(deckKey)) {
            // 黄道牌组：开局拥有「塔罗商人」「星球商人」「多重库存」
            s.vouchers.add("tarotm");
            s.vouchers.add("planetm");
            s.vouchers.add("overstock");
        }

        // 挑战修饰（jokers/money 立即生效；handSize/handsSet 由 applyVouchersPassive 应用）
        if (challenge != null) ChallengeMods.applyTo(s, challenge);

        buildFullDeck(s);
        applyVouchersPassive(s);
        startAnte(s);
        return s;
    }

    private static void buildFullDeck(RunState s) {
        Mods m = s.mods;
        List<Card> deck = s.fullDeck;
        Rng.Stream stream = s.stream("deckbuild");
        if ("erratic".equals(s.deckKey)) {
            for (int i = 0; i < 52; i++) deck.add(s.makeCard(stream.range(2, 14), stream.range(0, 3)));
        } else if ("checkered".equals(s.deckKey) || m.checkered) {
            for (int su = 0; su < 2; su++) {
                int suit = su == 0 ? 0 : 1;
                for (int rep = 0; rep < 2; rep++) for (int r = 2; r <= 14; r++) deck.add(s.makeCard(r, suit));
            }
        } else if (m.allStone) {
            for (int i = 0; i < 52; i++) {
                Card c = s.makeCard(0, -1);
                c.setEnh(Data.Enhancement.STONE);
                deck.add(c);
            }
        } else if (m.faceDouble) {
            // 十五分钟城市（真版）：两张所有人头牌、无 A/2/3 —— 4~10 各 4 张(28) + J/Q/K 各 8 张(24) = 52
            // （R102 对齐真版；REF 原版网页未实现此牌组，属 REF bug）
            for (int su = 0; su < 4; su++) {
                for (int r = 4; r <= 10; r++) deck.add(s.makeCard(r, su));
                for (int rep = 0; rep < 2; rep++) for (int r = 11; r <= 13; r++) deck.add(s.makeCard(r, su));
            }
        } else if (m.rankMin > 0) {
            // 疯狂世界（真版）：仅 2~9 共 32 张（R122 对齐真版；REF 未实现，属 REF bug）
            for (int su = 0; su < 4; su++) {
                for (int r = m.rankMin; r <= m.rankMax; r++) deck.add(s.makeCard(r, su));
            }
        } else {
            for (int su = 0; su < 4; su++) {
                for (int r = 2; r <= 14; r++) {
                    if ("abandoned".equals(s.deckKey) && r >= 11 && r <= 13) continue;
                    deck.add(s.makeCard(r, su));
                }
            }
        }
        if (m.facesToStone) {
            for (Card c : deck) {
                if (c.rank() >= 11 && c.rank() <= 13) {
                    c.setRank(0); c.setSuit(-1); c.setEnh(Data.Enhancement.STONE);
                }
            }
        }
        if (m.numbersToFaces) {
            // P12 性能：静态常量替代每次 List.of（挑战牌组逐牌一次）
            for (Card c : deck) {
                if (c.rank() >= 2 && c.rank() <= 10) c.setRank(stream.pick(FACE_RANKS));
            }
        }
        // R123 真版挑战牌组修饰
        if (m.redSealDeck) {
            for (Card c : deck) c.setSeal(Data.Seal.RED); // 孤注一掷：全牌组红蜡封（重触发）
        }
        if (m.glassDeck) {
            for (Card c : deck) c.setEnh(Data.Enhancement.GLASS); // 易碎品：全牌组玻璃
        }
    }

    // ================= 底注 / 盲注 =================

    private static void startAnte(RunState s) {
        s.playedThisAnte.clear();
        chooseBoss(s);
        s.phase = Phase.BLIND_SELECT;
        s.blindType = null;
        s.nextBlind = "small";
    }

    private static void chooseBoss(RunState s) {
        Rng.Stream st = s.stream("boss");
        // R126 对齐真版（Boss_Blinds Wiki）：Showdown 5 终结者仅在底注 8/16…出现；
        // 底注 1~7 只从 23 个常规 Boss 抽取（REF 28 个混抽为 REF bug，R45 曾"验证"该错误分布）。
        // P8 性能：池为静态共享不可变列表（内容仅取决于 showdown，pick 只读）。
        List<Data.Boss> pool = s.ante % 8 == 0 ? BOSS_POOL_FINISHERS : BOSS_POOL_REGULAR;
        Data.Boss picked = st.pick(pool);
        for (int tries = 0; tries < 5 && (picked.key.equals(s.bossKey)
                || s.mods.bannedBosses.contains(picked.key)); tries++) {
            picked = st.pick(pool);
        }
        s.bossKey = picked.key;
        s.bossQueue.clear();
        s.bossQueue.add(picked.key);
        if (s.mods.doubleBoss) {
            // 双 Boss（engine 能力，对齐 engine.js chooseBoss）：再抽第二个不同的 Boss（5 次尽力去重）
            Data.Boss second = st.pick(pool);
            for (int tries = 0; tries < 5 && (second.key.equals(picked.key)
                    || s.mods.bannedBosses.contains(second.key)); tries++) {
                second = st.pick(pool);
            }
            s.bossQueue.add(second.key);
        }
    }

    /** 当前 Boss 定义（仅命名/展示）。 */
    public static Data.Boss bossDef(RunState s) {
        if (s.bossQueue.isEmpty()) return BOSSES.get(0);
        return Data.Boss.byKey(s.bossQueue.get(0));
    }

    // ================= 标签 =================

    public static void gainTag(RunState s, String key) {
        gainTagOne(s, key);
        if (s.doubleTagPending && !key.equals("double")) {
            s.doubleTagPending = false;
            s.msg(Lang.t("msg.double_tag"));
            gainTagOne(s, key);
        }
    }

    private static void gainTagOne(RunState s, String key) {
        Data.Tag tag = null;
        for (Data.Tag t : Data.TAGS) if (t.key().equals(key)) { tag = t; break; }
        if (tag == null) return;
        s.tags.add(key);
        s.msg(Lang.t("msg.tag_gained", tag.name()));
        Rng.Stream st = s.stream("tag");
        switch (key) {
            case "double" -> s.doubleTagPending = true;
            // R127 对齐真版：罕见/稀有/四版本标签的指定小丑**免费**（"Shop has a FREE ..."）
            case "uncommon" -> { s.nextShop.put("rarity", 1); s.nextShop.put("freeFirstJoker", true); }
            case "rare" -> { s.nextShop.put("rarity", 2); s.nextShop.put("freeFirstJoker", true); }
            case "negative" -> { s.nextShop.put("edition", "negative"); s.nextShop.put("freeLastJoker", true); }
            case "foil" -> { s.nextShop.put("edition", "foil"); s.nextShop.put("freeLastJoker", true); }
            case "holo" -> { s.nextShop.put("edition", "holo"); s.nextShop.put("freeLastJoker", true); }
            case "poly" -> { s.nextShop.put("edition", "poly"); s.nextShop.put("freeLastJoker", true); }
            case "invest" -> s.nextShop.put("invest", true);
            case "voucher" -> s.nextShop.merge("extraVoucher", 1, (a, b) -> (a instanceof Number ? ((Number) a).intValue() : 0) + 1);
            case "boss" -> rerollBoss(s, true);
            // R127 对齐真版（Tags Wiki）：Standard/Charm/Meteor/Buffoon 标签=立即免费开 **Mega** 包；
            // 幽冥标签=立即免费开幻灵包（此前为商店修饰，属 REF bug）
            case "standard" -> cn.quotidietium.balatro.engine.shop.Packs.open(s, megaPackOfType(Data.PackType.STANDARD));
            case "buffoon" -> cn.quotidietium.balatro.engine.shop.Packs.open(s, megaPackOfType(Data.PackType.BUFFOON));
            case "charm" -> cn.quotidietium.balatro.engine.shop.Packs.open(s, megaPackOfType(Data.PackType.ARCANA));
            case "meteor" -> cn.quotidietium.balatro.engine.shop.Packs.open(s, megaPackOfType(Data.PackType.CELESTIAL));
            case "ethereal" -> cn.quotidietium.balatro.engine.shop.Packs.open(s, firstPackOfType(Data.PackType.SPECTRAL));
            case "coupon" -> s.nextShop.put("coupon", true);
            case "d6" -> s.nextShop.put("freeReroll", true);
            case "topup" -> { s.gainRandomJoker(0); s.gainRandomJoker(0); }
            case "handy" -> s.gainMoney(s.statsHandsPlayed);
            case "garbage" -> s.gainMoney(s.statsDiscardsUnused);
            case "speed" -> s.gainMoney(5L * s.statsBlindsSkipped);
            // R127/R131 对齐真版（Economy Tag Wiki）：<$40 翻倍（=+money）、≥$40 flat +$40、
            // 负余额【归零】（$-5→$0，非停留在负值）——REF 的 $1/$5 上限$25 为 REF bug
            case "economy" -> {
                if (s.money < 0) s.gainMoney(-s.money);
                else s.gainMoney(Math.min(40, s.money));
            }
            case "orbital" -> s.levelUpHand(st.pick(Data.HAND_TYPES), 3);
            case "juggle" -> s.nextShop.put("juggle", true);
            default -> { }
        }
    }

    /** 重掷 Boss 盲注；free=true 时免费。 */
    public static void rerollBoss(RunState s, boolean free) {
        if (!free) {
            if (!s.vouchers.contains("director") && !s.vouchers.contains("retcon")) return;
            if (!s.vouchers.contains("retcon")) {
                if (s.money < 10) return;
                s.money -= 10;
            }
        }
        chooseBoss(s);
        s.msg(Lang.t("msg.boss_rerolled", bossDef(s).displayName()));
    }

    /** 取指定类型的第一个补充包定义。 */
    private static Data.Pack firstPackOfType(Data.PackType type) {
        for (Data.Pack p : Data.PACKS) if (p.type == type) return p;
        return Data.PACKS.get(0);
    }

    /** 取指定类型的 **Mega**（x3 档：5 选 2 / Buffoon 4 选 2，$8）补充包定义——R135 自证修正：
     *  真版档位 Normal($4,3选1)/Jumbo($6,5选1)/Mega($8,5选2)（Booster Packs Wiki 表），
     *  我方 x1/x2/x3 恰对应三档；R127 的 endsWith("2") 误取 Jumbo（5 选 1）。
     *  Standard/Charm/Meteor/Buffoon 标签均给 Mega 包（Tags Wiki）。 */
    private static Data.Pack megaPackOfType(Data.PackType type) {
        for (Data.Pack p : Data.PACKS) if (p.type == type && p.key.endsWith("3")) return p;
        return firstPackOfType(type);
    }

    /** 盲注目标分。 */
    public static long blindTarget(RunState s, Data.BlindType type) {
        double base = Data.blindBase(s.ante);
        if (s.mods.greenStake) base *= Math.pow(1.15, s.ante - 1);
        if (s.mods.purpleStake) base *= Math.pow(1.3, s.ante - 1);
        double mult = type.mult;
        if (type == Data.BlindType.BOSS) {
            String bk = s.bossQueue.isEmpty() ? null : s.bossQueue.get(0);
            // R126 对齐真版（Boss_Blinds Wiki）：高墙 4×；紫罗兰之瓶 6×（REF 的 3× 为 REF bug）；
            // 缝衣针 1× 基础分（"Play only 1 hand" 的 1x base，REF 漏乘数修正）。
            if ("wall".equals(bk)) mult = 4;
            else if ("vessel".equals(bk)) mult = 6;
            else if ("needle".equals(bk)) mult = 1;
        }
        if ("plasma".equals(s.deckKey)) mult *= 2;
        if (s.mods.blindMult != 0) mult *= s.mods.blindMult;
        if (s.mods.jokerTax != 0) mult *= (1 + s.mods.jokerTax * s.jokers.size());
        return Math.round(base * mult);
    }

    /** 选择盲注（type 必须等于 nextBlind）；skip 路径 0.1.0 不支持标签（0.3.0）。 */
    public static boolean selectBlind(RunState s, Data.BlindType type, boolean skip) {
        if (s.phase != Phase.BLIND_SELECT) return false;
        if (!type.key.equals(s.nextBlind)) return false;
        if (skip) {
            if (type == Data.BlindType.BOSS) return false;
            s.statsBlindsSkipped++;
            // 禁入标签过滤（R123 真版无丑牌/易碎品）：池空则跳过无标签（跳盲本身仍生效）
            List<Data.Tag> tagPool = new ArrayList<>();
            for (Data.Tag t : Data.TAGS) {
                if (!s.mods.bannedTags.contains(t.key())) tagPool.add(t);
            }
            Data.Tag tag = tagPool.isEmpty() ? null : s.stream("skiptag").pick(tagPool);
            if (tag != null) gainTag(s, tag.key());
            List<JokerInstance> skipSnap = s.acquireJokerSnap(); // P10：池化快照
            try {
                for (int i = 0; i < skipSnap.size(); i++) {
                    JokerInstance j = skipSnap.get(i);
                    if (!j.debuff && s.jokers.contains(j)) j.def.onSkip(s, j);
                }
            } finally {
                s.releaseJokerBuffer();
            }
            s.nextBlind = type == Data.BlindType.SMALL ? "big" : "boss";
            return true;
        }
        s.blindType = type;
        startRound(s);
        return true;
    }

    // ================= 回合 =================

    private static void applyVouchersPassive(RunState s) {
        s.jokerSlots = 5 + ("black".equals(s.deckKey) ? 1 : 0) - ("painted".equals(s.deckKey) ? 1 : 0);
        if (s.mods.jokerSlotsSet >= 0) s.jokerSlots = s.mods.jokerSlotsSet; // R123 真版固定槽（0 合法；券加成叠加其上）
        s.consumableSlots = 2 + ("nebula".equals(s.deckKey) ? -1 : 0);
        s.shopSlots = 2;
        s.handSizeBase = 8 + ("painted".equals(s.deckKey) ? 2 : 0);
        s.handsBase = 4 + ("blue".equals(s.deckKey) ? 1 : 0) - ("black".equals(s.deckKey) ? 1 : 0);
        s.discardsBase = 3 + ("red".equals(s.deckKey) ? 1 : 0) - (s.stakeIdx >= 4 ? 1 : 0);
        if (s.mods.handsSet != 0) s.handsBase = s.mods.handsSet;
        if (s.mods.discardsSet != 0) s.discardsBase = s.mods.discardsSet; // R123 真版固定弃牌
        if (s.mods.handSize != 0) s.handSizeBase += s.mods.handSize;
        s.interestCap = 5;

        // 优惠券槽位/上限加成（逐字对齐 engine.js applyVouchersPassive）
        if (s.vouchers.contains("antimatter")) s.jokerSlots += 1;       // 反物质：小丑槽 +1
        if (s.vouchers.contains("crystal")) s.consumableSlots += 1;     // 水晶球：消耗品槽 +1
        if (s.vouchers.contains("overstock")) s.shopSlots += 1;         // 多重库存：商店卡牌位 +1
        if (s.vouchers.contains("overstock2")) s.shopSlots += 1;        // 多重库存+：再 +1
        if (s.vouchers.contains("seedmoney")) s.interestCap = 10;       // 种子基金：利息上限 $10
        if (s.vouchers.contains("moneytree")) s.interestCap = 20;       // 摇钱树：利息上限 $20
        if (s.vouchers.contains("grabber")) s.handsBase += 1;           // 补给手：出牌 +1
        if (s.vouchers.contains("nacho")) s.handsBase += 1;             // 顺手牵羊：出牌再 +1
        if (s.vouchers.contains("wasteful")) s.discardsBase += 1;       // 挥霍无度：弃牌 +1
        if (s.vouchers.contains("recyclo")) s.discardsBase += 1;        // 回收狂人：弃牌再 +1
        if (s.vouchers.contains("paintbrush")) s.handSizeBase += 1;     // 油漆刷：手牌上限 +1
        if (s.vouchers.contains("palette")) s.handSizeBase += 1;        // 调色板：手牌上限再 +1
        // R137 真版：通灵板/ectoplasm 的永久手牌上限减量在重建后叠加（详见 RunState.handSizePerm）
        if (s.handSizePerm != 0) s.handSizeBase += s.handSizePerm;
        // R125 对齐真版（Vouchers Wiki）：象形文字=「-1 Ante, -1 hand each round」、
        // 岩画=「-1 Ante again, -1 discard each round」——底注扣减在 buyVoucher（购时 -1），
        // 此处补缺失的每回合修正（REF 两券仅有底注部分，属 REF bug）。
        if (s.vouchers.contains("hieroglyph")) s.handsBase -= 1;        // 象形文字：每回合出牌 -1
        if (s.vouchers.contains("petroglyph")) s.discardsBase -= 1;     // 岩画：每回合弃牌 -1
    }

    private static void computeFlags(RunState s) {
        // P8 性能：双缓冲——复用备用 HashMap（clear+重填）替代每次 new HashMap。
        // 语义等价论证：旧表停驻为备用时**内容冻结**（本调用内不触碰），与本轮之前
        // 「换新表、旧表留给已持有引用者」的可观察行为一致；s.flags 字段在每处读取点
        // 都是现取现用（isFace/isSuit/HandEval/prob 等），无跨重算持快照的读取方。
        Map<String, Object> f = s.flagsSpare;
        if (f == null) f = new HashMap<>();
        s.flagsSpare = s.flags;
        f.clear();
        for (JokerInstance j : s.jokers) {
            if (j.debuff) continue;
            // P5 性能：flags() 只调一次（原实现判空+取值各调一次——BasicJoker 的
            // flags() 每次调用都新建 Map.of，双调即双倍分配）
            Map<String, Object> fl = j.def.flags();
            if (fl == null) fl = j.def.flagsFn(s, j);
            if (fl != null) {
                for (Map.Entry<String, Object> e : fl.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Number) {
                        int cur = f.get(e.getKey()) instanceof Number ? ((Number) f.get(e.getKey())).intValue() : 0;
                        f.put(e.getKey(), cur + ((Number) v).intValue());
                    } else {
                        f.put(e.getKey(), v);
                    }
                }
            }
        }
        s.flags = f;
    }

    /** 重新计算 flags（购买小丑/优惠券后调用）。 */
    public static void recomputeFlags(RunState s) {
        computeFlags(s);
    }

    /** 补满手牌（消耗品弃牌后调用）。 */
    public static void refillHand(RunState s) {
        drawUpTo(s);
        sortHand(s);
    }

    private static void startRound(RunState s) {
        applyVouchersPassive(s);
        computeFlags(s);
        Map<String, Object> f = s.flags;

        s.roundCount++;
        s.phase = Phase.ROUND;
        s.roundScore = 0;
        s.handsPlayedThisRound = 0;
        s.discardsUsedThisRound = 0;
        s.usedDiscardThisRound = false;
        s.playedTypesThisRound.clear();
        s.bossDisabled = false;
        s.bossTriggeredThisHand = false;

        s.blindTarget = blindTarget(s, s.blindType);
        s.handSizeRound = Math.max(1, s.handSizeBase + intFlag(f, "handSize"));
        if (s.mods.luxuryTax) { // R123 奢侈品税（真版）：每持 $5 手牌上限 -1（基础 10）
            s.handSizeRound = Math.max(0, s.handSizeRound - (int) (s.money / 5));
        }
        if (s.nextShop.get("juggle") != null) { s.handSizeRound += 3; s.nextShop.remove("juggle"); }
        s.handsLeft = Math.max(0, s.handsBase + intFlag(f, "hands"));
        s.discardsLeft = Math.max(0, s.discardsBase + intFlag(f, "discards"));

        // juggle 标签（下回合手牌 +3）→ 0.3.0 标签
        // Boss 回合开始效果（pre-draw：覆盖 hands/discards/handSize/失效标记/打乱）
        String bk = effectBk(s);
        s.bossSuitDebuff = null;
        s.bossFaceDebuff = false;
        s.bossLeaf = false;
        s.bellCardId = null;
        if (bk != null) {
            if ("water".equals(bk)) s.discardsLeft = 0;
            if ("needle".equals(bk)) s.handsLeft = 1;
            if ("manacle".equals(bk)) s.handSizeRound = Math.max(1, s.handSizeRound - 1);
            if ("club".equals(bk)) s.bossSuitDebuff = 2;
            if ("goad".equals(bk)) s.bossSuitDebuff = 0;
            if ("head".equals(bk)) s.bossSuitDebuff = 1;
            if ("window".equals(bk)) s.bossSuitDebuff = 3;
            if ("plant".equals(bk)) s.bossFaceDebuff = true;
            if ("leaf".equals(bk)) s.bossLeaf = true;
            if ("acorn".equals(bk)) { s.stream("acorn").shuffle(s.jokers); s.msg(Lang.t("msg.acorn")); }
        }

        // 洗牌并抽牌
        // 清除上一盲注 Boss 施加的 debuff/facedown（对齐真版：Boss 效果只在该盲注内有效）。
        // REF 原版网页不清除（fullDeck 牌状态跨盲注残留），导致换 Boss 后旧 Boss 的 debuff 仍生效——
        // 本插件修正此行为。pillar 的 debuff 在 drawOne 时基于 playedThisAnte 重新施加，清除后仍正确。
        // 不消耗 stream，不影响种子复现。
        for (Card c : s.fullDeck) { c.setDebuff(false); c.setFacedown(false); }
        // P5 性能：复用 drawPile 现有容量（clear+addAll 替代 new ArrayList 拷贝）——
        // 第二回合起零扩容分配；内容语义与「新列表装 fullDeck 再洗牌」完全一致。
        // P8 性能：首回合单步 ensureCapacity 到整副牌大小（替代 10→…→49 的逐级扩容链）；
        // 字段声明为 List，第三方替换非 ArrayList 实现时自动跳过（仅失去优化，行为不变）。
        s.drawPile.clear();
        if (s.drawPile instanceof ArrayList<Card> dp) dp.ensureCapacity(s.fullDeck.size());
        s.drawPile.addAll(s.fullDeck);
        s.discardPile.clear();
        if (s.discardPile instanceof ArrayList<Card> dcp) dcp.ensureCapacity(s.fullDeck.size());
        s.streamRound("shuffle").shuffle(s.drawPile); // P15：每回合一次性流（分段折叠，零物化/零缓存）
        s.hand.clear();
        for (int i = 0; i < s.handSizeRound; i++) drawOne(s, i == 0 && "house".equals(bk));

        // Boss post-draw：房子（首轮全面朝下）、翠绿铃（强制选中）、翠绿之叶（全部失效）
        if ("house".equals(bk)) {
            for (Card c : s.hand) c.setFacedown(true);
        }
        if ("bell".equals(bk) && !s.hand.isEmpty()) {
            s.bellCardId = s.stream("bell").pick(s.hand).id();
        }
        if (s.bossLeaf) {
            for (Card c : s.hand) c.setDebuff(true);
        }

        // 小丑回合开始钩子
        List<JokerInstance> snap = s.acquireJokerSnap(); // P10：池化快照
        try {
            for (int i = 0; i < snap.size(); i++) {
                JokerInstance j = snap.get(i);
                if (!j.debuff && s.jokers.contains(j)) {
                    j.def.onBlindSelect(s, j, s.blindType);
                    j.def.onBlindStart(s, j);
                    j.def.onRoundStart(s, j);
                }
            }
        } finally {
            s.releaseJokerBuffer();
        }
        // 整理手牌（bell/hook 等 stream.pick 已发生于此之前，安全）
        sortHand(s);
        s.msg(Lang.t("msg.round_start", s.blindTarget));
    }

    private static Card drawOne(RunState s, boolean forceFacedown) {
        if (s.drawPile.isEmpty()) return null;
        Card c = s.drawPile.remove(s.drawPile.size() - 1);
        String bk = effectBk(s);
        c.setFacedown(forceFacedown);
        if ("wheel".equals(bk) && s.stream("wheel").chance(1.0 / 7)) c.setFacedown(true);
        // X 光视界（真版）：抽到的牌 1/4 概率面朝下（R102 对齐真版；REF 原版网页未实现，属 REF bug）。
        // 命名流 "xray" 仅本挑战消耗，不影响其他挑战/标准局的种子复现。
        if (s.mods.xrayFacedown && s.stream("xray").chance(0.25)) c.setFacedown(true);
        if ("mark".equals(bk) && c.rank() >= 11 && c.rank() <= 13) c.setFacedown(true);
        if ("pillar".equals(bk) && s.playedThisAnte.contains(c.id())) c.setDebuff(true);
        if (s.bossSuitDebuff != null && !c.isEnh(Data.Enhancement.STONE) && c.suit() == s.bossSuitDebuff) c.setDebuff(true);
        if (s.bossFaceDebuff && isFaceCard(s, c)) c.setDebuff(true);
        if (s.bossLeaf) c.setDebuff(true);
        s.hand.add(c);
        return c;
    }

    private static void drawUpTo(RunState s) {
        String bk = effectBk(s);
        int n = s.handSizeRound - s.hand.size();
        if ("serpent".equals(bk)) n = 3;
        for (int i = 0; i < n; i++) {
            Card c = drawOne(s, false);
            if (c == null) break;
            if ("fish".equals(bk) && s.handsPlayedThisRound > 0) c.setFacedown(true);
        }
    }

    /**
     * 按「点数降序、同点花色升序、石头牌置末」整理手牌，与原版 {@code game.js sortHand("rank")} 逐字一致。
     *
     * <p>对齐原版：{@code rankOrder(c)=enh==="stone"?-1:c.rank}、{@code suitOrder(c)=enh==="stone"?9:c.suit}，
     * 主序 {@code rankOrder(b)-rankOrder(a)}（降序），次序 {@code suitOrder(a)-suitOrder(b)}（升序）。
     *
     * <p>计分按 {@code s.hand} 中位置从左到右（{@code playHand} 内 {@code cards.sort(indexOf)}），
     * 故整理手牌后展示与计分顺序一致（呈现给玩家的始终是排列好的手牌）。
     *
     * <p><b>调用时机红线</b>：必须在所有「按 stream 索引 pick(s.hand)」之后调用——
     * bell/hook 等用 {@code stream.pick(hand)} 依手牌顺序取元素，整理顺序若先于 pick 发生会与原版分流。
     * 因此只在 startRound（bell 抽取之后）/ playHand（updateBellCard 之后）/ discard / refillHand / Consumables.use 末尾调用。
     */
    public static void sortHand(RunState s) {
        s.hand.sort((a, b) -> {
            int ra = a.isStone() ? -1 : a.rank();
            int rb = b.isStone() ? -1 : b.rank();
            int primary = Integer.compare(rb, ra); // rankOrder(b)-rankOrder(a)：点数降序
            if (primary != 0) return primary;
            int sa = a.isStone() ? 9 : a.suit();
            int sb = b.isStone() ? 9 : b.suit();
            return Integer.compare(sa, sb);        // suitOrder(a)-suitOrder(b)：花色升序
        });
    }

    private static boolean hasChicot(RunState s) {
        for (int i = 0; i < s.jokers.size(); i++) {
            JokerInstance j = s.jokers.get(i);
            if (j.def.key().equals("chicot") && !j.debuff) return true;
        }
        return false;
    }

    /** 当前生效的 Boss key（用于效果分支；chicot/已消除时不生效）。 */
    private static String effectBk(RunState s) {
        if (s.blindType != Data.BlindType.BOSS) return null;
        if (hasChicot(s) || s.bossDisabled) return null;
        return s.bossQueue.isEmpty() ? null : s.bossQueue.get(0);
    }

    private static boolean isFaceCard(RunState s, Card c) {
        return s.isFace(c);
    }

    /** 翠绿铃：强制牌离开手牌后重新指定一张（防软锁）。对齐 REF engine.js:480-487。 */
    private static void updateBellCard(RunState s) {
        // 对齐 REF：只判断 bk!=bell 就 return；bellCardId==null 时不短路（some 返回 false →
        // 重新 pick），保证与 REF 的 stream 消耗完全一致。
        if (!"bell".equals(effectBk(s))) return;
        boolean still = false;
        for (int i = 0; i < s.hand.size(); i++) {
            if (s.hand.get(i).id() == s.bellCardId) { still = true; break; }
        }
        if (!still) {
            s.bellCardId = !s.hand.isEmpty() ? s.stream("bell").pick(s.hand).id() : null;
        }
    }

    public static HandEval.Result evaluateHand(RunState s, List<Card> cards) {
        return HandEval.evaluate(s, cards);
    }

    // ================= 出牌计分 =================

    public static PlayResult playHand(RunState s, List<Integer> cardIds) {
        if (s.phase != Phase.ROUND) return PlayResult.err(Lang.t("err.not_in_round"));
        if (s.handsLeft <= 0) return PlayResult.err(Lang.t("err.no_hands_left"));
        if (cardIds == null || cardIds.size() < 1 || cardIds.size() > 5) return PlayResult.err(Lang.t("err.select_1_to_5"));
        // P4 性能：≤5 个 id 的查重/包含改为 int 数组两两比较——原 HashSet 构造与
        // List<Integer>.contains 每次装箱（card id 超出 Integer 缓存区间，逐个分配）。
        int[] ids = new int[cardIds.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = cardIds.get(i);
        for (int i = 0; i < ids.length; i++) {
            for (int j = i + 1; j < ids.length; j++) {
                if (ids[i] == ids[j]) return PlayResult.err(Lang.t("err.duplicate_card"));
            }
        }

        // 保持手牌顺序（从左到右结算）：按手牌位置单趟选取，等价于原 indexOf 稳定排序
        List<Card> cards = new ArrayList<>(ids.length);
        for (Card hc : s.hand) {
            for (int id : ids) {
                if (hc.id() == id) {
                    cards.add(hc);
                    break;
                }
            }
        }
        if (cards.size() != ids.length) return PlayResult.err(Lang.t("err.bad_hand_cards"));

        // Boss 出牌限制（psychic/bell；eye/mouth 需先判定牌型，见下）
        String bk = effectBk(s);
        if ("psychic".equals(bk) && cards.size() != 5) return PlayResult.err(Lang.t("err.psychic"));
        if (s.mods.must5 && cards.size() != 5) return PlayResult.err(Lang.t("err.must5"));
        if ("bell".equals(bk) && s.bellCardId != null && !containsId(ids, s.bellCardId)) {
            return PlayResult.err(Lang.t("err.cerulean_bell"));
        }

        HandEval.Result evalRes = HandEval.evaluate(s, cards);
        Data.HandType type = evalRes.type;

        if ("eye".equals(bk) && s.playedTypesThisRound.contains(type)) {
            return PlayResult.err(Lang.t("err.eye", type.displayName()));
        }
        if ("mouth".equals(bk) && !s.playedTypesThisRound.isEmpty() && s.playedTypesThisRound.get(0) != type) {
            return PlayResult.err(Lang.t("err.mouth", s.playedTypesThisRound.get(0).displayName()));
        }

        s.bossTriggeredThisHand = false;

        // ---------- 计分 ----------
        List<String> events = new ArrayList<>();
        int lvl = s.handLevel(type);
        Data.HandType hd = type;
        double chips = hd.chips + (long) (lvl - 1) * hd.lchips;
        double mult = hd.mult + (long) (lvl - 1) * hd.lmult;

        // flint（燧石）：基础筹码/倍率减半（须在 ctx 构造前改 chips/mult）
        if ("flint".equals(bk)) {
            chips = Math.max(1, Math.round(chips / 2.0));
            mult = Math.max(1, Math.round(mult / 2.0));
            events.add(Lang.t("ev.flint"));
            s.bossTriggeredThisHand = true;
        }

        List<Card> scoringCards = evalRes.scoring;
        List<Card> heldCards = new ArrayList<>();
        for (int i = 0; i < s.hand.size(); i++) {
            Card c = s.hand.get(i);
            if (!containsId(ids, c.id())) heldCards.add(c);
        }
        ScoreContext ctx = new ScoreContext(s, type, chips, mult, cards, heldCards, events);
        ctx.handContainsSet = evalRes.contains; // R130 contains 口径注入
        ctx.scoredCards = evalRes.scoring; // R130 计分牌口径注入
        // P10 性能：activeJokers 走池化缓冲（深度池，嵌套安全）；计分区结束即归还
        List<JokerInstance> activeJokers = s.acquireJokerBuffer();
        try {
            for (int i = 0; i < s.jokers.size(); i++) {
                JokerInstance j = s.jokers.get(i);
                if (!j.debuff && !j.debuffHand) activeJokers.add(j);
            }

            // 绯红之心：随机禁用一张小丑（本次出牌内）
            if ("heart".equals(bk) && !activeJokers.isEmpty()) {
                JokerInstance v = s.stream("heart").pick(activeJokers);
                v.debuffHand = true;
                events.add(Lang.t("ev.crimson_heart", v.def.displayName()));
                s.bossTriggeredThisHand = true;
            }

            // R130/R132 真版【计分前】阶段（每手恰一次——R130 曾误插逐卡循环内致 space 每卡掷骰）：
            // Space 升级当手即生效（Space Wiki："triggered before scoring"）；Obelisk 先重置再计分
            //（Obelisk Wiki："resets before the hand is scored"），严格唯一最常用才重置（并列安全）。
            List<JokerInstance> preSnap = s.acquireJokerSnap(); // P10：池化快照
            try {
                for (int i = 0; i < preSnap.size(); i++) {
                    JokerInstance pj = preSnap.get(i);
                    if (pj.debuff || !s.jokers.contains(pj)) continue;
                    if (pj.def.key().equals("space") && s.stream("space").chance(0.25)) {
                        s.levelUpHand(type, 1);
                        s.msg(Lang.t("msg.space_joker", type.displayName()));
                    } else if (pj.def.key().equals("obelisk")) {
                        Data.HandType strict = strictMostPlayed(s);
                        if (strict == type) {
                            pj.extra.put("x", 0.0);
                            pj.extra.put("obNoGain", Boolean.TRUE); // R133：重置手不获增量（"consecutive
                            // ... without playing your most played"——该手不计入连续），onPlayHand 据此跳过
                            s.msg(Lang.t("msg.obelisk_reset"));
                        }
                    }
                }
            } finally {
                s.releaseJokerBuffer();
            }

            // 1) 打出牌逐张计分（含重新触发）
            for (int ci = 0; ci < cards.size(); ci++) {
                Card card = cards.get(ci);
                card.setFacedown(false);
                boolean isScoring = scoringCards.contains(card);
                if (!isScoring) continue;
                ctx.scoreIndex = ci;

                int retriggers = 0;
                if (card.isSeal(Data.Seal.RED)) retriggers += 1;
                for (int ji = 0; ji < activeJokers.size(); ji++) {
                    JokerInstance src = resolveCopy(activeJokers, ji);
                    if (src != null && !activeJokers.get(ji).debuffHand) {
                        ctx.joker = src;
                        retriggers += src.def.retrigger(card, ctx);
                    }
                }
                int times = card.debuff() ? 0 : (1 + retriggers);
                for (int t = 0; t < times; t++) {
                    scoreOneCard(s, ctx, card);
                    for (int ji = 0; ji < activeJokers.size(); ji++) {
                        JokerInstance src = resolveCopy(activeJokers, ji);
                        if (src != null && !activeJokers.get(ji).debuffHand) {
                            ctx.joker = src;
                            src.def.onScoreCard(ctx, card);
                        }
                    }
                }
                // 玻璃牌破碎
                if (card.isEnh(Data.Enhancement.GLASS) && !card.debuff()) {
                    double p = s.mods.glassDouble ? 0.5 : 0.25;
                    if (s.stream("glass").chance(p)) {
                        card.setBroken(true);
                        events.add(Lang.t("ev.glass_shattered"));
                        for (int ji = 0; ji < activeJokers.size(); ji++) {
                            JokerInstance j = activeJokers.get(ji);
                            j.def.onGlassBreak(s, j);
                        }
                    }
                }
            }

            // 2) 持有牌效果（钢铁 + onHeld；哑剧重触发 + 红蜡封同样重触发手中效果——R129 真版）
            boolean mime = Boolean.TRUE.equals(s.flags.get("mimeRetrigger"));
            for (int hi = 0; hi < heldCards.size(); hi++) {
                Card card = heldCards.get(hi);
                if (card.debuff()) continue;
                int heldRepeat = 1 + (mime ? 1 : 0) + (card.isSeal(Data.Seal.RED) ? 1 : 0);
                for (int rep = 0; rep < heldRepeat; rep++) {
                    if (card.isEnh(Data.Enhancement.STEEL)) ctx.xMult(1.5);
                    for (int ji = 0; ji < activeJokers.size(); ji++) {
                        JokerInstance src = resolveCopy(activeJokers, ji);
                        if (src != null && !activeJokers.get(ji).debuffHand) {
                            ctx.joker = src;
                            src.def.onHeld(ctx, card);
                        }
                    }
                }
            }

            // 3) 独立小丑结算（含蓝图/头脑风暴）
            for (int ji = 0; ji < activeJokers.size(); ji++) {
                JokerInstance j = activeJokers.get(ji);
                if (j.debuffHand) continue;
                applyJokerScore(s, ctx, j, ji, activeJokers);
            }

            // 天文台（observatory 优惠券）：消耗品区的星球牌使其对应牌型 ×1.5
            if (s.vouchers.contains("observatory")) {
                for (int ci = 0; ci < s.consumables.size(); ci++) {
                    cn.quotidietium.balatro.engine.Consumable con = s.consumables.get(ci);
                    if ("planet".equals(con.kind)) {
                        Data.Planet p = Data.Planet.byKey(con.key);
                        if (p.hand == type) { ctx.xMult(1.5); events.add(Lang.t("ev.observatory")); }
                    }
                }
            }

        } finally {
            s.releaseJokerBuffer();
        }

        // 等离子牌组：平衡
        if (s.mods.plasma) {
            double avg = (ctx.chips + ctx.mult) / 2.0;
            ctx.chips = Math.round(avg);
            ctx.mult = Math.round(avg);
        }

        // 富者愈富（真版）：单手筹码不得超当前金钱（R123）
        if (s.mods.chipsCapByMoney) {
            ctx.chips = Math.min(ctx.chips, Math.max(0, s.money));
        }

        long chipsR = Math.round(ctx.chips);
        double multR = Math.round(ctx.mult * 100.0) / 100.0;
        long score = Math.round(chipsR * multR);
        // 饱和累加：无尽模式极端 build 单手可逼近 long 上限，直接 += 会环绕成负数
        // （负分永远达不到目标分 → 必败软锁），对齐 JS double 不环绕语义
        s.roundScore = RunState.satAdd(s.roundScore, score);

        // ---------- 出牌后处理 ----------
        s.handsLeft--;
        s.handsPlayedThisRound++;
        s.statsHandsPlayed++;
        s.handPlayedCount.merge(type, 1, Integer::sum);
        s.playedTypesThisRound.add(type);
        for (int i = 0; i < cards.size(); i++) s.playedThisAnte.add(cards.get(i).id());

        // 孤注一掷（真版）：计分后的牌失效（R123）——牌入弃牌堆时带 debuff，
        // 换盲注时 startRound 的 fullDeck 全清兜底恢复
        if (s.mods.playedDebuff) {
            for (int i = 0; i < cards.size(); i++) cards.get(i).setDebuff(true);
        }

        // Boss：公牛（最常用牌型→金钱归零）/牙齿（每牌-$1）/手臂（牌型降级）
        if ("ox".equals(bk)) {
            Data.HandType most = s.mostPlayedType();
            if (most == type) { s.money = 0; events.add(Lang.t("ev.bull_boss")); s.bossTriggeredThisHand = true; }
        }
        if ("tooth".equals(bk)) {
            s.money = Math.max(0, s.money - cards.size());
            events.add(Lang.t("ev.tooth", cards.size()));
            s.bossTriggeredThisHand = true;
        }
        if ("arm".equals(bk) && s.handLevel(type) > 1) {
            s.levelUpHand(type, -1);
            events.add(Lang.t("ev.arm", type.displayName()));
            s.bossTriggeredThisHand = true;
        }

        // 移除打出的牌（破碎的玻璃牌从牌组销毁，其余进弃牌堆）
        for (int i = s.hand.size() - 1; i >= 0; i--) {
            if (containsId(ids, s.hand.get(i).id())) s.hand.remove(i);
        }
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            if (c.isBroken()) s.removeCardFromDeck(c);
            else s.discardPile.add(c);
        }

        // 小丑 onPlayHand
        PlayHandInfo info = new PlayHandInfo(s, type, cards, scoringCards, evalRes.contains); // R130 contains
        List<JokerInstance> jokersSnap = s.acquireJokerSnap(); // P10：池化快照
        try {
            for (int i = 0; i < jokersSnap.size(); i++) {
                JokerInstance j = jokersSnap.get(i);
                if (!j.debuff && !j.debuffHand && s.jokers.contains(j)) {
                    j.def.onPlayHand(s, info, j); // 传触发实例：多副本累积器各自计数（R111）
                }
            }
        } finally {
            s.releaseJokerBuffer();
        }

        // 恢复 debuffHand
        for (int i = 0; i < s.jokers.size(); i++) s.jokers.get(i).debuffHand = false;

        // Boss：钩子（出牌后随机弃 2 张）——R129 真版：被动弃牌同样触发紫蜡封
        // （Seals Wiki："when discarded (either player or automatic discards)"；REF 漏触发为 REF bug）
        if ("hook".equals(bk) && !s.hand.isEmpty()) {
            Rng.Stream st = s.stream("hook");
            for (int i = 0; i < 2 && !s.hand.isEmpty(); i++) {
                Card v = st.pick(s.hand);
                s.hand.remove(v);
                s.discardPile.add(v);
                if (v.isSeal(Data.Seal.PURPLE) && !v.debuff()) {
                    Data.Tarot t40 = s.stream("consumable").pick(Data.TAROTS);
                    if (s.addConsumableKey("tarot", t40.key)) s.msg(Lang.t("msg.purple_seal", t40.displayName()));
                }
                // R130 真版：被动弃牌同样触发小丑 onDiscard（Green Wiki："whether by the
                // player or game mechanics"；对齐 burnt 的首弃守卫/faceless/castle 等同口径）
                List<JokerInstance> hookSnap = s.acquireJokerSnap(); // P10：池化快照
                try {
                    for (int hi = 0; hi < hookSnap.size(); hi++) {
                        JokerInstance hj = hookSnap.get(hi);
                        // R134 真版：Burnt 不被钩子强弃激活（Burnt Wiki："The Hook's forced discards
                        // won't activate it"）；Green 等其余 onDiscard 照常（R132 已证）
                        if (!hj.debuff && s.jokers.contains(hj) && !hj.def.key().equals("burnt")) {
                            hj.def.onDiscard(s, List.of(v), hj);
                        }
                    }
                } finally {
                    s.releaseJokerBuffer();
                }
            }
            events.add(Lang.t("ev.hook"));
            s.bossTriggeredThisHand = true;
        }

        drawUpTo(s);
        updateBellCard(s);
        sortHand(s); // 补牌后整理（updateBellCard 的 stream.pick 已发生于此之前，安全）

        // 胜负判定
        boolean won = s.roundScore >= s.blindTarget;
        if (won) {
            endRound(s, true);
            return PlayResult.ok(score, type, events, true, false);
        }
        if (s.handsLeft <= 0) {
            // 骨头先生免死：得分 ≥ 目标 25% 时销毁自身并判回合通过
            JokerInstance bones = null;
            for (int i = 0; i < s.jokers.size(); i++) {
                JokerInstance j = s.jokers.get(i);
                if (j.def.key().equals("bones") && !j.debuff) { bones = j; break; }
            }
            if (bones != null && s.roundScore >= Math.round(s.blindTarget * 0.25)) {
                s.destroyJoker(bones, Lang.t("msg.mr_bones"));
                endRound(s, true);
                return PlayResult.ok(score, type, events, true, false);
            }
            loseRun(s);
            return PlayResult.ok(score, type, events, false, true);
        }
        return PlayResult.ok(score, type, events, false, false);
    }

    private static void scoreOneCard(RunState s, ScoreContext ctx, Card card) {
        if (card.isEnh(Data.Enhancement.STONE)) ctx.addChips(50); // P13：位段谓词
        else ctx.addChips(Data.rankChips(card.rank()));
        ctx.addChips(card.chipBonus());

        if (card.isEnh(Data.Enhancement.BONUS)) ctx.addChips(30);
        if (card.isEnh(Data.Enhancement.MULT)) ctx.addMult(4);
        if (card.isEnh(Data.Enhancement.GLASS)) ctx.xMult(2);
        if (card.isEnh(Data.Enhancement.LUCKY)) {
            Rng.Stream st = s.stream("lucky");
            double p5 = 1.0 / 5, p15 = 1.0 / 15;
            if (Boolean.TRUE.equals(s.flags.get("doubleProb"))) { p5 *= 2; p15 *= 2; }
            if (st.chance(p5)) { ctx.addMult(20); triggerLuckyCat(s); }
            if (st.chance(p15)) { ctx.dollars(20); triggerLuckyCat(s); }
        }

        if (card.isEdition(Data.Edition.FOIL)) ctx.addChips(50);
        if (card.isEdition(Data.Edition.HOLO)) ctx.addMult(10);
        if (card.isEdition(Data.Edition.POLY)) ctx.xMult(1.5);

        if (card.isSeal(Data.Seal.GOLD)) ctx.dollars(3);
    }

    private static void triggerLuckyCat(RunState s) {
        List<JokerInstance> snap = s.acquireJokerSnap(); // P10：池化快照
        try {
            for (int i = 0; i < snap.size(); i++) {
                JokerInstance j = snap.get(i);
                if (!j.debuff) j.def.onLucky(s, j);
            }
        } finally {
            s.releaseJokerBuffer();
        }
    }

    /** 蓝图/头脑风暴复制解析：返回实际生效的小丑实例（0.1.0 无复制类，恒返回自身）。 */
    private static JokerInstance resolveCopy(List<JokerInstance> active, int ji) {
        JokerInstance j = active.get(ji);
        if (j.def.blueprint()) {
            JokerInstance right = ji < active.size() - 1 ? active.get(ji + 1) : null;
            if (right == null || right.def.blueprint() || right.def.brainstorm()) return null;
            return right;
        }
        if (j.def.brainstorm()) {
            JokerInstance first = active.get(0);
            if (first == null || first == j || first.def.blueprint() || first.def.brainstorm()) return null;
            return first;
        }
        return j;
    }

    private static void applyJokerScore(RunState s, ScoreContext ctx, JokerInstance joker, int idx, List<JokerInstance> active) {
        JokerInstance src = resolveCopy(active, idx);
        if (src == null) return;
        ctx.joker = src;
        src.def.onScore(ctx);
        // 小丑自身版本加成
        if (joker.edition == Data.Edition.FOIL) ctx.addChips(50);
        if (joker.edition == Data.Edition.HOLO) ctx.addMult(10);
        if (joker.edition == Data.Edition.POLY) ctx.xMult(1.5);
    }

    // ================= 弃牌 =================

    public static PlayResult discard(RunState s, List<Integer> cardIds) {
        if (s.phase != Phase.ROUND) return PlayResult.err(Lang.t("err.not_in_round"));
        if (s.discardsLeft <= 0) return PlayResult.err(Lang.t("err.no_discards_left"));
        // 金针（真版）：每次弃牌花费 $1（信用卡允许负余额，R123）
        if (s.mods.discardCost) {
            long credit = s.flags != null && s.flags.get("credit") instanceof Number
                    ? ((Number) s.flags.get("credit")).longValue() : 0;
            if (RunState.satAdd(s.money, credit) < 1) {
                return PlayResult.err(Lang.t("err.discard_costs"));
            }
            s.money -= 1;
        }
        if (cardIds == null || cardIds.size() < 1 || cardIds.size() > 5) return PlayResult.err(Lang.t("err.select_1_to_5"));
        // P4 性能：int 数组查重（同 playHand，去 HashSet 装箱）
        int[] ids = new int[cardIds.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = cardIds.get(i);
        for (int i = 0; i < ids.length; i++) {
            for (int j = i + 1; j < ids.length; j++) {
                if (ids[i] == ids[j]) return PlayResult.err(Lang.t("err.duplicate_card"));
            }
        }

        // 注意：cards 保持 cardIds 顺序（与原实现一致）——紫蜡封按此顺序消耗流、
        // onDiscard 钩子看到此顺序，不得改为手牌顺序（种子复现敏感）。
        List<Card> cards = new ArrayList<>(ids.length);
        for (int id : ids) {
            Card c = findInHand(s, id);
            if (c == null) return PlayResult.err(Lang.t("err.bad_hand_cards"));
            cards.add(c);
        }

        s.discardsLeft--;
        s.discardsUsedThisRound++;
        s.usedDiscardThisRound = true;

        for (int i = s.hand.size() - 1; i >= 0; i--) {
            if (containsId(ids, s.hand.get(i).id())) s.hand.remove(i);
        }
        for (Card c : cards) {
            c.setFacedown(false);
            // 紫色蜡封 → 塔罗牌（对齐 engine.js discard）
            if (c.isSeal(Data.Seal.PURPLE) && !c.debuff()) {
                Data.Tarot t = s.stream("consumable").pick(Data.TAROTS);
                if (s.addConsumableKey("tarot", t.key)) s.msg(Lang.t("msg.purple_seal", t.displayName()));
            }
            s.discardPile.add(c);
        }

        List<JokerInstance> snap = s.acquireJokerSnap(); // P10：池化快照
        try {
            for (int i = 0; i < snap.size(); i++) {
                JokerInstance j = snap.get(i);
                if (!j.debuff && s.jokers.contains(j)) {
                    j.def.onDiscard(s, cards, j);
                }
            }
        } finally {
            s.releaseJokerBuffer();
        }

        drawUpTo(s);
        updateBellCard(s);
        sortHand(s);
        return PlayResult.okDiscard();
    }

    // ================= 回合结束 / 推进 =================

    private static void endRound(RunState s, boolean wonRound) {
        if (!wonRound) { loseRun(s); return; }

        long gain = 0;
        List<String> detail = new ArrayList<>();

        // 盲注奖励金
        long reward = s.blindType.reward;
        // R126 对齐真版：Showdown Boss（底注 8 的 5 终结者）奖励 $8（Boss_Blinds/Violet Vessel Wiki）
        if (s.blindType == Data.BlindType.BOSS && !s.bossQueue.isEmpty()
                && Data.FINISHERS.contains(s.bossQueue.get(0))) {
            reward = 8;
        }
        if (s.mods.redStake && s.blindType == Data.BlindType.SMALL) reward = 0;
        if (s.mods.smallBigNoReward && s.blindType != Data.BlindType.BOSS) reward = 0; // R123 残酷（真版）
        if (s.mods.smallBigRewardHalf && s.blindType != Data.BlindType.BOSS) reward = (long) Math.ceil(reward / 2.0);
        if (s.mods.rewardMult != 0) reward *= s.mods.rewardMult;
        if (s.mods.noBlindReward) reward = 0; // 煎蛋卷（真版）：所有盲注无奖励金（R102 对齐真版）
        gain += reward;
        if (reward > 0) detail.add(Lang.t("pay.blind_reward", reward));

        // 剩余出牌
        long handPay = s.handsLeft;
        if (s.mods.minRewardMoney != 0 && s.money < s.mods.minRewardMoney) handPay = 0;
        if (s.mods.noHandPay) handPay = 0; // 煎蛋卷（真版）：剩余出牌不再产生金钱（R102 对齐真版）
        if ("green".equals(s.deckKey)) {
            long g = 2L * s.handsLeft + s.discardsLeft;
            gain += g;
            if (g > 0) detail.add(Lang.t("pay.green_deck", g));
        } else if (handPay > 0) {
            gain += handPay;
            detail.add(Lang.t("pay.hands_left", handPay));
        }

        // 利息
        if (!s.mods.noInterest && s.money > 0) {
            int rate = s.mods.doubleInterest ? 2 : 1;
            long interest = Math.min(s.interestCap, (s.money / 5) * rate);
            if (interest > 0) { gain += interest; detail.add(Lang.t("pay.interest", interest)); }
        }

        // 黄金牌（手中）
        for (int i = 0; i < s.hand.size(); i++) {
            Card c = s.hand.get(i);
            if (c.isEnh(Data.Enhancement.GOLD) && !c.debuff()) gain += 3;
        }
        // 蓝色蜡封（手中）→ 对应星球牌（对齐 engine.js endRound）
        for (int i = 0; i < s.hand.size(); i++) {
            Card c = s.hand.get(i);
            if (c.isSeal(Data.Seal.BLUE) && !c.debuff()) {
                Data.HandType lastType = s.playedTypesThisRound.isEmpty()
                        ? Data.HandType.HIGH : s.playedTypesThisRound.get(s.playedTypesThisRound.size() - 1);
                Data.Planet p = Data.Planet.byHand(lastType);
                if (p != null && s.addConsumableKey("planet", p.key)) detail.add(Lang.t("pay.blue_seal", p.displayName()));
            }
        }

        // 小丑回合结束钩子（payout 可能极大——奔月额外利息 = money/5，复利指数增长；饱和累加防环绕）
        List<JokerInstance> snap = s.acquireJokerSnap(); // P10：池化快照
        try {
            for (int i = 0; i < snap.size(); i++) {
                JokerInstance j = snap.get(i);
                if (j.debuff || !s.jokers.contains(j)) continue;
                long g = j.def.onRoundEnd(s, j);
                if (g > 0) { gain = RunState.satAdd(gain, g); detail.add(j.def.displayName() + " +$" + g); }
            }
        } finally {
            s.releaseJokerBuffer();
        }

        // 租赁小丑：每回合 -$3（在 money += gain 之前扣）
        for (int i = 0; i < s.jokers.size(); i++) {
            JokerInstance j = s.jokers.get(i);
            if (j.rental) { s.money -= 3; detail.add(Lang.t("pay.rental", j.def.displayName())); }
        }
        // 易腐小丑：倒计时，归零后失效
        for (int ji = s.jokers.size() - 1; ji >= 0; ji--) {
            JokerInstance j = s.jokers.get(ji);
            if (j.perishable) {
                j.perishCount--;
                if (j.perishCount <= 0) j.debuff = true;
            }
        }

        s.statsDiscardsUnused += s.discardsLeft;
        s.gainMoney(gain);

        // 投资标签：击败 Boss 后 +$25
        if (s.blindType == Data.BlindType.BOSS && s.nextShop.get("invest") != null) {
            s.gainMoney(25);
            detail.add(Lang.t("pay.investment_tag"));
            s.nextShop.remove("invest");
        }

        // 击败 Boss
        if (s.blindType == Data.BlindType.BOSS) {
            List<JokerInstance> bossSnap = s.acquireJokerSnap(); // P10：池化快照
            try {
                for (int i = 0; i < bossSnap.size(); i++) {
                    JokerInstance j = bossSnap.get(i);
                    if (!j.debuff && s.jokers.contains(j)) j.def.onBossDefeated(s, j);
                }
            } finally {
                s.releaseJokerBuffer();
            }
            // 浮雕牌组(anaglyph)：每击败一个 Boss 盲注获得一个翻倍标签（对齐 engine.js）
            if ("anaglyph".equals(s.deckKey)) gainTag(s, "double");
            // 刻板印象（真版）：击败第 4 底注 Boss 后全员永恒 + 槽位归零（R123）
            if (s.mods.typecastTrigger && s.ante == 4) {
                for (int i = 0; i < s.jokers.size(); i++) s.jokers.get(i).eternal = true;
                s.jokerSlots = 0;
                s.msg(Lang.t("msg.stereotype"));
            }
            s.bossQueue.remove(0);
            if (!s.bossQueue.isEmpty()) {
                // 双 Boss 挑战（对齐 engine.js）：无商店间隔，立即接第二个 Boss
                s.phase = Phase.BLIND_SELECT;
                s.nextBlind = "boss";
                s.msg(Lang.t("msg.second_boss_plain", bossDef(s).displayName()));
                return;
            }
        }

        s.msg(Lang.t("msg.round_end", String.join(Lang.t("msg.round_end_sep"), detail)));
        // 胜出盲注后进入商店（0.2.0）
        cn.quotidietium.balatro.engine.shop.Shop.openShop(s);
    }

    /** 离开商店推进到下一盲注（玩家在商店阶段调用）。返回 false 表示当前不在商店。 */
    public static boolean nextRound(RunState s) {
        if (s.phase != Phase.SHOP) return false;
        if (s.mods.inflation) s.inflation++;
        if (s.blindType == Data.BlindType.SMALL) {
            s.nextBlind = "big";
            s.phase = Phase.BLIND_SELECT;
        } else if (s.blindType == Data.BlindType.BIG) {
            s.nextBlind = "boss";
            s.phase = Phase.BLIND_SELECT;
        } else {
            // 击败 Boss → 下一底注或胜利
            if (s.ante >= 8 && !s.endless) {
                s.phase = Phase.END;
                s.won = true;
                s.endlessPending = true;
                return true;
            }
            s.ante++;
            startAnte(s);
        }
        return true;
    }

        /** R130 真版：严格唯一最常用牌型（并列返回 null——Obelisk 并列安全）。 */
    static Data.HandType strictMostPlayed(RunState s) {
        Data.HandType best = null; int bestN = 0; boolean tie = false;
        for (Map.Entry<Data.HandType, Integer> e : s.handPlayedCount.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); tie = false; }
            else if (e.getValue() == bestN && bestN > 0) tie = true;
        }
        return tie ? null : best;
    }

/** 进入无尽模式（通关后可选）。 */
    public static boolean continueEndless(RunState s) {
        if (!s.endlessPending) return false;
        s.endless = true;
        s.endlessPending = false;
        s.won = false; // 重置通关标记：通关(ante 8)的 finishRun 已在 nextRound 触发一次，
                       // 此后无尽模式不再视为已通关，避免 play()/nextRound 重复触发 finishRun。
        s.phase = Phase.BLIND_SELECT;
        s.ante++;
        startAnte(s);
        return true;
    }

    private static void loseRun(RunState s) {
        s.phase = Phase.END;
        s.lost = true;
        s.msg(Lang.t("msg.run_lost"));
    }

    // ================= 辅助 =================

    private static Card findInHand(RunState s, int id) {
        for (Card c : s.hand) if (c.id() == id) return c;
        return null;
    }

    /** P4 性能：出牌/弃牌的 id 包含检查（≤5 个元素；替代 List&lt;Integer&gt;.contains 的逐次装箱）。 */
    private static boolean containsId(int[] ids, int v) {
        for (int id : ids) if (id == v) return true;
        return false;
    }

    private static int intFlag(Map<String, Object> f, String key) {
        Object v = f.get(key);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    // ================= 出牌结果 =================

    public static final class PlayResult {
        public final boolean ok;
        public final String err;
        public final long score;
        public final Data.HandType type;
        public final List<String> events;
        public final boolean won;
        public final boolean lost;

        private PlayResult(boolean ok, String err, long score, Data.HandType type, List<String> events, boolean won, boolean lost) {
            this.ok = ok;
            this.err = err;
            this.score = score;
            this.type = type;
            this.events = events;
            this.won = won;
            this.lost = lost;
        }

        public static PlayResult err(String msg) {
            return new PlayResult(false, msg, 0, null, null, false, false);
        }

        public static PlayResult ok(long score, Data.HandType type, List<String> events, boolean won, boolean lost) {
            return new PlayResult(true, null, score, type, events, won, lost);
        }

        public static PlayResult okDiscard() {
            return new PlayResult(true, null, 0, null, new ArrayList<>(), false, false);
        }
    }
}
