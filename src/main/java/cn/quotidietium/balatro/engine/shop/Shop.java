package cn.quotidietium.balatro.engine.shop;

import cn.quotidietium.balatro.engine.Card;
import cn.quotidietium.balatro.engine.Data;
import cn.quotidietium.balatro.engine.Joker;
import cn.quotidietium.balatro.engine.JokerInstance;
import cn.quotidietium.balatro.engine.Rng;
import cn.quotidietium.balatro.engine.RunState;
import cn.quotidietium.balatro.engine.joker.JokerRegistry;
import cn.quotidietium.balatro.i18n.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商店生成与交易，移植自 {@code engine.js} 商店段（genShop/makeJokerItem/buy/reroll 等）。
 * 小丑池与稀有度取自 {@link JokerRegistry}（含元数据），故 0.4.0 小丑全齐后即可正确生成。
 *
 * <p>0.2.0：本类提供纯逻辑；全息商店视图与回合流程接入（胜出后进商店）随后补齐。
 */
public final class Shop {

    // ---- P9 性能：静态共享池（内容只由静态数据决定，pick 只读）----
    /** 商店包池：PACKS 去掉 SPECTRAL（genShop 原地筛选的产物，顺序=PACKS 声明序）。 */
    private static final List<Data.Pack> SHOP_PACK_POOL = buildShopPackPool();
    /** 幻灵商店池：SPECTRALS 去掉 SPECIAL_SPECTRALS（genShopItem case4 无禁入时的等价池）。 */
    private static final List<Data.Spectral> SHOP_SPECTRAL_POOL = buildShopSpectralPool();

    private static List<Data.Pack> buildShopPackPool() {
        List<Data.Pack> pool = new ArrayList<>();
        for (Data.Pack p : Data.PACKS) {
            if (p.type != Data.PackType.SPECTRAL) pool.add(p);
        }
        return List.copyOf(pool);
    }

    private static List<Data.Spectral> buildShopSpectralPool() {
        List<Data.Spectral> pool = new ArrayList<>();
        for (Data.Spectral s0 : Data.SPECTRALS) {
            if (!Data.SPECIAL_SPECTRALS.contains(s0.key)) pool.add(s0);
        }
        return List.copyOf(pool);
    }

    /** 按稀有度分桶的 ORDERED 视图（桶内保持原版顺序，与原「逐个筛选」产出的池序一致）。 */
    private static final List<List<Joker>> JOKERS_BY_RARITY = buildJokersByRarity();

    private static List<List<Joker>> buildJokersByRarity() {
        // 桶大小先数一遍，再精确装桶（每个桶单次分配）
        int[] counts = new int[4];
        for (Joker j : JokerRegistry.allJokersOrdered()) {
            counts[JokerRegistry.rarityOf(j.key())]++;
        }
        @SuppressWarnings("unchecked")
        ArrayList<Joker>[] buckets = new ArrayList[4];
        for (int r = 0; r < 4; r++) buckets[r] = new ArrayList<>(counts[r]);
        for (Joker j : JokerRegistry.allJokersOrdered()) {
            buckets[JokerRegistry.rarityOf(j.key())].add(j);
        }
        List<List<Joker>> out = new ArrayList<>(4);
        for (int r = 0; r < 4; r++) out.add(List.copyOf(buckets[r]));
        return out;
    }

    private Shop() {
    }

    // ---- 商品数据 ----
    public static final class CardItem {
        public String kind; // joker/tarot/planet/spectral/playing
        public JokerInstance joker; // kind=joker
        public Card card;           // kind=playing
        public String key;          // tarot/planet/spectral 的 key
        public String name, desc;
        public long price;
        public boolean sold;

        public CardItem copy() {
            CardItem c = new CardItem();
            c.kind = kind; c.joker = joker; c.card = card; c.key = key;
            c.name = name; c.desc = desc; c.price = price; c.sold = sold;
            return c;
        }
    }

