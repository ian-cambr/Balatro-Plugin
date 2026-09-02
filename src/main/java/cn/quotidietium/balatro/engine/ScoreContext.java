package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.i18n.Lang;
import java.util.List;

/**
 * 计分上下文，对应 balatro playHand 内的 ctx 对象。
 * chips/mult 为可变双精度（对齐 JS Number，兼容 ×1.5 等分数倍率），由 Engine 在计分末尾取整。
 */
public final class ScoreContext {
    public final RunState state;
    public final Data.HandType handType;
    public final List<Card> playedCards;
    public final List<Card> heldCards;
    public final List<String> events;
    public int scoreIndex = -1;
    public JokerInstance joker;
    public boolean photoUsed;

    public double chips;
    public double mult;

    public ScoreContext(RunState state, Data.HandType handType, double baseChips, double baseMult,
                        List<Card> playedCards, List<Card> heldCards, List<String> events) {
        this.state = state;
        this.handType = handType;
        this.chips = baseChips;
        this.mult = baseMult;
        this.playedCards = playedCards;
        this.heldCards = heldCards;
        this.events = events;
    }

    public void addChips(long n) {
        chips += n;
    }

    public void addMult(long n) {
        mult += n;
    }

    public void xMult(double x) {
        mult *= x;
    }

    public void dollars(long n) {
        state.gainMoney(n);
        events.add("+$" + n);
    }

    public void msg(String t) {
        events.add(t);
    }

    public boolean prob(double p) {
        if (Boolean.TRUE.equals(state.flags.get("doubleProb"))) p = Math.min(1, p * 2);
        return state.stream("prob").chance(p);
    }

    public int rngInt(int a, int b) {
        return state.stream("prob").range(a, b);
    }

    public boolean isSuit(Card c, int s) {
        return state.isSuit(c, s);
    }

    public boolean isFace(Card c) {
        return state.isFace(c);
    }

    public boolean handIs(String key) {
        return handType.key.equals(key);
    }

    /** R130 真版 contains 口径：手牌是否**包含**指定牌型（附加型小丑触发条件，
     *  wiki Important Joker Terms）。由 Engine 注入本手的 contains 集合。 */
    public java.util.Set<Data.HandType> handContainsSet = java.util.Set.of();

    /** R130 真版：本手【计分牌】（重影/花盆判定口径）；由 Engine 注入，缺省回落为打出牌。 */
    public java.util.List<Card> scoredCards = java.util.List.of();

    public boolean handContains(String key) {
        for (Data.HandType t : handContainsSet) if (t.key.equals(key)) return true;
        return false;
    }

    /**
     * 获得随机消耗品（对齐 engine.js ctx.gainConsumable）：
     * 按 kind 从对应池经 {@code consumable} 流随机取；加入成功则记入本手事件。
     * （8 号球等小丑经此发放塔罗牌。）
     *
     * <p>R137：池口径与 {@link RunState#gainConsumable} 统一（共享 grantPool 提取）——
     * 塔罗/幻灵走禁入过滤，幻灵并排除 SPECIAL_SPECTRALS（R128 真版：灵魂/黑洞仅幽灵包
     * 产出）。此前本方法用全量 SPECTRALS，公共 API 路径（第三方小丑）可绕过 R128 规则。
     * 池空跳过抽取（不消耗流），与 state 版一致。
     */
    public void gainConsumable(String kind) {
        String name = null;
        if ("tarot".equals(kind)) {
            java.util.List<Data.Tarot> pool = state.tarotGrantPool();
            if (!pool.isEmpty()) {
                Data.Tarot t = state.stream("consumable").pick(pool);
                if (state.addConsumableKey("tarot", t.key)) name = t.displayName();
            }
        } else if ("planet".equals(kind)) {
            Data.Planet p = state.stream("consumable").pick(Data.PLANETS);
            if (state.addConsumableKey("planet", p.key)) name = p.displayName();
        } else {
            java.util.List<Data.Spectral> pool = state.spectralGrantPool();
            if (!pool.isEmpty()) {
                Data.Spectral sp = state.stream("consumable").pick(pool);
                if (state.addConsumableKey("spectral", sp.key)) name = sp.displayName();
            }
        }
        if (name != null) events.add(Lang.t("msg.gained", name));
    }
}
