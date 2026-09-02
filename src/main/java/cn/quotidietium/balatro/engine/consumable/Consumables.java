package cn.quotidietium.balatro.engine.consumable;

import cn.quotidietium.balatro.engine.Card;
import cn.quotidietium.balatro.engine.Consumable;
import cn.quotidietium.balatro.engine.Data;
import cn.quotidietium.balatro.engine.Engine;
import cn.quotidietium.balatro.engine.JokerInstance;
import cn.quotidietium.balatro.engine.Phase;
import cn.quotidietium.balatro.engine.Rng;
import cn.quotidietium.balatro.engine.RunState;
import cn.quotidietium.balatro.i18n.Lang;
import java.util.ArrayList;
import java.util.List;

/**
 * 消耗品使用与效果，移植自 {@code engine.js} 的 useConsumable/applyConsumable（52 效果）。
 * 目标手牌通过 targetIds（卡 id）传入；非回合内使用时 targets 返回 null（仅限可用项）。
 */
public final class Consumables {

    private Consumables() {
    }

    public static final class Result {
        public final boolean ok;
        public final String err;
        private Result(boolean ok, String err) { this.ok = ok; this.err = err; }
        public static Result ok() { return new Result(true, null); }
        public static Result err(String e) { return new Result(false, e); }
    }

    /** 使用消耗品 idx；targetIds 为手牌卡 id（可空）。 */
    public static Result use(RunState s, int idx, List<Integer> targetIds) {
        if (s.phase != Phase.ROUND && s.phase != Phase.SHOP) return Result.err(Lang.t("err.cannot_use_now"));
        if (idx < 0 || idx >= s.consumables.size()) return Result.err(Lang.t("err.bad_consumable"));
        Consumable c = s.consumables.get(idx);
        boolean inRound = s.phase == Phase.ROUND;

        Result res = apply(s, c, targetIds, inRound);
        if (!res.ok) return res;

        s.consumables.remove(idx);
        if (c.kind.equals("tarot") || c.kind.equals("planet")) {
            s.lastTarotPlanet = new RunState.TarotPlanet(c.kind, c.key);
        }
        if (c.kind.equals("tarot")) {
            List<JokerInstance> useSnap = s.acquireJokerSnap(); // P14：P10 池化快照补点
            try {
                for (int i = 0; i < useSnap.size(); i++) {
                    JokerInstance j = useSnap.get(i);
                    if (!j.debuff) j.def.onUseTarot(s, j);
                }
            } finally {
                s.releaseJokerBuffer();
            }
        }
        if (c.kind.equals("planet")) {
            s.usedPlanets.put(c.key, true);
            List<JokerInstance> planetSnap = s.acquireJokerSnap(); // P14：P10 池化快照补点
            try {
                for (int i = 0; i < planetSnap.size(); i++) {
                    JokerInstance j = planetSnap.get(i);
                    if (!j.debuff) j.def.onUsePlanet(s, j);
                }
            } finally {
                s.releaseJokerBuffer();
            }
        }
        Engine.sortHand(s); // 消耗品可能改写/增删手牌，整理以保持展示顺序（apply 内 stream.pick 已于此之前完成）
        // hex/ankh 等会直接改写小丑列表：重算 flags，避免被毁小丑的标志残留
        // （如商店内 hex 销毁信用卡后 credit 仍生效 → 无小丑也能欠债消费的漏洞）。
        // computeFlags 不消耗随机流，不影响种子复现。
        Engine.recomputeFlags(s);
        return Result.ok();
    }