    public static final class PackItem {
        public Data.Pack pack;
        public String name, desc;
        public long price;
        public boolean sold;
    }

    public static final class VoucherItem {
        public Data.Voucher voucher;
        public String name, desc;
        public long price;
        public boolean sold;
    }

    public static final class ShopData {
        // P9 性能：按典型规模预置（卡位 2~4 / 包 2 / 券 1~2），消除首元素扩容
        public List<CardItem> cards = new ArrayList<>(4);
        public List<PackItem> packs = new ArrayList<>(2);
        /** 商店陈列的优惠券列表（可含多张：voucher 标签每叠加一次追加一张额外券）。 */
        public List<VoucherItem> vouchers = new ArrayList<>(2);
        public int rerollCount;
        public int freeRerolls;
    }

    // ---- 价格 / 负担 ----

    public static long shopPrice(RunState s, long base) {
        long p = base;
        if (hasVoucher(s, "liquidation")) p = (long) Math.ceil(p * 0.5);
        else if (hasVoucher(s, "clearance")) p = (long) Math.ceil(p * 0.75);
        if (s.mods.shopDiscount != 0) p = (long) Math.ceil(p * s.mods.shopDiscount);
        // 通胀（真版 R123）：每次购买永久 +$1（重掷不涨）；旧 mods.inflation 保留引擎兼容
        if (s.mods.inflationPerBuy || s.mods.inflation) p += s.inflation;
        return Math.max(1, p);
    }

    public static long jokerCost(JokerInstance j) {
        int add = j.edition == Data.Edition.FOIL ? 2 : j.edition == Data.Edition.HOLO ? 3
                : j.edition == Data.Edition.POLY ? 5 : j.edition == Data.Edition.NEGATIVE ? 5 : 0;
        return Math.max(1, j.def.cost() + add);
    }

    public static boolean canAfford(RunState s, long price) {
        long credit = s.flags != null && s.flags.get("credit") instanceof Number
                ? ((Number) s.flags.get("credit")).longValue() : 0;
        // 饱和加法：无尽模式金钱可饱和至 Long.MAX_VALUE，直接 + credit 会环绕成负数
        // （后果是「买不起」的误判——方向安全但语义错误），对齐 money 全域的饱和约定。
        return RunState.satAdd(s.money, credit) >= price;
    }

    private static boolean hasVoucher(RunState s, String key) {
        return s.vouchers.contains(key);
    }

    // ---- 开店 / 生成 ----

    public static void openShop(RunState s) {
        s.phase = cn.quotidietium.balatro.engine.Phase.SHOP;
        genShop(s);
    }

    /** 有禁入包（真版煎蛋卷/无丑牌）时的现筛包池（顺序=PACKS 声明序）。 */
    private static List<Data.Pack> buildBannedFilteredPackPool(RunState s) {
        List<Data.Pack> pool = new ArrayList<>(Data.PACKS.size());
        for (Data.Pack p : Data.PACKS) {
            if (p.type == Data.PackType.SPECTRAL) continue;
            if (s.mods.bannedPacks.contains(p.key)) continue; // R123 真版禁入包（标准/丑牌包）
            pool.add(p);
        }
        return pool;
    }

