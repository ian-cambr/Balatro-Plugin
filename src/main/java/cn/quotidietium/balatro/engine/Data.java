package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.i18n.Lang;
import java.util.HashMap;
import java.util.Map;

/**
 * 静态数据表，移植自 {@code REF/balatro/js/data.js}。
 *
 * <p>0.1.0 范围：花色、点数（名称/牌面筹码）、13 种牌型（基础筹码/倍率 + 每级成长 + 顺序）、
 * 盲注目标分（底注表 + 倍率 + 奖励）。其余数据表（牌组/赌注/Boss/标签/优惠券/塔罗/星球/幻灵/
 * 增强/版本/蜡封/补充包/挑战/稀有度）随后续版本补齐。
 *
 * <p>数值类型约定（与 balatro 的 JS Number 对齐，整数域优先 long）：
 * <ul>
 *   <li>chips/mult/money/score/target 用 {@code long}（整数域，正常对局内远在 long 范围内）。</li>
 *   <li>深无尽模式（底注 16+，目标分 8.6e20 超 long 上限）不可达，{@link #blindBase} 对超出表/超 long 的值钳制为 {@link Long#MAX_VALUE}。</li>
 * </ul>
 */
public final class Data {
    private Data() {
    }

    // ================= 枚举缓存列表（P4 性能） =================
    // values() 每次调用都克隆内部数组，且调用点多接 List.of(...) 再包一层——
    // 商店/补充包/紫蜡封/消耗品生成等热路径逐次分配。此处一次性缓存为不可变列表
    // （顺序=枚举声明序，与 values() 一致；不可变避免第三方污染）。
    public static final java.util.List<HandType> HAND_TYPES = java.util.List.of(HandType.values());
    public static final java.util.List<Enhancement> ENHANCEMENTS = java.util.List.of(Enhancement.values());
    public static final java.util.List<Seal> SEALS = java.util.List.of(Seal.values());
    public static final java.util.List<Tarot> TAROTS = java.util.List.of(Tarot.values());
    public static final java.util.List<Planet> PLANETS = java.util.List.of(Planet.values());
    public static final java.util.List<Spectral> SPECTRALS = java.util.List.of(Spectral.values());

    // ================= 花色 =================
    // 0 黑桃 1 红桃 2 梅花 3 方块
    public enum Suit {
        SPADE(0, "spade", "♠", "dark"),
        HEART(1, "heart", "♥", "red"),
        CLUB(2, "club", "♣", "dark"),
        DIAMOND(3, "diamond", "♦", "red");

        public final int index;
        public final String key;
        public final String symbol;
        public final String color;

        Suit(int index, String key, String symbol, String color) {
            this.index = index;
            this.key = key;
            this.symbol = symbol;
            this.color = color;
        }

        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() {
            return Lang.t("suit." + key);
        }

        public boolean isRed() {
            return "red".equals(color);
        }

        private static final Map<String, Suit> BY_KEY = new HashMap<>();
        /** P6 性能：byIndex 原为 values() 线性扫（每次克隆数组）——渲染/消息路径高频。 */
        private static final Suit[] BY_INDEX = values();
        static {
            for (Suit s : BY_INDEX) BY_KEY.put(s.key, s);
        }

        public static Suit byIndex(int index) {
            if (index < 0 || index >= BY_INDEX.length) {
                throw new IllegalArgumentException("suit index out of range: " + index);
            }
            return BY_INDEX[index];
        }

        public static Suit byKey(String key) {
            Suit s = BY_KEY.get(key);
            if (s == null) throw new IllegalArgumentException("unknown suit: " + key);
            return s;
        }
    }

    // ================= 点数 ================= 2..14（11=J 12=Q 13=K 14=A）
    /** 点数显示名。 */
    public static String rankName(int r) {
        if (r >= 2 && r <= 10) return Integer.toString(r);
        switch (r) {
            case 11: return "J";
            case 12: return "Q";
            case 13: return "K";
            case 14: return "A";
            default: return "?";
        }
    }