    /**
     * 设置牌的增强，正确处理石头牌转换（委托 {@link Card#applyEnhancement}）。
     *
     * <p>真版语义（[Reddit](https://www.reddit.com/r/balatro/comments/1bn9dpi/) +
     * [Stone cards Wiki](https://balatrowiki.org/w/Stone_cards)）：增强塔罗替换原增强；
     * 石头牌被转为非 stone 增强时不再是石头，须恢复合法 rank/suit。REF engine.js 此处未恢复
     * （REF bug：石头牌转其他增强后 rank/suit 仍 0/-1，isStone 按 rank==0 仍判石头但 enh 已非 stone，
     * HandEval 按 enh!=stone 当普通牌，rank=0/suit=-1 参与判定致混乱）。
     *
     * @param card 目标牌
     * @param enh  新增强（含 STONE）
     */
    private static void applyEnhancement(Card card, Data.Enhancement enh) {
        card.applyEnhancement(enh);
    }

    private static List<Card> targets(RunState s, List<Integer> targetIds, boolean inRound, int max, boolean exact) {
        if (!inRound) return null;
        if (targetIds == null) targetIds = List.of();
        if (targetIds.size() > max) return null;
        if (exact && targetIds.size() != max) return null;
        // P14 性能：≤max(≤3) 个 id 的查重改两两比较（原 HashSet 构造逐次装箱分配）
        for (int i = 0; i < targetIds.size(); i++) {
            for (int j = i + 1; j < targetIds.size(); j++) {
                if (targetIds.get(i).intValue() == targetIds.get(j).intValue()) return null;
            }
        }
        List<Card> arr = new ArrayList<>();
        for (int id : targetIds) {
            Card card = null;
            for (Card x : s.hand) if (x.id() == id) { card = x; break; }
            if (card == null) return null;
            arr.add(card);
        }
        return arr;
    }

    /** 版本抽取池（wheel/aura 用，对齐原版：1/3 均匀，非商店权重）。 */
    private static final List<String> EDITION_POOL = List.of("foil", "holo", "poly");

    /** 消耗品使用需求元数据：目标张数区间（exact 需求 min==max）+ 是否仅限出牌回合。 */
    public record UseInfo(int minTargets, int maxTargets, boolean roundOnly) {
        /** 是否需要手牌目标（maxTargets>0）。 */
        public boolean needsTargets() { return maxTargets > 0; }
    }

    /** 无目标且不限回合（星球/非目标塔罗/非回合限幻灵）。 */
    private static final UseInfo UI_NONE = new UseInfo(0, 0, false);
    /** 无目标但仅限回合（作用于随机/全体手牌的幻灵，apply 内 inRoundHand 分支把关）。 */
    private static final UseInfo UI_ROUND_ONLY = new UseInfo(0, 0, true);

    /**
     * 消耗品使用需求（UI 预检/提示的单一事实源）。行为真源仍是各 apply 分支的
     * targets(max, exact) 与 inRoundHand 调用——两者由 ConsumablesUseInfoTest
     * 的行为边界锁强制一致（改任一侧都会红）。needsTargets()==true 的 key 集合与
     * 原 NEED_ROUND_TARGET 集合逐字相同，非回合预检行为不变。
     */
    public static UseInfo useInfo(String key) {
        return switch (key) {
            case "magician", "empress", "hierophant", "strength", "hanged" -> new UseInfo(1, 2, true);
            case "lovers", "chariot", "justice", "devil", "tower",
                    "talisman", "dejavu", "trance", "medium", "aura", "cryptid" -> new UseInfo(1, 1, true);
            case "death" -> new UseInfo(2, 2, true);
            case "star", "moon", "sun", "world" -> new UseInfo(1, 3, true);
            case "familiar", "grim", "incantation", "sigil", "ouija", "immolate" -> UI_ROUND_ONLY;
            default -> UI_NONE;
        };
    }

    /**
     * 该消耗品在本局的<b>生效</b>使用需求（UI 预检用）：愚人（fool）复制上一张
     * 塔罗/星球（lastTarotPlanet），其生效需求随被复制者动态变化——上一张是目标类
     * 塔罗时，愚人同样需要选中目标（引擎递归 apply 会以相同 targetIds 走内层分支，
     * 空目标即报「请选择 N 张手牌」）。其余 key 与 {@link #useInfo} 一致。
     */
    public static UseInfo effectiveUseInfo(RunState s, String key) {
        if (key.equals("fool") && s.lastTarotPlanet != null) {
            UseInfo inner = useInfo(s.lastTarotPlanet.key);
            if (inner.needsTargets()) return inner;
        }
        return useInfo(key);
    }