    private static void genShop(RunState s) {
        Rng.Stream st = s.streamRound("shopgen"); // P15：每回合一次性流（分段折叠，零物化/零缓存）
        List<CardItem> cards = genShopCards(s);

        // 补充包 2 个
        // P9 性能：无禁入包时直接用静态共享池（内容/顺序与原地筛选一致）；有禁入才现筛
        List<PackItem> packs = new ArrayList<>(2);
        List<Data.Pack> packPool = s.mods.bannedPacks.isEmpty()
                ? SHOP_PACK_POOL : buildBannedFilteredPackPool(s);
        for (int i = 0; i < 2; i++) {
            Data.Pack p = st.pick(packPool);
            if (s.nextShop.get("etherealPack") != null) {
                for (Data.Pack x : Data.PACKS) if (x.type == Data.PackType.SPECTRAL) p = x;
                s.nextShop.remove("etherealPack");
            }
            // R130 真版：天文学家使天体包免费（"All Planet cards and Celestial Packs are free"）
            // R130 真版：天文学家（小丑）使天体包免费——经 flags.freePlanets（R142 修复：
            // 原误查 vouchers.contains("astronomer")，券集合永远不含该小丑 key，效果死亡）
            boolean free = s.nextShop.get("coupon") != null
                    || (p.type == Data.PackType.CELESTIAL && s.flags != null
                            && Boolean.TRUE.equals(s.flags.get("freePlanets")));
            PackItem pi = new PackItem();
            pi.pack = p; pi.name = p.displayName(); pi.desc = Lang.t("shop.pack_size", p.size, p.choose);
            pi.price = free ? 0 : shopPrice(s, p.cost);
            packs.add(pi);
        }

        // 优惠券：基础一张 + voucher 标签每叠加一次追加一张额外券（对齐真版
        // "Adds a Voucher to the next Shop. Can be stacked" — balatrowiki.org/w/Voucher_Tag）。
        // REF 原版网页未实现 extraVoucher 消费（只置位），此处按真版补齐。
        // P12 负结果（已回退）：券池两趟虚拟抽取省 ~184B/店但时间净回归（同 makeJokerItem 结论）。
        List<VoucherItem> vouchers = new ArrayList<>(2);
        List<Data.Voucher> avail = new ArrayList<>(Data.VOUCHERS.size());
        for (Data.Voucher v : Data.VOUCHERS) {
            if (s.vouchers.contains(v.key)) continue;
            if (v.requires != null && !s.vouchers.contains(v.requires)) continue;
            // 禁入券（真版煎蛋卷：种子基金/摇钱树不进券池）——R108 对齐真版
            if (s.mods.bannedVouchers.contains(v.key)) continue;
            avail.add(v);
        }
        // extraVoucher 计数（标签每次叠加 +1；0 表示无额外券）
        int extraVouchers = 0;
        Object ev = s.nextShop.get("extraVoucher");
        if (ev instanceof Number) extraVouchers = ((Number) ev).intValue();
        int totalVouchers = 1 + extraVouchers; // 基础 1 张 + 额外
        // 每张券从剩余 avail 中随机取一张并移除（不重复），avail 不足则少几张
        for (int vi = 0; vi < totalVouchers && !avail.isEmpty(); vi++) {
            Data.Voucher v = st.pick(avail);
            avail.remove(v); // 同一商店内券不重复
            VoucherItem item = new VoucherItem();
            item.voucher = v; item.name = v.displayName(); item.desc = v.desc();
            item.price = shopPrice(s, 10);
            vouchers.add(item);
        }
        s.nextShop.remove("extraVoucher");

        if (s.nextShop.get("coupon") != null) {
            for (CardItem c : cards) c.price = 0;
            s.nextShop.remove("coupon");
        }
        s.nextShop.remove("freeTarot");
        s.nextShop.remove("freePlanet");

        ShopData shop = new ShopData();
        shop.cards = cards;
        shop.packs = packs;
        shop.vouchers = vouchers;
        shop.rerollCount = 0;
        int freeRerolls = (s.flags != null && s.flags.get("freeRerolls") instanceof Number
                ? ((Number) s.flags.get("freeRerolls")).intValue() : 0);
        if (s.nextShop.get("freeReroll") != null) freeRerolls += 99;
        if (s.mods.freeReroll) freeRerolls += 99;
        shop.freeRerolls = freeRerolls;
        s.nextShop.remove("freeReroll");
        s.shop = shop;
    }