    /** 牌面筹码：2~10 面值，J/Q/K=10，A=11。 */
    public static int rankChips(int r) {
        if (r >= 2 && r <= 10) return r;
        if (r == 14) return 11;
        return 10;
    }

    // ================= 牌型（13 种） =================
    public enum HandType {
        HIGH("high", 5, 1, 10, 1, 1),
        PAIR("pair", 10, 2, 15, 1, 2),
        TWOPAIR("twopair", 20, 2, 20, 1, 3),
        THREE("three", 30, 3, 20, 2, 4),
        STRAIGHT("straight", 30, 4, 30, 3, 5),
        FLUSH("flush", 35, 4, 15, 2, 6),
        FULL("full", 40, 4, 25, 2, 7),
        FOUR("four", 60, 7, 30, 3, 8),
        SFLUSH("sflush", 100, 8, 40, 4, 9),
        ROYAL("royal", 100, 8, 40, 4, 10),
        FIVE("five", 120, 12, 35, 3, 11),
        FHOUSE("fhouse", 140, 14, 40, 4, 12),
        FFIVE("ffive", 160, 16, 50, 5, 13);

        public final String key;
        public final int chips;   // 1 级基础筹码
        public final int mult;    // 1 级基础倍率
        public final int lchips;  // 每升 1 级 +筹码
        public final int lmult;   // 每升 1 级 +倍率
        public final int order;   // 同级判定时的排序（大者胜）

        HandType(String key, int chips, int mult, int lchips, int lmult, int order) {
            this.key = key;
            this.chips = chips;
            this.mult = mult;
            this.lchips = lchips;
            this.lmult = lmult;
            this.order = order;
        }

        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() {
            return Lang.t("hand." + key);
        }

        private static final Map<String, HandType> BY_KEY = new HashMap<>();
        static {
            for (HandType h : values()) BY_KEY.put(h.key, h);
        }

        public static HandType byKey(String key) {
            HandType h = BY_KEY.get(key);
            if (h == null) throw new IllegalArgumentException("unknown hand: " + key);
            return h;
        }

        /** 升级后的基础筹码（level >= 1）：chips + lchips*(level-1)。 */
        public long chipsAtLevel(int level) {
            return (long) chips + (long) lchips * Math.max(0, level - 1);
        }

        /** 升级后的基础倍率（level >= 1）：mult + lmult*(level-1)。 */
        public long multAtLevel(int level) {
            return (long) mult + (long) lmult * Math.max(0, level - 1);
        }

        /** 出牌优先级比较（同级按 order 大者胜，对应 data.js 的 handOrder）。返回 <0/0/>0。 */
        public static int compareOrder(HandType a, HandType b) {
            return Integer.compare(a.order, b.order);
        }
    }

    // ================= 盲注目标分 =================
    // 底注 1~8 的基础目标分；下标 0 占位。
    private static final long[] ANTE_BASE = {0, 300, 800, 2000, 5000, 11000, 20000, 35000, 50000};
    // 无尽模式（底注 9+）基础目标分；下标 0 占位。[8]=8.6e20 超 long → 钳制为 MAX。
    private static final long[] ANTE_ENDLESS = {
            0, 110_000L, 560_000L, 7_200_000L, 300_000_000L,
            47_000_000_000L, 29_000_000_000_000L, 77_000_000_000_000_000L, Long.MAX_VALUE
    };

    /** 底注 ante（>=1）的基础目标分（不含盲注倍率/赌注修饰）。 */
    public static long blindBase(int ante) {
        if (ante <= 8) return ANTE_BASE[ante];
        int idx = ante - 8;
        if (idx <= 8) return ANTE_ENDLESS[idx];
        return Long.MAX_VALUE; // 超出表格，不可达
    }