    private static Result apply(RunState s, Consumable c, List<Integer> targetIds, boolean inRound) {
        // P14 性能：一次性流（名字内嵌递增 useSeq 永不复现）分段折叠建流，
        // 零字符串物化/零缓存插入，与原 stream("use:"+...) 逐位等价（守门测试锁定）。
        Rng.Stream st = s.streamUse(c.key);

        // 需指定手牌目标的消耗品只能在出牌回合使用（提前给出明确提示，避免误导性报错）
        if (!inRound && useInfo(c.key).needsTargets()) {
            return Result.err(Lang.t("err.needs_hand_target"));
        }

        if (c.kind.equals("planet")) {
            Data.Planet p = Data.Planet.byKey(c.key);
            s.levelUpHand(p.hand, 1);
            s.msg(Lang.t("msg.hand_level_up", p.displayName(), p.hand.displayName()));
            return Result.ok();
        }

        if (c.kind.equals("tarot")) {
            switch (c.key) {
                case "fool": {
                    RunState.TarotPlanet last = s.lastTarotPlanet;
                    if (last == null || (last.kind.equals("tarot") && last.key.equals("fool")))
                        return Result.err(Lang.t("err.nothing_to_copy"));
                    return apply(s, new Consumable(last.kind, last.key), targetIds, inRound);
                }
                case "magician", "empress", "hierophant": {
                    List<Card> t = targets(s, targetIds, inRound, 2, false);
                    if (t == null || t.isEmpty()) return Result.err(Lang.t("err.select_up_to_2"));
                    Data.Enhancement enh = c.key.equals("magician") ? Data.Enhancement.LUCKY
                            : c.key.equals("empress") ? Data.Enhancement.MULT : Data.Enhancement.BONUS;
                    for (Card card : t) { applyEnhancement(card, enh); card.setFacedown(false); }
                    return Result.ok();
                }
                case "priestess": {
                    for (int i = 0; i < 2; i++) {
                        Data.Planet p = st.pick(Data.PLANETS);
                        if (!s.addConsumableKey("planet", p.key)) break;
                    }
                    return Result.ok();
                }
                case "emperor": {
                    // REF 语义：全池抽取（消耗流），抽中 emperor 跳过；R123 扩展：禁入塔罗同样跳过
                    // （标准局无禁入 → 与 REF 取流逐字一致，种子复现不变）
                    for (int i = 0; i < 2; i++) {
                        Data.Tarot t = st.pick(Data.TAROTS);
                        if (t.key.equals("emperor") || s.mods.bannedTarots.contains(t.key)) continue;
                        if (!s.addConsumableKey("tarot", t.key)) break;
                    }
                    return Result.ok();
                }
                case "lovers", "chariot", "justice", "devil", "tower": {
                    List<Card> t = targets(s, targetIds, inRound, 1, true);
                    if (t == null) return Result.err(Lang.t("err.select_1"));
                    Data.Enhancement enh = switch (c.key) {
                        case "lovers" -> Data.Enhancement.WILD;
                        case "chariot" -> Data.Enhancement.STEEL;
                        case "justice" -> Data.Enhancement.GLASS;
                        case "devil" -> Data.Enhancement.GOLD;
                        default -> Data.Enhancement.STONE;
                    };
                    applyEnhancement(t.get(0), enh);
                    t.get(0).setFacedown(false);
                    return Result.ok();
                }
                case "hermit": {
                    long g = Math.min(20, Math.max(0, s.money));
                    s.gainMoney(g);
                    return Result.ok();
                }
                case "wheel": {
                    List<JokerInstance> noEdition = new ArrayList<>();
                    for (JokerInstance j : s.jokers) if (j.edition == null) noEdition.add(j);
                    if (noEdition.isEmpty()) return Result.err(Lang.t("err.no_edition_target"));
                    double pch = Boolean.TRUE.equals(s.flags.get("doubleProb")) ? 0.5 : 0.25;
                    if (st.chance(pch)) {
                        JokerInstance j = st.pick(noEdition);
                        // 对齐原版：版本为 1/3 均匀抽取（此前误用商店权重 50/35/15）
                        String e = st.pick(EDITION_POOL);
                        j.edition = parseEdition(e);
                        s.msg(Lang.t("msg.wheel_hit", j.def.displayName(), editionName(e)));
                    } else s.msg(Lang.t("msg.wheel_miss"));
                    return Result.ok();
                }
                case "strength": {
                    List<Card> t = targets(s, targetIds, inRound, 2, false);
                    if (t == null || t.isEmpty()) return Result.err(Lang.t("err.select_up_to_2"));
                    for (Card card : t) {
                        if (card.rank() >= 2 && card.rank() < 14) card.setRank(card.rank() + 1);
                        card.setFacedown(false);
                    }
                    return Result.ok();
                }
                case "hanged": {
                    List<Card> t = targets(s, targetIds, inRound, 2, false);
                    if (t == null || t.isEmpty()) return Result.err(Lang.t("err.select_up_to_2"));
                    for (Card card : t) { s.destroyCard(card); }
                    cn.quotidietium.balatro.engine.Engine.refillHand(s);
                    return Result.ok();
                }
                case "death": {
                    List<Card> t = targets(s, targetIds, inRound, 2, true);
                    if (t == null) return Result.err(Lang.t("err.select_exactly_2"));
                    Card src = t.get(1), dst = t.get(0);
                    dst.setRank(src.rank()); dst.setSuit(src.suit()); dst.setEnh(src.enh());
                    dst.setEdition(src.edition()); dst.setSeal(src.seal()); dst.setFacedown(false);
                    return Result.ok();
                }
                case "temperance": {
                    long sum = 0;
                    for (JokerInstance j : s.jokers) sum += s.sellValue(j);
                    s.gainMoney(Math.min(50, sum));
                    return Result.ok();
                }
                case "star", "moon", "sun", "world": {
                    List<Card> t = targets(s, targetIds, inRound, 3, false);
                    if (t == null || t.isEmpty()) return Result.err(Lang.t("err.select_up_to_3"));
                    int suit = switch (c.key) { case "star" -> 3; case "moon" -> 2; case "sun" -> 1; default -> 0; };
                    for (Card card : t) { if (card.enh() != Data.Enhancement.STONE) card.setSuit(suit); card.setFacedown(false); }
                    return Result.ok();
                }
                case "judgement":
                    return s.gainRandomJoker(null) ? Result.ok() : Result.err(Lang.t("err.joker_slots_full"));
                default:
                    return Result.err(Lang.t("err.tarot_unimplemented"));
            }
        }

        if (c.kind.equals("spectral")) {
            switch (c.key) {
                case "familiar", "grim", "incantation": {
                    if (!inRoundHand(s)) return Result.err(Lang.t("err.needs_round"));
                    destroyRandomHandCards(s, st, 1);
                    int n; List<Integer> ranks;
                    if (c.key.equals("familiar")) { n = 3; ranks = List.of(11, 12, 13); }
                    else if (c.key.equals("grim")) { n = 2; ranks = List.of(14); }
                    else { n = 4; ranks = List.of(2, 3, 4, 5, 6, 7, 8, 9, 10); }
                    for (int i = 0; i < n; i++) {
                        Card card = s.randomPlayingCard();
                        card.setRank(st.pick(ranks));
                        card.setEnh(Data.ENHANCEMENTS.get(st.range(0, Data.ENHANCEMENTS.size() - 1)));
                        s.addCardToDeck(card); // R130：统一入口（触发 onCardAdded）
                        s.hand.add(card);
                    }
                    trimHand(s);
                    return Result.ok();
                }
                case "talisman", "dejavu", "trance", "medium": {
                    List<Card> t = targets(s, targetIds, inRound, 1, true);
                    if (t == null) return Result.err(Lang.t("err.select_1"));
                    Data.Seal seal = switch (c.key) {
                        case "talisman" -> Data.Seal.GOLD; case "dejavu" -> Data.Seal.RED;
                        case "trance" -> Data.Seal.BLUE; default -> Data.Seal.PURPLE;
                    };
                    t.get(0).setSeal(seal); t.get(0).setFacedown(false);
                    return Result.ok();
                }
                case "aura": {
                    List<Card> t = targets(s, targetIds, inRound, 1, true);
                    if (t == null) return Result.err(Lang.t("err.select_1"));
                    // 对齐原版：版本为 1/3 均匀抽取（此前误用商店权重 50/35/15）
                    t.get(0).setEdition(parseEdition(st.pick(EDITION_POOL))); t.get(0).setFacedown(false);
                    return Result.ok();
                }
                case "wraith":
                    s.money = 0; s.gainRandomJoker(2); return Result.ok();
                case "sigil": {
                    if (!inRoundHand(s)) return Result.err(Lang.t("err.needs_round"));
                    int suit = st.range(0, 3);
                    for (Card card : s.hand) if (card.enh() != Data.Enhancement.STONE) card.setSuit(suit);
                    return Result.ok();
                }
                case "ouija": {
                    if (!inRoundHand(s)) return Result.err(Lang.t("err.needs_round"));
                    int rank = st.range(2, 14);
                    for (Card card : s.hand) if (card.enh() != Data.Enhancement.STONE) card.setRank(rank);
                    // R137 真版：手牌上限 -1 为整局永久（写入 handSizePerm；真版即时生效，
                    // 当前回合的补牌口径同步下调，下限 1 与 startRound 的 Math.max(1,·) 一致）
                    s.handSizePerm -= 1;
                    s.handSizeRound = Math.max(1, s.handSizeRound - 1);
                    return Result.ok();
                }
                case "hex": {
                    List<JokerInstance> editable = new ArrayList<>();
                    for (JokerInstance j : s.jokers) if (!j.eternal) editable.add(j);
                    if (editable.isEmpty()) return Result.err(Lang.t("err.no_joker"));
                    JokerInstance keep = st.pick(editable);
                    // R137 真版：任意途径销毁格罗米歇尔都解锁卡文迪什（本处 removeIf 直删
                    // 绕过 destroyJoker，REF 同 bug 不置 grosDead——被销毁者含之则补置）
                    boolean grosHit = false;
                    for (JokerInstance j : s.jokers) {
                        if (j != keep && !j.eternal && j.def.key().equals("grossmichel")) grosHit = true;
                    }
                    s.jokers.removeIf(j -> j != keep && !j.eternal);
                    if (grosHit) s.grosDead = true;
                    // R128 对齐真版（Spectral Wiki："Add Polychrome to a random Joker, destroy all
                    // other Jokers"）——REF 误为 NEGATIVE（R17 逐行对 REF 的经典盲区）。
                    keep.edition = Data.Edition.POLY;
                    return Result.ok();
                }
                case "ankh": {
                    List<JokerInstance> copyable = new ArrayList<>();
                    for (JokerInstance j : s.jokers) if (!j.eternal) copyable.add(j);
                    if (copyable.isEmpty()) return Result.err(Lang.t("err.no_joker"));
                    JokerInstance src = st.pick(copyable);
                    // R137 真版：同 hex——removeIf 直删须补置 grosDead（详见 hex 注释）
                    boolean grosHit = false;
                    for (JokerInstance j : s.jokers) {
                        if (j != src && !j.eternal && j.def.key().equals("grossmichel")) grosHit = true;
                    }
                    s.jokers.removeIf(j -> j != src && !j.eternal);
                    if (grosHit) s.grosDead = true;
                    // R114 对齐真版：贴纸（永恒/易腐/租赁）随复制保留；负片版本不被 ankh 复制
                    // （Ankh Wiki/Steam：Ankh duplicate of a Negative joker loses Negative）
                    Data.Edition ed = src.edition == Data.Edition.NEGATIVE ? null : src.edition;
                    s.duplicateJoker(src, ed);
                    return Result.ok();
                }
                case "cryptid": {
                    List<Card> t = targets(s, targetIds, inRound, 1, true);
                    if (t == null) return Result.err(Lang.t("err.select_1"));
                    for (int i = 0; i < 2; i++) {
                        Card copy = s.cloneCard(t.get(0));
                        // R130：统一入口（触发 onCardAdded，如全息海报）
                        s.addCardToDeck(copy); s.hand.add(copy);
                    }
                    trimHand(s);
                    return Result.ok();
                }
                case "immolate": {
                    if (!inRoundHand(s)) return Result.err(Lang.t("err.needs_round"));
                    destroyRandomHandCards(s, st, 5);
                    s.gainMoney(20);
                    return Result.ok();
                }
                case "soul":
                    return s.gainRandomJoker(3) ? Result.ok() : Result.err(Lang.t("err.joker_slots_full"));
                case "blackhole":
                    for (Data.HandType h : Data.HandType.values()) s.levelUpHand(h, 1);
                    s.msg(Lang.t("msg.black_hole"));
                    return Result.ok();
                case "ectoplasm": {
                    List<JokerInstance> editable = new ArrayList<>();
                    for (JokerInstance j : s.jokers) if (j.edition == null) editable.add(j);
                    if (editable.isEmpty()) return Result.err(Lang.t("err.no_joker"));
                    st.pick(editable).edition = Data.Edition.NEGATIVE;
                    // R137 真版：同 ouija——永久手牌上限 -1（跨回合存活 + 当前回合即时生效）
                    s.handSizePerm -= 1;
                    s.handSizeRound = Math.max(1, s.handSizeRound - 1);
                    return Result.ok();
                }
                default:
                    return Result.err(Lang.t("err.spectral_unimplemented"));
            }
        }
        return Result.err(Lang.t("err.unknown_consumable"));
    }

