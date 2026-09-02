package cn.quotidietium.balatro.engine.consumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.quotidietium.balatro.engine.Card;
import cn.quotidietium.balatro.engine.Consumable;
import cn.quotidietium.balatro.engine.Data;
import cn.quotidietium.balatro.engine.Engine;
import cn.quotidietium.balatro.engine.Phase;
import cn.quotidietium.balatro.engine.RunState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Consumables.useInfo 元数据与 apply 实际行为的一致性锁（R225）。
 *
 * <p>锁两层：
 * <ol>
 * <li>needsTargets()==true 的 key 集合 = 原 NEED_ROUND_TARGET 21 键（集合等价锁，
 *     防替换后悄悄漏键/多键——非回合预检行为不变）；</li>
 * <li>对全部 21 个目标 key × N∈[0,4] 逐一实际调用 use()，断言成功当且仅当
 *     min≤N≤max（行为边界锁：apply 分支的 targets(max,exact) 与 useInfo 表
 *     任何一侧漂移都会红）。</li>
 * </ol>
 */
class ConsumablesUseInfoTest {


    /** 本类断言的是原版中文文案，故固定跑在 zh_CN 下（同时兼作 zh_CN 覆盖度检查）。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    /** 原 NEED_ROUND_TARGET 的 21 键（等价基准）。 */
    private static final Set<String> TARGET_KEYS = Set.of(
            "magician", "empress", "hierophant",
            "lovers", "chariot", "justice", "devil", "tower",
            "strength", "hanged", "death",
            "star", "moon", "sun", "world",
            "talisman", "dejavu", "trance", "medium", "aura", "cryptid");

    private static String kindOf(String key) {
        for (Data.Tarot t : Data.TAROTS) if (t.key.equals(key)) return "tarot";
        for (Data.Spectral sp : Data.SPECTRALS) if (sp.key.equals(key)) return "spectral";
        throw new IllegalStateException("未知 key: " + key);
    }

    private RunState roundState() {
        RunState s = Engine.createRun("red", 0, "USEINFO1", null);
        Engine.selectBlind(s, Data.BlindType.SMALL, false);
        assertEquals(Phase.ROUND, s.phase);
        return s;
    }

    @Test
    void needsTargetsKeySetMatchesLegacySet() {
        Set<String> actual = new HashSet<>();
        for (Data.Tarot t : Data.TAROTS) if (Consumables.useInfo(t.key).needsTargets()) actual.add(t.key);
        for (Data.Spectral sp : Data.SPECTRALS) if (Consumables.useInfo(sp.key).needsTargets()) actual.add(sp.key);
        for (Data.Planet p : Data.PLANETS) assertFalse(Consumables.useInfo(p.key).needsTargets(),
                "星球不应需要目标: " + p.key);
        assertEquals(TARGET_KEYS, actual, "needsTargets 键集合必须与原 NEED_ROUND_TARGET 完全一致");
    }

    @Test
    void representativeTableValues() {
        // (1,2)： magician 族 + strength/hanged
        assertEquals(new Consumables.UseInfo(1, 2, true), Consumables.useInfo("magician"));
        assertEquals(new Consumables.UseInfo(1, 2, true), Consumables.useInfo("strength"));
        // (1,1)： lovers 族 + 蜡封族 + aura/cryptid
        assertEquals(new Consumables.UseInfo(1, 1, true), Consumables.useInfo("lovers"));
        assertEquals(new Consumables.UseInfo(1, 1, true), Consumables.useInfo("aura"));
        assertEquals(new Consumables.UseInfo(1, 1, true), Consumables.useInfo("cryptid"));
        // (2,2)： death
        assertEquals(new Consumables.UseInfo(2, 2, true), Consumables.useInfo("death"));
        // (1,3)： star 族
        assertEquals(new Consumables.UseInfo(1, 3, true), Consumables.useInfo("star"));
        // 无目标但仅限回合： familiar 族
        assertEquals(new Consumables.UseInfo(0, 0, true), Consumables.useInfo("familiar"));
        assertEquals(new Consumables.UseInfo(0, 0, true), Consumables.useInfo("immolate"));
        // 无目标不限回合： 星球/非目标塔罗/非回合限幻灵
        assertEquals(new Consumables.UseInfo(0, 0, false), Consumables.useInfo(Data.PLANETS.get(0).key));
        assertEquals(new Consumables.UseInfo(0, 0, false), Consumables.useInfo("hermit"));
        assertEquals(new Consumables.UseInfo(0, 0, false), Consumables.useInfo("wraith"));
    }