    private static List<CardItem> genShopCards(RunState s) {
        Rng.Stream st = s.stream("shopcards");
        List<CardItem> items = new ArrayList<>(s.shopSlots);
        int slots = s.shopSlots;

        // P9 性能：权重表用两个平置 int 数组承载（原实现每次 new ArrayList + 4~5 个 int[]
        // 装箱；kindCode: 0 joker,1 tarot,2 planet,3 playing,4 spectral）
        int[] wkinds = new int[5];
        int[] wvals = new int[5];
        int wn = 0;
        wkinds[wn] = 0; wvals[wn] = 70; wn++;
        wkinds[wn] = 1; wvals[wn] = hasVoucher(s, "tarott") ? 30 : hasVoucher(s, "tarotm") ? 15 : 8; wn++;
        wkinds[wn] = 2; wvals[wn] = hasVoucher(s, "planett") ? 30 : hasVoucher(s, "planetm") ? 15 : 8; wn++;
        if (hasVoucher(s, "magictrick")) { wkinds[wn] = 3; wvals[wn] = 8; wn++; }
        if (s.mods.spectralInShop || hasVoucher(s, "omen")) { wkinds[wn] = 4; wvals[wn] = 4; wn++; }
        if (s.mods.noJokers) { // 原地 removeIf(kind==0) 的等价平移
            for (int i = 0; i < wn; i++) {
                if (wkinds[i] == 0) {
                    System.arraycopy(wkinds, i + 1, wkinds, i, wn - 1 - i);
                    System.arraycopy(wvals, i + 1, wvals, i, wn - 1 - i);
                    wn--;
                    break;
                }
            }
        }

        for (int i = 0; i < slots; i++) {
            int kind = weightedPick(st, wkinds, wvals, wn);
            items.add(genShopItem(s, kind, i));
        }
        // 标签保证
        Object forceRarity = s.nextShop.get("rarity");
        if (forceRarity != null && !items.isEmpty()) {
            items.set(0, makeJokerItem(s, (Integer) forceRarity, null));
            // R127 真版：罕见/稀有标签的该小丑免费（"Shop has a FREE Uncommon/Rare Joker"）
            if (Boolean.TRUE.equals(s.nextShop.get("freeFirstJoker"))) items.get(0).price = 0;
            s.nextShop.remove("rarity");
            s.nextShop.remove("freeFirstJoker");
        }
        Object forceEdition = s.nextShop.get("edition");
        if (forceEdition != null && !items.isEmpty()) {
            items.set(items.size() - 1, makeJokerItem(s, null, (String) forceEdition));
            // R127 真版：四版本标签的小丑免费（"...shop Joker is FREE and becomes ..."）
            if (Boolean.TRUE.equals(s.nextShop.get("freeLastJoker"))) items.get(items.size() - 1).price = 0;
            s.nextShop.remove("edition");
            s.nextShop.remove("freeLastJoker");
        }
        return items;
    }

    /**
     * P9 性能：平置数组的加权抽取——与 {@code Rng.Stream.weighted} 的算术逐位一致
     * （total 按**同序**逐元素 int→double 累加；恰一次 next()；按序减重、r&lt;0 即中），
     * 仅去掉 List/int[] 装箱与 lambda 分发。
     * 防御：空表 / 全权重 ≤0 时与原实现同返回塔罗（1）（非空、价格正常的稳妥默认）。
     */
    private static int weightedPick(Rng.Stream st, int[] kinds, int[] vals, int n) {
        double total = 0;
        for (int i = 0; i < n; i++) total += Math.max(0, vals[i]);
        if (total <= 0) return 1;
        double r = st.next() * total;
        for (int i = 0; i < n; i++) {
            r -= Math.max(0, vals[i]);
            if (r < 0) return kinds[i];
        }
        return kinds[n - 1];
    }