    private static boolean inRoundHand(RunState s) {
        return s.phase == Phase.ROUND && !s.hand.isEmpty();
    }

    private static void destroyRandomHandCards(RunState s, Rng.Stream st, int n) {
        Rng.Stream ds = s.stream("destroyhand");
        for (int i = 0; i < n && !s.hand.isEmpty(); i++) {
            Card v = ds.pick(s.hand);
            s.destroyCard(v);
        }
        // 对齐原版：销毁后补满手牌（drawUpTo）。缺失会使手牌停留短缺状态，
        // 且 wheel Boss 回合跳过补牌即跳过 wheel 流消耗，造成后续流分歧。
        cn.quotidietium.balatro.engine.Engine.refillHand(s);
    }

    private static void trimHand(RunState s) {
        // 对齐原版：允许手牌临时溢出至上限 +3（新生成/复制的牌得以保留），
        // 此前按上限硬裁剪，满手时 cryptid 的复制会直接进弃牌堆（效果作废）。
        while (s.hand.size() > s.handSizeRound + 3) {
            Card c = s.hand.remove(s.hand.size() - 1);
            s.discardPile.add(c);
        }
    }

    private static Data.Edition parseEdition(String e) {
        return switch (e) {
            case "foil" -> Data.Edition.FOIL;
            case "holo" -> Data.Edition.HOLO;
            case "poly" -> Data.Edition.POLY;
            case "negative" -> Data.Edition.NEGATIVE;
            default -> null;
        };
    }

    private static String editionName(String e) {
        return switch (e) {
            case "foil", "holo", "poly", "negative" -> Lang.t("edition." + e + ".name"); default -> e;
        };
    }
}