    // ================= 盲注类型（倍率 + 奖励） =================
    public enum BlindType {
        SMALL("small", 1.0, 3),
        BIG("big", 1.5, 4),
        BOSS("boss", 2.0, 5);

        public final String key;
        public final double mult;   // BLIND_MULT
        public final int reward;    // BLIND_REWARD（$）

        BlindType(String key, double mult, int reward) {
            this.key = key;
            this.mult = mult;
            this.reward = reward;
        }

        private static final Map<String, BlindType> BY_KEY = new HashMap<>();
        static {
            for (BlindType b : values()) BY_KEY.put(b.key, b);
        }

        public static BlindType byKey(String key) {
            BlindType b = BY_KEY.get(key);
            if (b == null) throw new IllegalArgumentException("unknown blind: " + key);
            return b;
        }
    }

    // ================= 增强（8） =================
    public enum Enhancement {
        BONUS("bonus"),
        MULT("mult"),
        WILD("wild"),
        GLASS("glass"),
        STEEL("steel"),
        STONE("stone"),
        GOLD("gold"),
        LUCKY("lucky");

        public final String key;

        Enhancement(String key) {
            this.key = key;
        }

        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() {
            return Lang.t("enhancement." + key + ".name");
        }

        public String desc() {
            return Lang.t("enhancement." + key + ".desc");
        }

        private static final Map<String, Enhancement> BY_KEY = new HashMap<>();
        static {
            for (Enhancement e : values()) BY_KEY.put(e.key, e);
        }

        public static Enhancement byKey(String key) {
            Enhancement e = BY_KEY.get(key);
            if (e == null) throw new IllegalArgumentException("unknown enhancement: " + key);
            return e;
        }
    }

    // ================= 版本（4，不含原版） =================
    public enum Edition {
        FOIL("foil", 0.50),
        HOLO("holo", 0.35),
        POLY("poly", 0.15),
        NEGATIVE("negative", 0.0);

        public final String key;
        public final double chance; // 商店出现权重（相对）

        Edition(String key, double chance) {
            this.key = key;
            this.chance = chance;
        }

        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() {
            return Lang.t("edition." + key + ".name");
        }

        public String desc() {
            return Lang.t("edition." + key + ".desc");
        }

        private static final Map<String, Edition> BY_KEY = new HashMap<>();
        static {
            for (Edition e : values()) BY_KEY.put(e.key, e);
        }

        public static Edition byKey(String key) {
            Edition e = BY_KEY.get(key);
            if (e == null) throw new IllegalArgumentException("unknown edition: " + key);
            return e;
        }
    }

    // ================= Boss 盲注（28） =================
    /** 真版 Showdown Boss（R126 对齐真版：仅在底注 8/16…出现，奖励 $8；紫罗兰之瓶 6×）。 */
    public static final java.util.Set<String> FINISHERS =
            java.util.Set.of("acorn", "leaf", "vessel", "heart", "bell");
    // 28 个 Boss 的 key/name/desc；效果实现见 Engine.java 各 Boss 分支（startRound/drawOne/
    // drawUpTo/playHand/blindTarget 等）+ RunState.disableBoss/sellJoker（leaf 解除）。
    public enum Boss {
        HOOK("hook"),
        OX("ox"),
        HOUSE("house"),
        WALL("wall"),
        WHEEL("wheel"),
        ARM("arm"),
        CLUB_BOSS("club"),
        GOAD("goad"),
        HEAD("head"),
        WINDOW("window"),
        FISH("fish"),
        PSYCHIC("psychic"),
        SERPENT("serpent"),
        PILLAR("pillar"),
        NEEDLE("needle"),
        TOOTH("tooth"),
        FLINT("flint"),
        MARK("mark"),
        ACORN("acorn"),
        BELL("bell"),
        HEART_BOSS("heart"),
        // R139：desc 同步 R126 代码真版对齐（6×；原 ×3 为 REF 值——代码已改而 desc 漏改）
        VESSEL("vessel"),
        WATER("water"),
        MANACLE("manacle"),
        EYE("eye"),
        MOUTH("mouth"),
        PLANT("plant"),
        LEAF("leaf");