    private static CardItem genShopItem(RunState s, int kindCode, int slotIdx) {
        Rng.Stream st = s.stream("shopcards");
        switch (kindCode) {
            case 0: return makeJokerItem(s, null, null);
            case 1: {
                // P9 性能：无禁入时塔罗池即 Data.TAROTS 本身（同内容同序），直接共享；
                // 有禁入（真版易碎品）才现筛
                List<Data.Tarot> tarotPool;
                if (s.mods.bannedTarots.isEmpty()) {
                    tarotPool = Data.TAROTS;
                } else {
                    tarotPool = new ArrayList<>(Data.TAROTS.size());
                    for (Data.Tarot t0 : Data.TAROTS) {
                        if (!s.mods.bannedTarots.contains(t0.key)) tarotPool.add(t0); // R123 真版禁入
                    }
                }
                Data.Tarot t = tarotPool.isEmpty() ? null : st.pick(tarotPool);
                if (t == null) return item("tarot", "fool",
                        Data.Tarot.byKey("fool").displayName(), Data.Tarot.byKey("fool").desc(), shopPrice(s, 3));
                boolean free = s.nextShop.get("freeTarot") != null;
                return item("tarot", t.key, t.displayName(), t.desc(), free ? 0 : shopPrice(s, 3));
            }
            case 2: {
                Data.Planet p = st.pick(Data.PLANETS);
                boolean free = s.nextShop.get("freePlanet") != null
                        || (s.flags != null && Boolean.TRUE.equals(s.flags.get("freePlanets")))
                        || s.vouchers.contains("astronomer"); // 保留兼容：早期版本若经券注入同名 key（当前无来源，见 R142）
                return item("planet", p.key, p.displayName(), p.desc(), free ? 0 : shopPrice(s, 3));
            }
            case 4: {
                // P9 性能：无禁入时用静态幻灵商店池（去 SPECIAL 的 SPECTRALS，同序）；
                // 有禁入才现筛
                List<Data.Spectral> spPool;
                if (s.mods.bannedSpectrals.isEmpty()) {
                    spPool = SHOP_SPECTRAL_POOL;
                } else {
                    spPool = new ArrayList<>(SHOP_SPECTRAL_POOL.size());
                    for (Data.Spectral s0 : Data.SPECTRALS) {
                        if (s.mods.bannedSpectrals.contains(s0.key)) continue; // R123 真版禁入
                        if (Data.SPECIAL_SPECTRALS.contains(s0.key)) continue; // R128 真版：商店排除灵魂/黑洞
                        spPool.add(s0);
                    }
                }
                Data.Spectral sp = spPool.isEmpty() ? null : st.pick(spPool);
                if (sp == null) return makeJokerItem(s, null, null);
                return item("spectral", sp.key, sp.displayName(), sp.desc(), shopPrice(s, 4));
            }
            case 3: {
                Card c = s.randomPlayingCard();
                if (hasVoucher(s, "illusion")) {
                    Rng.Stream r = s.stream("illusion");
                    if (r.chance(0.4)) {
                        c.setEnh(Data.ENHANCEMENTS.get(r.range(0, Data.ENHANCEMENTS.size() - 1)));
                    }
                    if (r.chance(0.3)) {
                        Data.Edition[] eds = {Data.Edition.FOIL, Data.Edition.HOLO, Data.Edition.POLY};
                        c.setEdition(eds[r.range(0, 2)]);
                    }
                }
                CardItem it = new CardItem();
                it.kind = "playing"; it.card = c; it.name = s.cardName(c); it.desc = Lang.t("item.playing_card");
                it.price = shopPrice(s, 1);
                return it;
            }
            default: return null;
        }
    }

    private static CardItem item(String kind, String key, String name, String desc, long price) {
        CardItem it = new CardItem();
        it.kind = kind; it.key = key; it.name = name; it.desc = desc; it.price = price;
        return it;
    }

