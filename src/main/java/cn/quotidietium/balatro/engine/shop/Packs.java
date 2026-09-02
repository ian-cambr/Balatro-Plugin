package cn.quotidietium.balatro.engine.shop;

import cn.quotidietium.balatro.engine.Card;
import cn.quotidietium.balatro.engine.Consumable;
import cn.quotidietium.balatro.engine.Data;
import cn.quotidietium.balatro.engine.JokerInstance;
import cn.quotidietium.balatro.engine.Phase;
import cn.quotidietium.balatro.engine.Rng;
import cn.quotidietium.balatro.engine.RunState;
import cn.quotidietium.balatro.i18n.Lang;
import java.util.ArrayList;
import java.util.List;

/**
 * 补充包开启/选择/跳过，移植自 {@code engine.js} openPack/pickPackCard/skipPack。
 */
public final class Packs {

    private Packs() {
    }

    public static final class PackCard {
        public String kind; // tarot/planet/playing/joker/spectral
        public String key;       // tarot/planet/spectral
        public Card card;        // playing
        public JokerInstance joker; // joker
        public String name, desc;
        public boolean taken;
    }

    public static final class Session {
        public Data.Pack def;
        public List<PackCard> cards = new ArrayList<>();
        public int left; // 还能选几张
    }

    /** 开启补充包（进入 PACK 阶段）。 */
    public static void open(RunState s, Data.Pack packDef) {
        Rng.Stream st = s.streamPack(packDef.key); // P14：一次性流分段折叠（见 RunState.streamPack）
        Session sess = new Session();
        sess.def = packDef;
        for (int i = 0; i < packDef.size; i++) {
            sess.cards.add(genPackCard(s, st, packDef.type));
        }
        sess.left = packDef.choose;
        s.packReturn = s.phase == Phase.PACK ? (s.packReturn != null ? s.packReturn : Phase.SHOP) : s.phase;
        s.pack = sess;
        s.phase = Phase.PACK;
        // 幻觉小丑（P14：P10 池化快照补点）
        List<JokerInstance> openSnap = s.acquireJokerSnap();
        try {
            for (int i = 0; i < openSnap.size(); i++) {
                JokerInstance j = openSnap.get(i);
                if (!j.debuff) j.def.onPackOpen(s, j);
            }
        } finally {
            s.releaseJokerBuffer();
        }
    }