        public final String key;

        Boss(String key) {
            this.key = key;
        }

        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() {
            return Lang.t("boss." + key + ".name");
        }

        public String desc() {
            return Lang.t("boss." + key + ".desc");
        }

        private static final Map<String, Boss> BY_KEY = new HashMap<>();
        static {
            for (Boss b : values()) BY_KEY.put(b.key, b);
        }

        public static Boss byKey(String key) {
            Boss b = BY_KEY.get(key);
            if (b == null) throw new IllegalArgumentException("unknown boss: " + key);
            return b;
        }
    }

    // ================= 蜡封（4） =================
    public enum Seal {
        GOLD("gold"),
        RED("red"),
        BLUE("blue"),
        PURPLE("purple");

        public final String key;

        Seal(String key) {
            this.key = key;
        }

        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() {
            return Lang.t("seal." + key + ".name");
        }

        public String desc() {
            return Lang.t("seal." + key + ".desc");
        }

        private static final Map<String, Seal> BY_KEY = new HashMap<>();
        static {
            for (Seal s : values()) BY_KEY.put(s.key, s);
        }

        public static Seal byKey(String key) {
            Seal s = BY_KEY.get(key);
            if (s == null) throw new IllegalArgumentException("unknown seal: " + key);
            return s;
        }
    }

    // ================= 补充包类型 =================
    public enum PackType {
        ARCANA("arcana"), CELESTIAL("celestial"), STANDARD("standard"), BUFFOON("buffoon"), SPECTRAL("spectral");
        public final String key;
        PackType(String key) { this.key = key; }
        public static PackType byKey(String key) {
            for (PackType p : values()) if (p.key.equals(key)) return p;
            throw new IllegalArgumentException("unknown pack type: " + key);
        }
    }

    // ================= 塔罗牌（22） =================
    public enum Tarot {
        FOOL("fool"),
        MAGICIAN("magician"),
        PRIESTESS("priestess"),
        EMPRESS("empress"),
        EMPEROR("emperor"),
        HIEROPHANT("hierophant"),
        LOVERS("lovers"),
        CHARIOT("chariot"),
        JUSTICE("justice"),
        HERMIT("hermit"),
        WHEEL("wheel"),
        STRENGTH("strength"),
        HANGED("hanged"),
        DEATH("death"),
        TEMPERANCE("temperance"),
        DEVIL("devil"),
        TOWER("tower"),
        STAR("star"),
        MOON("moon"),
        SUN("sun"),
        JUDGEMENT("judgement"),
        WORLD("world");
        public final String key;
        Tarot(String k) { key = k; }
        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() { return Lang.t("tarot." + key + ".name"); }
        public String desc() { return Lang.t("tarot." + key + ".desc"); }
        private static final Map<String, Tarot> BY_KEY = new HashMap<>();
        static { for (Tarot t : values()) BY_KEY.put(t.key, t); }
        public static Tarot byKey(String k) { Tarot t = BY_KEY.get(k); if (t == null) throw new IllegalArgumentException("unknown tarot: " + k); return t; }
    }