    public static CardItem makeJokerItem(RunState s, Integer forceRarity, String forceEdition) {
        Rng.Stream st = s.stream("shopjoker");
        Integer rarity = forceRarity;
        if (rarity == null) {
            double r = st.next() * 100;
            rarity = r < 70 ? 0 : r < 95 ? 1 : 2;
        }
        // P9 性能：按稀有度分桶的静态视图（桶内保持 ORDERED 序，与原逐个筛选的池序一致），
        // 免去每次全量遍历 150 个小丑的 rarityOf 查找；池按桶大小精确预置（单次分配）。
        // P12 负结果（已回退）：两趟虚拟抽取（计数→取第 n 个匹配）省 490B/次分配，但第二趟
        // 105 元素过滤 ~0.25μs/次 ×4.2 次/op ≈ +1μs——TLAB 物化 490B 仅 ~50ns，时间净回归
        // 0.838×（A/B 三对完全分离）。与 P10/P11 同源结论第三次确认：小列表物化/复用/两趟
        // 皆不敌 TLAB 新鲜分配。详细数据见 note/report/perf/2026-08-16-P12-商店池物化消灭.md。
        List<Joker> bucket = JOKERS_BY_RARITY.get(rarity);
        List<Joker> pool = new ArrayList<>(bucket.size());
        for (Joker j : bucket) {
            if (j.key().equals("cavendish") && !s.grosDead) continue;
            if (s.mods.noJokers) continue;
            // 禁入小丑（真版煎蛋卷：经济小丑不进商店池）——R108 对齐真版
            if (s.mods.bannedJokers.contains(j.key())) continue;
            if (!Boolean.TRUE.equals(s.flags.get("allowDupes"))) {
                boolean owned = false;
                for (JokerInstance o : s.jokers) if (o.def.key().equals(j.key())) { owned = true; break; }
                if (owned) continue;
            }
            pool.add(j);
        }
        if (pool.isEmpty()) {
            Data.Tarot t = st.pick(Data.TAROTS); // P9：共享 Data.TAROTS（原 List.of(values()) 每次新建）
            return item("tarot", t.key, t.displayName(), t.desc(), shopPrice(s, 3));
        }
        Joker def = st.pick(pool);
        Data.Edition edition = forceEdition != null ? parseEdition(forceEdition) : null;
        if (edition == null) {
            double chance = hasVoucher(s, "glowup") ? 0.2 : hasVoucher(s, "hone") ? 0.1 : 0.05;
            if (st.chance(chance)) edition = parseEdition(weightedEdition(st));
        }
        JokerInstance ji = new JokerInstance(def);
        ji.edition = edition;
        if (s.mods.blackStake && st.chance(0.3)) ji.eternal = true;
        if (s.mods.orangeStake && st.chance(0.3)) ji.perishable = true;
        if (s.mods.goldStake && st.chance(0.3)) ji.rental = true;
        if (s.mods.allEternal) ji.eternal = true;
        long price = shopPrice(s, jokerCost(ji));
        // R124 对齐真版：租赁小丑售价恒 $1（"costs $1 to buy"，Reddit/Steam/Stickers）；
        // REF 的 price-3 为 REF bug（$5 小丑 $2 买）。仅金注出现，标准局零影响。
        if (ji.rental) price = 1;
        CardItem it = new CardItem();
        it.kind = "joker"; it.joker = ji; it.name = JokerRegistry.nameOf(def.key());
        it.desc = def.desc(); it.price = price;
        return it;
    }

    private static String weightedEdition(Rng.Stream st) {
        double r = st.next() * 100;
        if (r < 50) return "foil";
        if (r < 85) return "holo";
        return "poly";
    }

    private static Data.Edition parseEdition(String e) {
        if (e == null) return null;
        return switch (e) {
            case "foil" -> Data.Edition.FOIL;
            case "holo" -> Data.Edition.HOLO;
            case "poly" -> Data.Edition.POLY;
            case "negative" -> Data.Edition.NEGATIVE;
            default -> null;
        };
    }

    // ---- 交易 ----