    /**
     * 行为边界锁：21 个目标 key × N∈[0,4]，use() 成功 ⟺ min≤N≤max。
     * 每个 (key,N) 用全新一局（8 张手牌）隔离副作用与流序。
     */
    @Test
    void applyBoundaryMatchesUseInfoForAllTargetKeys() {
        List<String> keys = new ArrayList<>(TARGET_KEYS);
        keys.sort(null);
        for (String key : keys) {
            Consumables.UseInfo info = Consumables.useInfo(key);
            assertTrue(info.needsTargets(), key + " 应需要目标");
            for (int n = 0; n <= 4; n++) {
                RunState s = roundState();
                s.consumables.add(new Consumable(kindOf(key), key));
                List<Integer> ids = new ArrayList<>();
                for (int i = 0; i < n && i < s.hand.size(); i++) ids.add(s.hand.get(i).id());
                Consumables.Result r = Consumables.use(s, 0, ids);
                boolean expectOk = n >= info.minTargets() && n <= info.maxTargets();
                assertEquals(expectOk, r.ok,
                        key + " 传 " + n + " 张目标：期望 " + (expectOk ? "成功" : "失败") + "，实际 err=" + r.err);
            }
        }
    }

    /** 非回合预检（等价锁）：SHOP 阶段目标类报「出牌回合」；familiar 族报「需要在回合中使用」；无限制项可用。 */
    @Test
    void shopPhaseBehaviourMatchesLegacyPrecheck() {
        // 目标类：SHOP 中预检拦截
        for (String key : new String[]{"magician", "lovers", "death", "star", "aura", "cryptid"}) {
            RunState s = Engine.createRun("red", 0, "USEINFO2", null);
            s.phase = Phase.SHOP;
            s.consumables.add(new Consumable(kindOf(key), key));
            Consumables.Result r = Consumables.use(s, 0, List.of());
            assertFalse(r.ok, key + " 在商店应被预检拦截");
            assertTrue(r.err.contains("出牌回合"), key + " 预检文案应指出需出牌回合: " + r.err);
        }
        // familiar 族：SHOP 中走 inRoundHand 分支报错（非预检路径）
        RunState s = Engine.createRun("red", 0, "USEINFO3", null);
        s.phase = Phase.SHOP;
        s.consumables.add(new Consumable("spectral", "familiar"));
        Consumables.Result r = Consumables.use(s, 0, List.of());
        assertFalse(r.ok);
        assertTrue(r.err.contains("需要在回合中使用"), "familiar 商店报错文案: " + r.err);

        // 无限制项：SHOP 中可用（星球升级 / hermit 取钱）
        RunState s2 = Engine.createRun("red", 0, "USEINFO4", null);
        s2.phase = Phase.SHOP;
        s2.consumables.add(new Consumable("planet", Data.PLANETS.get(0).key));
        assertTrue(Consumables.use(s2, 0, List.of()).ok, "星球应可在商店使用");
        // 上一次 use 已移除星球，hermit 现位于索引 0
        s2.consumables.add(new Consumable("tarot", "hermit"));
        assertTrue(Consumables.use(s2, 0, List.of()).ok, "hermit 应可在商店使用");
    }

    /**
     * 愚人生效需求（R225 同族收口）：fool 复制上一张塔罗/星球——上一张是目标类塔罗时，
     * effectiveUseInfo 返回其需求（板端据此携带 @ids）；否则与 useInfo(fool) 一致。
     */
    @Test
    void foolEffectiveRequirementFollowsLastTarotPlanet() {
        RunState s = roundState();
        // 无上一张：愚人无目标需求（引擎侧会报「没有可复制的牌」，与本元数据无关）
        assertEquals(Consumables.useInfo("fool"), Consumables.effectiveUseInfo(s, "fool"));
        // 上一张是目标类塔罗 strength(1,2)：生效需求跟随
        s.lastTarotPlanet = new RunState.TarotPlanet("tarot", "strength");
        assertEquals(new Consumables.UseInfo(1, 2, true), Consumables.effectiveUseInfo(s, "fool"));
        // 上一张是非目标塔罗 hermit：无目标需求
        s.lastTarotPlanet = new RunState.TarotPlanet("tarot", "hermit");
        assertEquals(Consumables.useInfo("fool"), Consumables.effectiveUseInfo(s, "fool"));
        // 上一张是星球：星球无目标需求
        s.lastTarotPlanet = new RunState.TarotPlanet("planet", Data.PLANETS.get(0).key);
        assertEquals(Consumables.useInfo("fool"), Consumables.effectiveUseInfo(s, "fool"));
        // 非 fool 的 key 不受 lastTarotPlanet 影响
        s.lastTarotPlanet = new RunState.TarotPlanet("tarot", "strength");
        assertEquals(Consumables.useInfo("aura"), Consumables.effectiveUseInfo(s, "aura"));

        // 行为闭环：愚人 + 上一张 strength + 1 张选中目标 → 成功（点数 +1 生效）
        RunState s2 = roundState();
        s2.lastTarotPlanet = new RunState.TarotPlanet("tarot", "strength");
        s2.consumables.add(new Consumable("tarot", "fool"));
        Card target = null; // 8 张手牌至多 4 张 A，必有 rank<14 的牌（A 不参与 +1）
        for (Card c : s2.hand) if (c.rank() < 14) { target = c; break; }
        int before = target.rank();
        Consumables.Result r = Consumables.use(s2, 0, List.of(target.id()));
        assertTrue(r.ok, "愚人复制 strength 应可用: " + r.err);
        assertEquals(before + 1, target.rank(), "目标牌点数应 +1");
    }
}