    private static PackCard genPackCard(RunState s, Rng.Stream st, Data.PackType type) {
        PackCard c = new PackCard();
        switch (type) {
            case ARCANA -> {
                Data.Tarot t = st.pick(Data.TAROTS);
                c.kind = "tarot"; c.key = t.key; c.name = t.displayName(); c.desc = t.desc();
            }
            case CELESTIAL -> {
                Data.Planet p = null;
                if (s.vouchers.contains("telescope")) {
                    Data.HandType most = s.mostPlayedType();
                    if (most == null) most = Data.HandType.HIGH;
                    for (Data.Planet x : Data.Planet.values()) if (x.hand == most) { p = x; break; }
                }
                if (p == null) p = st.pick(Data.PLANETS);
                c.kind = "planet"; c.key = p.key; c.name = p.displayName(); c.desc = p.desc();
            }
            case STANDARD -> {
                Card card = s.randomPlayingCard();
                if (st.chance(0.4)) {
                    card.setEnh(Data.ENHANCEMENTS.get(st.range(0, Data.ENHANCEMENTS.size() - 1)));
                }
                // 版本均匀 1/3（对齐 REF engine.js:1433 s.pick(["foil","holo","poly"])）；
                // 此前误用 weightedEdition（50/35/15），分布错误且破坏种子复现。
                if (st.chance(0.2)) {
                    Data.Edition[] eds = {Data.Edition.FOIL, Data.Edition.HOLO, Data.Edition.POLY};
                    card.setEdition(eds[st.range(0, 2)]);
                }
                if (st.chance(0.2)) {
                    card.setSeal(Data.SEALS.get(st.range(0, Data.SEALS.size() - 1)));
                }
                c.kind = "playing"; c.card = card; c.name = s.cardName(card); c.desc = Lang.t("item.playing_card");
            }
            case BUFFOON -> {
                Shop.CardItem item = Shop.makeJokerItem(s, null, null);
                // 小丑池耗尽时 makeJokerItem 回退为塔罗（joker 字段为 null）：
                // 必须沿用回退商品的 kind/key，否则 kind=joker + joker=null 的毒数据
                // 会在 pick 时把 null 塞进 jokers 列表，后续 computeFlags 遍历 NPE。
                // （拥有某稀有度全部小丑理论可达：负片版本小丑不占槽位。）
                if (item.joker != null) {
                    c.kind = "joker"; c.joker = item.joker;
                } else {
                    c.kind = item.kind; c.key = item.key;
                }
                c.name = item.name; c.desc = item.desc;
            }
            case SPECTRAL -> {
                // R128 对齐真版（Spectral Wiki）：灵魂/黑洞仅在幽灵包以 ~0.3% 概率出现
                Data.Spectral sp;
                if (st.chance(0.003)) sp = Data.Spectral.byKey("soul");
                else if (st.chance(0.003)) sp = Data.Spectral.byKey("blackhole");
                else {
                    List<Data.Spectral> pool = new ArrayList<>();
                    for (Data.Spectral s0 : Data.Spectral.values()) {
                        if (!Data.SPECIAL_SPECTRALS.contains(s0.key)) pool.add(s0);
                    }
                    sp = st.pick(pool);
                }
                c.kind = "spectral"; c.key = sp.key; c.name = sp.displayName(); c.desc = sp.desc();
            }
        }
        return c;
    }

    /** 从包中选第 idx 张。 */
    public static boolean pick(RunState s, int idx) {
        Session pack = s.pack;
        if (pack == null || idx < 0 || idx >= pack.cards.size()) return false;
        PackCard item = pack.cards.get(idx);
        if (item.taken) return false;
        switch (item.kind) {
            case "joker" -> {
                if (item.joker == null) return false; // 防御：毒数据绝不入 jokers 列表
                // negative 版本小丑自带 +1 槽：满槽时仍可选（对齐真版，见 RunState.gainJoker 注释）。
                boolean neg = item.joker.edition == cn.quotidietium.balatro.engine.Data.Edition.NEGATIVE;
                if (neg ? s.jokerSpace() < 0 : s.jokerSpace() <= 0) return false;
                s.jokers.add(item.joker);
                s.msg(Lang.t("msg.joker_gained", item.name));
            }
            case "playing" -> {
                s.addCardToDeck(item.card);
                s.msg(Lang.t("msg.deck_added", item.name));
            }
            default -> {
                if (!s.addConsumableKey(item.kind, item.key)) return false;
                s.msg(Lang.t("msg.gained", item.name));
            }
        }
        item.taken = true;
        pack.left--;
        if (pack.left <= 0) {
            s.pack = null;
            s.phase = s.packReturn != null ? s.packReturn : Phase.SHOP;
            s.packReturn = null;
        }
        cn.quotidietium.balatro.engine.Engine.recomputeFlags(s);
        return true;
    }

    /** 跳过补充包。 */
    public static boolean skip(RunState s) {
        if (s.pack == null) return false;
        s.pack = null;
        s.phase = s.packReturn != null ? s.packReturn : Phase.SHOP;
        s.packReturn = null;
        List<JokerInstance> skipSnap = s.acquireJokerSnap(); // P14：P10 池化快照补点
        try {
            for (int i = 0; i < skipSnap.size(); i++) {
                JokerInstance j = skipSnap.get(i);
                if (!j.debuff) j.def.onPackSkip(s, j);
            }
        } finally {
            s.releaseJokerBuffer();
        }
        s.msg(Lang.t("msg.pack_skipped"));
        return true;
    }

}
