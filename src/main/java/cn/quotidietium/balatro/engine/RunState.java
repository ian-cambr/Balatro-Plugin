package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.i18n.Lang;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一局游戏的可变状态，对应 balatro {@code createRun} 返回的 state 对象。
 *
 * <p>字段为 {@code public}（对齐 JS 里开放的可变 state：引擎直接改写，渲染/会话层只读）。
 * 仅 {@link #streamSource} 与卡牌 id 序列私有。
 */
public final class RunState {

    // ---- 配置 ----
    public String seed;
    public String deckKey;
    public int stakeIdx;
    public String challenge; // 0.1.0 恒为 null
    public final Mods mods = new Mods();

    // ---- 进度 ----
    public long money;
    public int ante;
    public String bossKey;              // 当前底注的 Boss key（仅命名/复现，0.1.0 不生效）
    public List<String> bossQueue = new ArrayList<>();
    public Data.BlindType blindType;    // 当前进行/刚完成的盲注
    public String nextBlind;            // small/big/boss
    public Phase phase;
    public boolean endless;
    public boolean won;
    public boolean lost;
    public boolean endlessPending;

    // ---- 槽位/上限（base） ----
    public int jokerSlots = 5;
    public int consumableSlots = 2;
    public int handSizeBase = 8;
    /** 永久手牌上限修正（R137 真版：通灵板/幻灵 ectoplasm 的 -1 为**整局永久**，
     *  须跨回合存活——applyVouchersPassive 每回合从 8 重建 handSizeBase 会把直接
     *  写在 base 上的减量抹掉（REF 同 bug），故独立携带、重建后叠加。 */
    public int handSizePerm;
    public int handsBase = 4;
    public int discardsBase = 3;
    public int interestCap = 5;
    public int shopSlots = 2;

    // ---- 持有物 ----
    public final List<JokerInstance> jokers = new ArrayList<>();
    public final List<Consumable> consumables = new ArrayList<>();
    public final List<String> vouchers = new ArrayList<>();
    public final List<String> tags = new ArrayList<>();

    // ---- 牌堆 ----
    /** P8 性能：预置 52 容量——标准/绝大多数挑战牌组恰 52 张，消除 10→15→22→33→49 的
     *  5 次扩容拷贝链（JFR 实测 grow 链占 playHand 场景分配 ~25% 的主因之一）。 */
    public List<Card> fullDeck = new ArrayList<>(52);
    public List<Card> drawPile = new ArrayList<>();
    public List<Card> hand = new ArrayList<>();
    public List<Card> discardPile = new ArrayList<>();

    // ---- 牌型升级/统计 ----
    /** P5 性能：EnumMap（迭代序=枚举声明序，与原 LinkedHashMap 构造插入序一致）。 */
    public final Map<Data.HandType, Integer> handLevels = new EnumMap<>(Data.HandType.class);
    /** 保持 LinkedHashMap：mostPlayedType 的平局裁决依赖「首次打出顺序」的插入序（语义红线）。 */
    public final Map<Data.HandType, Integer> handPlayedCount = new LinkedHashMap<>();
    public final Map<String, Boolean> usedPlanets = new HashMap<>(); // 卫星小丑用（0.2.0 起记录）

    // ---- 回合运行时 ----
    public int handsLeft;
    public int discardsLeft;
    public long roundScore;
    public long blindTarget;
    public int handSizeRound;
    public int handsPlayedThisRound;
    public int discardsUsedThisRound;
    public boolean usedDiscardThisRound;
    public final List<Data.HandType> playedTypesThisRound = new ArrayList<>();
    public final Set<Integer> playedThisAnte = new HashSet<>(); // pillar：本底注打过的牌 id
    public Map<String, Object> flags = new HashMap<>();
    /** P8 性能：computeFlags 双缓冲备用表（见 Engine.computeFlags）。包内私有协作字段。 */
    Map<String, Object> flagsSpare;

    // ---- P10 性能：小丑列表快照的深度分层池 ----
    // 原实现各钩子分发点 `new ArrayList<>(jokers)`（playHand 每手 2~3 次、discard/endRound/
    // sellJoker/reroll 等各 1 次，~104B/次）。池化复用后稳态零分配。
    // 安全性：①深度分层——同时活跃的嵌套快照（如 onPlayHand 钩子内再触发 addCardToDeck
    // 的 onCardAdded 分发）各占一层，互不覆写；②try/finally 释放，钩子抛异常不泄漏深度；
    // ③漏调 release 只会使下一层新建缓冲（自愈，无正确性影响）；
    // ④缓冲内容在 acquire 时重建（clear+addAll），语义与「每次新列表装 jokers」完全一致。
    private ArrayList<JokerInstance>[] jokerBufferPool = new ArrayList[4];
    private int jokerBufferDepth;

    /** 取一层缓冲并装入当前 jokers 的拷贝（各钩子分发点快照）。
     *  引擎协作 API（跨 engine 子包共用）：必须与 {@link #releaseJokerBuffer()} 严格配对
     *  （建议 try/finally），循环内不得越出配对区持有该列表。 */
    public ArrayList<JokerInstance> acquireJokerSnap() {
        ArrayList<JokerInstance> buf = acquireJokerBuffer();
        buf.addAll(jokers);
        return buf;
    }

    /** 取一层**空**缓冲（调用方自行填充，如 playHand 的 activeJokers 过滤列表）。 */
    @SuppressWarnings("unchecked")
    ArrayList<JokerInstance> acquireJokerBuffer() {
        if (jokerBufferDepth >= jokerBufferPool.length) {
            jokerBufferPool = Arrays.copyOf(jokerBufferPool, jokerBufferPool.length * 2);
        }
        ArrayList<JokerInstance> buf = jokerBufferPool[jokerBufferDepth];
        if (buf == null) {
            buf = new ArrayList<>(Math.max(4, jokers.size() + 2));
            jokerBufferPool[jokerBufferDepth] = buf;
        }
        jokerBufferDepth++;
        buf.clear();
        return buf;
    }

    /** 归还最近一层缓冲（与 acquire 严格配对，建议 finally 中调用）。 */
    public void releaseJokerBuffer() {
        if (jokerBufferDepth > 0) jokerBufferDepth--;
    }
    public boolean bossDisabled;
    public boolean bossTriggeredThisHand;
    public Integer bossSuitDebuff; // Boss 花色失效（null 或 0-3）
    public boolean bossFaceDebuff; // Boss 人头牌失效
    public boolean bossLeaf;       // 翠绿之叶：全部失效
    public Integer bellCardId;     // 翠绿铃：强制选中的牌 id（null 无）
    public int roundCount = 0;
    public boolean grosDead; // 格罗米歇尔已碎（决定卡文迪什是否可生成）
    public int inflation;   // 通货膨胀挑战：商店加价累计
    public int useSeq;      // 消耗品使用序号（构造唯一流）
    public TarotPlanet lastTarotPlanet; // 愚人复制用：上一张使用的塔罗/星球
    public boolean doubleTagPending;    // 翻倍标签待复制
    public int statsHandsPlayed, statsDiscardsUnused, statsBlindsSkipped; // 标签/统计用

    /** 上一张使用的塔罗/星球（供愚人复制）。 */
    public static final class TarotPlanet {
        public final String kind, key;
        public TarotPlanet(String kind, String key) { this.kind = kind; this.key = key; }
    }
    public cn.quotidietium.balatro.engine.shop.Shop.ShopData shop; // 当前商店（0.2.0）
    public final Map<String, Object> nextShop = new HashMap<>();  // 标签等对下个商店的修饰
    public cn.quotidietium.balatro.engine.shop.Packs.Session pack; // 当前补充包
    public Phase packReturn;                                       // 开完包返回的阶段
    public int packSeq;                                            // 补充包序号（构造唯一流）

    // ---- 运行时杂项 ----
    private final StreamSource streamSource;
    private int cardIdSeq = 1;
    public final List<String> messages = new ArrayList<>();

    RunState(String seed) {
        this.seed = seed;
        this.streamSource = new StreamSource(seed);
        // P11 性能：遍历共享的 HAND_TYPES（List.of 缓存），替代每次 createRun 的
        // HandType.values()（枚举 values() 每次都新数组）——迭代序同为枚举声明序
        for (Data.HandType h : Data.HAND_TYPES) {
            handLevels.put(h, 1);
        }
    }

    // ---- 随机流 ----
    public Rng.Stream stream(String name) {
        return streamSource.stream(name);
    }

    /**
     * P14 性能：消耗品使用流（名字 "use:"+key+":"+roundCount+":"+seq 内嵌递增序号，
     * 永不复现）——分段折叠建流，零字符串物化/零缓存插入，与
     * {@code stream("use:"+key+":"+roundCount+":"+seq)} 逐位等价（守门测试锁定）。
     */
    public Rng.Stream streamUse(String consumableKey) {
        return streamSource.streamUse(consumableKey, roundCount, ++useSeq);
    }

    /** P14 性能：补充包流（同 {@link #streamUse} 的 "pack"+roundCount+":"+key+":"+seq 方案）。 */
    public Rng.Stream streamPack(String packKey) {
        return streamSource.streamPack(roundCount, packKey, ++packSeq);
    }

    /** P15 性能：每回合一次性流（prefix+roundCount，如 shuffle/shopgen——回合数递增永不复现）。 */
    public Rng.Stream streamRound(String roundPrefix) {
        return streamSource.streamRound(roundPrefix, roundCount);
    }

    // ---- 卡牌 id ----
    int nextCardId() {
        return cardIdSeq++;
    }

    public Card makeCard(int rank, int suit) {
        return new Card(nextCardId(), rank, suit);
    }

    // ---- 金钱/消息 ----
    /**
     * 饱和加法（防 long 溢出环绕）。原版 JS 的 Number 是 double，不会环绕；
     * 无尽模式极端数值（如奔月复利使金钱指数增长）下 long 会环绕成负数，
     * 语义错误且破坏利息等下游计算（负钱→负利息恶性循环）。饱和到极值对齐 double 不环绕语义。
     */
    public static long satAdd(long a, long b) {
        long r = a + b;
        // 同号相加得异号结果即溢出
        if (((a ^ r) & (b ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    public void gainMoney(long n) {
        money = satAdd(money, n);
    }

    public void msg(String text) {
        messages.add(text);
        if (messages.size() > 200) {
            messages.remove(0);
        }
    }

    /** 取最近的消息（用于 UI 简报）。 */
    public List<String> drainMessages() {
        List<String> copy = new ArrayList<>(messages);
        messages.clear();
        return copy;
    }

    // ---- 牌型辅助（供小丑钩子与计分共用，对应 isFaceCard/isSuitFor） ----
    public boolean isFace(Card c) {
        if (flags != null && Boolean.TRUE.equals(flags.get("allFace"))) return true;
        return c.rank() >= 11 && c.rank() <= 13;
    }

    public boolean isSuit(Card c, int s) {
        if (c.isEnh(Data.Enhancement.STONE)) return false; // P13：位段谓词
        if (c.isEnh(Data.Enhancement.WILD)) return true;
        if (flags != null && Boolean.TRUE.equals(flags.get("smeared"))) {
            if (s == 1 || s == 3) return c.suit() == 1 || c.suit() == 3;
            return c.suit() == 0 || c.suit() == 2;
        }
        return c.suit() == s;
    }

    /** 本局最常打出的牌型（公牛 Boss 用），无则 null。 */
    public Data.HandType mostPlayedType() {
        Data.HandType best = null;
        int bestN = -1;
        for (Map.Entry<Data.HandType, Integer> e : handPlayedCount.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    public int handLevel(Data.HandType t) {
        return handLevels.getOrDefault(t, 1);
    }

    /**
     * 销毁一张小丑（自毁类小丑在 onRoundEnd/onPlayHand 中调用）。
     *
     * <p>对齐 REF engine.js destroyJoker：任何途径（自毁/仪式匕首/癫狂）销毁格罗米歇尔
     * 都置 {@link #grosDead}（解锁卡文迪什生成）；销毁后重算 flags，
     * 使被毁小丑的手牌上限/四指/信用等标志立即失效（此前缺失，标志残留至下次重算）。
     */
    public void destroyJoker(JokerInstance j, String reason) {
        jokers.remove(j);
        if ("grossmichel".equals(j.def.key())) grosDead = true;
        if (reason != null) msg(reason);
        Engine.recomputeFlags(this);
    }

    /** 小丑售价（含版本加成，max(1)）。R123：通胀挑战持有物基价同步上涨。 */
    public int sellValue(JokerInstance j) {
        int cost = j.def.cost();
        if (j.edition == Data.Edition.FOIL) cost += 2;
        else if (j.edition == Data.Edition.HOLO) cost += 3;
        else if (j.edition == Data.Edition.POLY) cost += 5;
        else if (j.edition == Data.Edition.NEGATIVE) cost += 5;
        if (mods.inflationPerBuy) cost += (int) Math.min(inflation, 1000); // 真版：基价随购买数上涨
        // R124 对齐真版：租赁小丑卖价恒 $1（"sell for only $1"）；蛋类 sellBonus 仍叠加。
        // REF 的 cost/2 为 REF bug（$8 租赁小丑卖 $4）。仅金注出现，标准局零影响。
        if (j.rental) return Math.max(1, 1 + j.sellBonus);
        return Math.max(1, cost / 2 + j.sellBonus);
    }

    /** 消耗品售价（max(1)，含 sellBonus 加成）。统一口径，供渲染/对话框/引擎共用。 */
    public static int sellValue(Consumable c) {
        return Math.max(1, 1 + c.sellBonus);
    }

    /** 消耗品售价（实例口径）：通胀挑战持有物基价同步上涨（R123）。 */
    public int sellValueC(Consumable c) {
        int v = 1 + c.sellBonus;
        if (mods.inflationPerBuy) v += (int) Math.min(inflation, 1000);
        return Math.max(1, v);
    }

    /** 出售第 idx 张小丑（永恒不可出售；触发 onSell/onAnySell；解除翠绿之叶）。 */
    public boolean sellJoker(int idx) {
        if (idx < 0 || idx >= jokers.size()) return false;
        JokerInstance j = jokers.get(idx);
        if (j.eternal) { msg(Lang.t("msg.eternal_no_sell")); return false; }
        int val = sellValue(j);
        jokers.remove(idx);
        gainMoney(val);
        msg(Lang.t("msg.sold_joker", j.def.displayName(), val));
        j.def.onSell(this, j);
        List<JokerInstance> anySellSnap = acquireJokerSnap(); // P10：池化快照
        try {
            for (int i = 0; i < anySellSnap.size(); i++) {
                JokerInstance o = anySellSnap.get(i);
                if (!o.debuff) o.def.onAnySell(this, o);
            }
        } finally {
            releaseJokerBuffer();
        }
        if (bossLeaf) {
            bossLeaf = false;
            for (Card c : hand) c.setDebuff(false);
            msg(Lang.t("msg.verdant_cleared"));
        }
        Engine.recomputeFlags(this);
        return true;
    }

    /** 出售第 idx 个消耗品。 */
    public boolean sellConsumable(int idx) {
        if (idx < 0 || idx >= consumables.size()) return false;
        Consumable c = consumables.remove(idx);
        int val = sellValueC(c);
        gainMoney(val);
        msg(Lang.t("msg.sold_consumable", val));
        return true;
    }

    /** 升级牌型等级。 */
    public void levelUpHand(Data.HandType type, int n) {
        handLevels.merge(type, n, Integer::sum);
    }

    /** 复制一张牌（新 id，复制增强/版本/蜡封/永久筹码）。 */
    public Card cloneCard(Card c) {
        Card n = new Card(nextCardId(), c.rank(), c.suit());
        n.setEnh(c.enh());
        n.setEdition(c.edition());
        n.setSeal(c.seal());
        n.addChipBonus(c.chipBonus());
        return n;
    }

    /** 卡牌可读名。 */
    public String cardName(Card c) {
        if (c.isStone()) return Lang.t("card.stone_card");
        return Data.Suit.byIndex(c.suit()).displayName() + Data.rankName(c.rank());
    }

    /** 从牌组/牌堆/手牌/弃牌堆移除一张牌。 */
    public void removeCardFromDeck(Card c) {
        fullDeck.remove(c);
        drawPile.remove(c);
        hand.remove(c);
        discardPile.remove(c);
    }

    /** 销毁一张牌（含 onFaceDestroyed 钩子：人头牌被销毁时触发 Canio 等）。 */
    public void destroyCard(Card c) {
        // 对齐原版 isFaceCard：尊重 allFace 标志（空想性错觉 pareidolia 时所有牌视为人头牌）
        if (isFace(c)) {
            List<JokerInstance> faceSnap = acquireJokerSnap(); // P10：池化快照
            try {
                for (int i = 0; i < faceSnap.size(); i++) {
                    JokerInstance j = faceSnap.get(i);
                    if (!j.debuff) j.def.onFaceDestroyed(this, j);
                }
            } finally {
                releaseJokerBuffer();
            }
        }
        removeCardFromDeck(c);
    }

    /** 获得随机消耗品（按 kind 从对应池随机取；对齐 engine.js：加入成功时提示"获得：名称"）。
     *  R123：禁入清单（真版易碎品/无丑牌）过滤抽取池。 */
    public void gainConsumable(String kind) {
        switch (kind) {
            case "tarot" -> {
                // R137：池口径抽取为 tarotGrantPool（P12 共享池语义不变，与 ctx 版共用）
                List<Data.Tarot> pool = tarotGrantPool();
                Data.Tarot t = pool.isEmpty() ? null : stream("consumable").pick(pool);
                if (t != null && addConsumableKey("tarot", t.key)) msg(Lang.t("msg.gained", t.displayName()));
            }
            case "planet" -> {
                Data.Planet p = stream("consumable").pick(Data.PLANETS);
                if (addConsumableKey("planet", p.key)) msg(Lang.t("msg.gained", p.displayName()));
            }
            default -> {
                // R137：池口径抽取为 spectralGrantPool（R128 去 SPECIAL + 禁入，与 ctx 版共用）
                List<Data.Spectral> pool = spectralGrantPool();
                Data.Spectral sp = pool.isEmpty() ? null : stream("consumable").pick(pool);
                if (sp != null && addConsumableKey("spectral", sp.key)) msg(Lang.t("msg.gained", sp.displayName()));
            }
        }
    }

    /** P12 性能：幻灵产出静态池（SPECTRALS 去 SPECIAL，无禁入时与物化过滤池同内容同序）。 */
    private static final List<Data.Spectral> SPECTRAL_GRANT_POOL = buildSpectralGrantPool();

    private static List<Data.Spectral> buildSpectralGrantPool() {
        List<Data.Spectral> pool = new ArrayList<>(Data.SPECTRALS.size());
        for (Data.Spectral sp : Data.SPECTRALS) {
            if (!Data.SPECIAL_SPECTRALS.contains(sp.key)) pool.add(sp);
        }
        return List.copyOf(pool);
    }

    /**
     * 塔罗随机产出池（禁入过滤；R137 抽取为共享口径，供 state/ctx 两条 gainConsumable 复用）。
     * 空表由调用方跳过抽取（不消耗 consumable 流，与既有行为一致）。
     */
    List<Data.Tarot> tarotGrantPool() {
        if (mods.bannedTarots.isEmpty()) return Data.TAROTS;
        List<Data.Tarot> pool = new ArrayList<>(Data.TAROTS.size());
        for (Data.Tarot t : Data.TAROTS) {
            if (!mods.bannedTarots.contains(t.key)) pool.add(t);
        }
        return pool;
    }

    /**
     * 幻灵随机产出池（去 SPECIAL【R128 真版：灵魂/黑洞仅幽灵包产出】+ 禁入过滤；
     * R137 抽取为共享口径，供 state/ctx 两条 gainConsumable 复用——此前 ScoreContext
     * 版仍用全量 SPECTRALS，公共 API 路径违反 R128 产出规则）。
     */
    List<Data.Spectral> spectralGrantPool() {
        if (mods.bannedSpectrals.isEmpty()) return SPECTRAL_GRANT_POOL;
        List<Data.Spectral> pool = new ArrayList<>(Data.SPECTRALS.size());
        for (Data.Spectral sp : Data.SPECTRALS) {
            if (mods.bannedSpectrals.contains(sp.key)) continue;
            if (Data.SPECIAL_SPECTRALS.contains(sp.key)) continue;
            pool.add(sp);
        }
        return pool;
    }

    /** 把指定消耗品加入消耗品区（槽位满则失败）。 */
    public boolean addConsumableKey(String kind, String key) {
        // 对齐 REF engine.js:202-205：negative 版本消耗品 each +1 槽
        int neg = 0;
        for (Consumable c : consumables) if (c.edition == Data.Edition.NEGATIVE) neg++;
        if (consumables.size() >= consumableSlots + neg) return false;
        consumables.add(new Consumable(kind, key));
        return true;
    }

    /** 消除当前 Boss 效果（对齐 REF engine.js:1987-1993：置标志 + 清除所有 Boss debuff 标记 + hand 已有 debuff）。 */
    public void disableBoss() {
        bossDisabled = true;
        bossSuitDebuff = null;
        bossFaceDebuff = false;
        bossLeaf = false;
        for (Card c : hand) c.setDebuff(false);
    }

    /** 获得跳过标签（应用效果）。 */
    public void gainTag(String key) {
        Engine.gainTag(this, key);
    }

    /** 评估一组手牌的牌型（供小丑钩子调用，如烧焦小丑）。 */
    public HandEval.Result evaluateHand(List<Card> cards) {
        return HandEval.evaluate(this, cards);
    }

    /** 剩余小丑槽（对齐 REF engine.js:1402-1405：negative 版本小丑 each +1 槽）。 */
    public int jokerSpace() {
        int neg = 0;
        for (JokerInstance j : jokers) if (j.edition == Data.Edition.NEGATIVE) neg++;
        return jokerSlots + neg - jokers.size();
    }

    /**
     * 获得一张指定小丑（0.2.0 商店/效果共用）。对齐 engine.js gainJoker：加入后重算 flags。
     *
     * <p><b>对 REF 的有意修正（第 6 处）</b>：negative 版本小丑自带 +1 槽，按真版语义
     * （[Negative Wiki](https://balatrowiki.org/w/Negative)：「+1 Joker slot」；
     * Steam 讨论：「increase your maximum slots by 1, able to buy one with a full hand」）
     * 满槽（jokerSpace==0）时仍可加入——因为加入后它自身贡献的 +1 槽正好容纳自身。
     * REF engine.js 的 gainJoker/jokerSpace 在加入前检查，满槽时拒绝 negative 小丑
     * （REF bug，与真版/描述不符）。此处按真版修正：negative 小丑只要当前未超槽即可加入。
     * 不影响种子复现：jokerSpace 检查不消耗 random stream，商店商品序列不变。
     */
    public boolean gainJoker(String key, Data.Edition edition) {
        boolean negative = edition == Data.Edition.NEGATIVE;
        // negative 小丑自带 +1 槽：jokerSpace==0（满普通槽）时仍可加入（加入后 =0 仍合法）；
        // 非 negative 小丑要求至少 1 空槽。jokerSpace 不可能为负（正常游戏），<0 仅为超槽兜底。
        if (negative ? jokerSpace() < 0 : jokerSpace() <= 0) return false;
        JokerInstance j = cn.quotidietium.balatro.engine.joker.JokerRegistry.create(key);
        if (j == null) return false;
        if (edition != null) j.edition = edition;
        jokers.add(j);
        msg(Lang.t("msg.joker_gained", cn.quotidietium.balatro.engine.joker.JokerRegistry.nameOf(key)));
        // 原版 gainJoker 即 computeFlags：新小丑的 flags（fourFingers/splash/handSize 等）
        // 须立即对后续计分/回合生效（如回合中用「审判」获得带 flags 的小丑）
        Engine.recomputeFlags(this);
        return true;
    }

    /**
     * 随机获得一张指定稀有度的小丑（对齐 engine.js gainRandomJoker）。
     *
     * <p>rarity=null 时从 普通+罕见+稀有 混合池**均匀**抽取（原版语义，无传奇）；
     * 否则取该稀有度池。流名 {@code randomjoker}、满槽先返回不耗流，均与原版逐字一致
     * （此前误用 jokergrant 流 + 70/25/5 分段掷稀有度，属移植错误，破坏种子复现）。
     */
    public boolean gainRandomJoker(Integer rarity) {
        if (jokerSpace() <= 0) return false;
        Rng.Stream st = stream("randomjoker");
        // P12 负结果（已回退）：两趟虚拟抽取省 ~700B/次但第二趟 150 元素过滤时间净回归
        // （与商店池同源结论：物化/复用/两趟皆不敌 TLAB）。人频路径保留物化实现。
        java.util.List<Joker> pool = new java.util.ArrayList<>();
        for (Joker j : cn.quotidietium.balatro.engine.joker.JokerRegistry.allJokersOrdered()) {
            int r = cn.quotidietium.balatro.engine.joker.JokerRegistry.rarityOf(j.key());
            if (rarity == null ? r < 3 : r == rarity) pool.add(j);
        }
        // 禁入小丑（真版煎蛋卷：随机获得路径同样不产出经济小丑）——R108 对齐真版
        pool.removeIf(j -> mods.bannedJokers.contains(j.key()));
        if (pool.isEmpty()) return false;
        Joker pick = st.pick(pool);
        return gainJoker(pick.key(), null);
    }

    /**
     * 复制一张小丑（ankh/隐形小丑产线，R114 对齐真版）：产出新实例并**继承贴纸**——
     * 永恒/租赁/易腐（含剩余回合计数）随复制保留（[Invisible Joker Wiki]/
     * [Reddit：copies the whole joker, even the remaining rounds counter]）；
     * 版本按调用方传入（ankh 不复制负片：[Ankh Wiki/Steam]）。
     * REF gainJoker 仅传版本、贴纸全丢——REF bug，按用户「对齐原版机制，REF 有问题一并修」修正。
     * sellBonus 不复制（无证据支持，保持新实例起步）。不消耗流。
     */
    public boolean duplicateJoker(JokerInstance src, Data.Edition edition) {
        boolean ok = gainJoker(src.def.key(), edition);
        if (ok) {
            JokerInstance copy = jokers.get(jokers.size() - 1);
            copy.eternal = src.eternal;
            copy.rental = src.rental;
            copy.perishable = src.perishable;
            copy.perishCount = src.perishCount;
        }
        return ok;
    }

    /** 把一张牌加入牌组（触发 onCardAdded）。 */
    public void addCardToDeck(Card c) {
        fullDeck.add(c);
        List<JokerInstance> addSnap = acquireJokerSnap(); // P10：池化快照
        try {
            for (int i = 0; i < addSnap.size(); i++) {
                JokerInstance j = addSnap.get(i);
                if (!j.debuff) j.def.onCardAdded(this, c, j);
            }
        } finally {
            releaseJokerBuffer();
        }
    }

    /** 生成一张随机游戏牌（rpc 流）。 */
    public Card randomPlayingCard() {
        Rng.Stream s = stream("rpc");
        return makeCard(s.range(2, 14), s.range(0, 3));
    }
}