    // ================= 星球牌（12） =================
    public enum Planet {
        PLUTO("pluto", HandType.HIGH),
        MERCURY("mercury", HandType.PAIR),
        URANUS("uranus", HandType.TWOPAIR),
        VENUS("venus", HandType.THREE),
        SATURN("saturn", HandType.STRAIGHT),
        JUPITER("jupiter", HandType.FLUSH),
        EARTH("earth", HandType.FULL),
        MARS("mars", HandType.FOUR),
        NEPTUNE("neptune", HandType.SFLUSH),
        PLANETX("planetx", HandType.FIVE),
        CERES("ceres", HandType.FHOUSE),
        ERIS("eris", HandType.FFIVE);
        public final String key;
        public final HandType hand;
        Planet(String k, HandType h) { key = k; hand = h; }
        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() { return Lang.t("planet." + key + ".name"); }
        public String desc() { return Lang.t("planet." + key + ".desc"); }
        private static final Map<String, Planet> BY_KEY = new HashMap<>();
        static { for (Planet p : values()) BY_KEY.put(p.key, p); }
        /** P6 性能：按牌型查星球（蓝蜡封/望远镜路径）——原线性扫。 */
        private static final Map<HandType, Planet> BY_HAND = new HashMap<>();
        static { for (Planet p : values()) BY_HAND.put(p.hand, p); }
        public static Planet byKey(String k) { Planet p = BY_KEY.get(k); if (p == null) throw new IllegalArgumentException("unknown planet: " + k); return p; }
        /** 按所升级的牌型查星球牌（无则 null）。蓝蜡封用。 */
        public static Planet byHand(HandType h) {
            return BY_HAND.get(h);
        }
    }

    // ================= 幻灵牌（18） =================
    /** 真版特殊幽灵（R128）：灵魂/黑洞仅在幽灵补充包以 ~0.3% 概率出现，
     *  商店与小丑产出的随机幽灵池排除二者（Spectral Cards Wiki）。 */
    public static final java.util.Set<String> SPECIAL_SPECTRALS = java.util.Set.of("soul", "blackhole");
    public enum Spectral {
        FAMILIAR("familiar"),
        GRIM("grim"),
        INCANTATION("incantation"),
        TALISMAN("talisman"),
        AURA("aura"),
        WRAITH("wraith"),
        SIGIL("sigil"),
        OUIJA("ouija"),
        HEX("hex"),
        ANKH("ankh"),
        DEJAVU("dejavu"),
        TRANCE("trance"),
        MEDIUM("medium"),
        CRYPTID("cryptid"),
        IMMOLATE("immolate"),
        SOUL("soul"),
        BLACKHOLE("blackhole"),
        ECTOPLASM("ectoplasm");
        public final String key;
        Spectral(String k) { key = k; }
        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() { return Lang.t("spectral." + key + ".name"); }
        public String desc() { return Lang.t("spectral." + key + ".desc"); }
        private static final Map<String, Spectral> BY_KEY = new HashMap<>();
        static { for (Spectral s : values()) BY_KEY.put(s.key, s); }
        public static Spectral byKey(String k) { Spectral s = BY_KEY.get(k); if (s == null) throw new IllegalArgumentException("unknown spectral: " + k); return s; }
    }

    // ================= 补充包（13） =================
    public static final class Pack {
        public final String key;
        public final PackType type;
        public final int size, choose, cost;
        public Pack(String key, PackType type, int size, int choose, int cost) {
            this.key = key; this.type = type; this.size = size; this.choose = choose; this.cost = cost;
        }
        /** 显示名（与各枚举保持同一命名，便于统一调用）。 */
        public String displayName() { return Lang.t("pack." + key + ".name"); }
    }
    public static final java.util.List<Pack> PACKS = java.util.List.of(
            new Pack("arcana1", PackType.ARCANA, 3, 1, 4),
            new Pack("arcana2", PackType.ARCANA, 5, 1, 6),
            new Pack("arcana3", PackType.ARCANA, 5, 2, 8),
            new Pack("celestial1", PackType.CELESTIAL, 3, 1, 4),
            new Pack("celestial2", PackType.CELESTIAL, 5, 1, 6),
            new Pack("celestial3", PackType.CELESTIAL, 5, 2, 8),
            new Pack("standard1", PackType.STANDARD, 3, 1, 4),
            new Pack("standard2", PackType.STANDARD, 5, 1, 6),
            new Pack("standard3", PackType.STANDARD, 5, 2, 8),
            new Pack("buffoon1", PackType.BUFFOON, 2, 1, 4),
            new Pack("buffoon2", PackType.BUFFOON, 4, 1, 6),
            new Pack("buffoon3", PackType.BUFFOON, 4, 2, 8),
            new Pack("spectral1", PackType.SPECTRAL, 2, 1, 4)
    );
    private static final Map<String, Pack> PACK_BY_KEY = new HashMap<>();
    static { for (Pack p : PACKS) PACK_BY_KEY.put(p.key, p); }
    public static Pack packByKey(String key) {
        Pack p = PACK_BY_KEY.get(key);
        if (p == null) throw new IllegalArgumentException("unknown pack: " + key);
        return p;
    }