    public static boolean buyCard(RunState s, int idx) {
        ShopData shop = s.shop;
        if (shop == null || idx < 0 || idx >= shop.cards.size()) return false;
        CardItem it = shop.cards.get(idx);
        if (it.sold || !canAfford(s, it.price)) return false;
        if (it.kind.equals("joker")) {
            // negative 版本小丑自带 +1 槽：满槽时仍可购买（对齐真版，见 RunState.gainJoker 注释）。
            // REF engine.js buyCard 此处不区分 edition 满槽拒绝 negative——REF bug，此处按真版修正。
            boolean neg = it.joker.edition == cn.quotidietium.balatro.engine.Data.Edition.NEGATIVE;
            if (neg ? s.jokerSpace() < 0 : s.jokerSpace() <= 0) return false;
            s.money -= it.price;
            s.jokers.add(it.joker);
            s.msg(Lang.t("msg.joker_gained", it.name));
        } else if (it.kind.equals("playing")) {
            s.money -= it.price;
            s.addCardToDeck(it.card);
            s.msg(Lang.t("msg.deck_added", it.name));
        } else {
            s.money -= it.price;
            if (!s.addConsumableKey(it.kind, it.key)) {
                s.money += it.price;
                return false; // 消耗品槽已满
            }
            s.msg(Lang.t("msg.gained", it.name));
        }
        it.sold = true;
        if (s.mods.inflationPerBuy) s.inflation++; // 真版通胀：每次购买 +$1（R123）
        cn.quotidietium.balatro.engine.Engine.recomputeFlags(s);
        return true;
    }

    public static boolean buyPack(RunState s, int idx) {
        ShopData shop = s.shop;
        if (shop == null || idx < 0 || idx >= shop.packs.size()) return false;
        PackItem it = shop.packs.get(idx);
        if (it.sold || !canAfford(s, it.price)) return false;
        s.money -= it.price;
        it.sold = true;
        if (s.mods.inflationPerBuy) s.inflation++; // 真版通胀：每次购买 +$1（R123）
        Packs.open(s, it.pack); // 进入补充包选择
        return true;
    }

    /** 购买第 idx 张优惠券（0-based）。 */
    public static boolean buyVoucher(RunState s, int idx) {
        ShopData shop = s.shop;
        if (shop == null || idx < 0 || idx >= shop.vouchers.size()) return false;
        VoucherItem it = shop.vouchers.get(idx);
        if (it.sold || !canAfford(s, it.price)) return false;
        s.money -= it.price;
        it.sold = true;
        if (s.mods.inflationPerBuy) s.inflation++; // 真版通胀：每次购买 +$1（R123）
        Data.Voucher v = it.voucher;
        s.vouchers.add(v.key);
        s.msg(Lang.t("msg.voucher_gained", v.displayName()));
        if (v.key.equals("hieroglyph") || v.key.equals("petroglyph")) {
            s.ante = Math.max(1, s.ante - 1);
        }
        cn.quotidietium.balatro.engine.Engine.recomputeFlags(s);
        return true;
    }

    public static long reroll(RunState s) {
        ShopData shop = s.shop;
        if (shop == null) return -1;
        long cost = 5 + shop.rerollCount;
        if (hasVoucher(s, "reroll1")) cost -= 2;
        if (hasVoucher(s, "reroll2")) cost -= 2;
        cost = Math.max(0, cost);
        if (shop.freeRerolls > 0) { shop.freeRerolls--; cost = 0; }
        else if (!canAfford(s, cost)) return -1;
        s.money -= cost;
        shop.rerollCount++;
        shop.cards = genShopCardsPublic(s);
        // P10 性能：池化快照（RunState 深度池，与 Engine 各分发点同源）
        List<JokerInstance> rerollSnap = s.acquireJokerSnap();
        try {
            for (int i = 0; i < rerollSnap.size(); i++) {
                JokerInstance j = rerollSnap.get(i);
                if (!j.debuff) j.def.onReroll(s, j);
            }
        } finally {
            s.releaseJokerBuffer();
        }
        return cost;
    }

    // 包级可见的生成入口（reroll 复用）
    private static List<CardItem> genShopCardsPublic(RunState s) {
        return genShopCards(s);
    }
}