    // ================= 优惠券（32 = 16 对） =================
    public static final class Voucher {
        public final String key;
        public final int base;
        public final String pair;     // 基础券：升级目标 key（升级券为 null）
        public final String requires; // 升级券：所依赖的基础券 key（基础券为 null）
        public Voucher(String key, int base, String pair, String requires) {
            this.key = key; this.base = base; this.pair = pair; this.requires = requires;
        }
        /** 显示名（与各枚举保持同一命名，便于统一调用）。 */
        public String displayName() { return Lang.t("voucher." + key + ".name"); }
        public String desc() { return Lang.t("voucher." + key + ".desc"); }
        public boolean isBase() { return pair != null; }
    }
    public static final java.util.List<Voucher> VOUCHERS = java.util.List.of(
            new Voucher("overstock", 10, "overstock2", null),
            new Voucher("overstock2", 10, null, "overstock"),
            new Voucher("clearance", 10, "liquidation", null),
            new Voucher("liquidation", 10, null, "clearance"),
            new Voucher("blank", 10, "antimatter", null),
            new Voucher("antimatter", 10, null, "blank"),
            new Voucher("tarotm", 10, "tarott", null),
            new Voucher("tarott", 10, null, "tarotm"),
            new Voucher("planetm", 10, "planett", null),
            new Voucher("planett", 10, null, "planetm"),
            new Voucher("hone", 10, "glowup", null),
            new Voucher("glowup", 10, null, "hone"),
            new Voucher("reroll1", 10, "reroll2", null),
            new Voucher("reroll2", 10, null, "reroll1"),
            new Voucher("crystal", 10, "omen", null),
            new Voucher("omen", 10, null, "crystal"),
            new Voucher("telescope", 10, "observatory", null),
            new Voucher("observatory", 10, null, "telescope"),
            new Voucher("seedmoney", 10, "moneytree", null),
            new Voucher("moneytree", 10, null, "seedmoney"),
            new Voucher("grabber", 10, "nacho", null),
            new Voucher("nacho", 10, null, "grabber"),
            new Voucher("wasteful", 10, "recyclo", null),
            new Voucher("recyclo", 10, null, "wasteful"),
            new Voucher("magictrick", 10, "illusion", null),
            new Voucher("illusion", 10, null, "magictrick"),
            new Voucher("hieroglyph", 10, "petroglyph", null),
            new Voucher("petroglyph", 10, null, "hieroglyph"),
            new Voucher("director", 10, "retcon", null),
            new Voucher("retcon", 10, null, "director"),
            new Voucher("paintbrush", 10, "palette", null),
            new Voucher("palette", 10, null, "paintbrush")
    );
    private static final Map<String, Voucher> VOUCHER_BY_KEY = new HashMap<>();
    static { for (Voucher v : VOUCHERS) VOUCHER_BY_KEY.put(v.key, v); }
    public static Voucher voucherByKey(String key) {
        Voucher v = VOUCHER_BY_KEY.get(key);
        if (v == null) throw new IllegalArgumentException("unknown voucher: " + key);
        return v;
    }

    // ================= 稀有度（4） =================
    public enum Rarity {
        COMMON("common", 70),
        UNCOMMON("uncommon", 25),
        RARE("rare", 5),
        LEGENDARY("legendary", 0);
        public final String key;
        public final int weight;
        Rarity(String k, int w) { key = k; weight = w; }
        /** 显示名（注意：不能用 name()，与 Enum.name() 冲突）。 */
        public String displayName() { return Lang.t("rarity." + key); }
        public static Rarity byKey(String k) {
            for (Rarity r : values()) if (r.key.equals(k)) return r;
            throw new IllegalArgumentException("unknown rarity: " + k);
        }
    }

    // ================= 牌组（15）/ 赌注（8）/ 标签（24）/ 挑战（20）：名称与描述 =================
    public record Deck(String key) {
        public String name() { return Lang.t("deck." + key + ".name"); }
        public String desc() { return Lang.t("deck." + key + ".desc"); }
    }
    public static final java.util.List<Deck> DECKS = java.util.List.of(
            new Deck("red"),
            new Deck("blue"),
            new Deck("yellow"),
            new Deck("green"),
            new Deck("black"),
            new Deck("magic"),
            new Deck("nebula"),
            new Deck("ghost"),
            new Deck("abandoned"),
            new Deck("checkered"),
            new Deck("zodiac"),
            new Deck("painted"),
            new Deck("anaglyph"),
            new Deck("plasma"),
            new Deck("erratic")
    );
    private static final Map<String, Deck> DECK_BY_KEY = new HashMap<>();
    static { for (Deck d : DECKS) DECK_BY_KEY.put(d.key(), d); }
    public static Deck deckByKey(String key) {
        Deck d = DECK_BY_KEY.get(key);
        if (d == null) throw new IllegalArgumentException("unknown deck: " + key);
        return d;
    }

    public record Stake(String key) {
        public String name() { return Lang.t("stake." + key + ".name"); }
        public String desc() { return Lang.t("stake." + key + ".desc"); }
    }
    public static final java.util.List<Stake> STAKES = java.util.List.of(
            new Stake("white"),
            new Stake("red"),
            new Stake("green"),
            new Stake("black"),
            new Stake("blue"),
            new Stake("purple"),
            new Stake("orange"),
            new Stake("gold")
    );

    public record Tag(String key) {
        public String name() { return Lang.t("tag." + key + ".name"); }
        public String desc() { return Lang.t("tag." + key + ".desc"); }
    }
    public static final java.util.List<Tag> TAGS = java.util.List.of(
            new Tag("double"),
            new Tag("uncommon"),
            new Tag("rare"),
            new Tag("negative"),
            new Tag("foil"),
            new Tag("holo"),
            new Tag("poly"),
            new Tag("invest"),
            new Tag("voucher"),
            new Tag("boss"),
            new Tag("standard"),
            new Tag("charm"),
            new Tag("meteor"),
            new Tag("buffoon"),
            new Tag("handy"),
            new Tag("garbage"),
            new Tag("ethereal"),
            new Tag("coupon"),
            new Tag("d6"),
            new Tag("topup"),
            new Tag("speed"),
            new Tag("orbital"),
            new Tag("economy"),
            new Tag("juggle")
    );

    public record Challenge(String key) {
        public String name() { return Lang.t("challenge." + key + ".name"); }
        public String desc() { return Lang.t("challenge." + key + ".desc"); }
    }
    public static final java.util.List<Challenge> CHALLENGES = java.util.List.of(
            new Challenge("omelette"),
            new Challenge("city15"),
            new Challenge("rich"),
            new Challenge("knife"),
            new Challenge("xray"),
            new Challenge("madworld"),
            new Challenge("luxury"),
            new Challenge("nonperish"),
            new Challenge("medusa"),
            new Challenge("double"),
            new Challenge("typecast"),
            new Challenge("inflation"),
            new Challenge("bram"),
            new Challenge("fragile"),
            new Challenge("monolith"),
            new Challenge("blastoff"),
            new Challenge("fivecard"),
            new Challenge("golden"),
            new Challenge("cruelty"),
            new Challenge("jokerless")
    );
}
