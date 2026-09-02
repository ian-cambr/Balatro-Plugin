package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.engine.consumable.Consumables;
import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bell（翠绿铃，终结者 Boss）回合 × 目标类消耗品交叉锁（R237）。
 *
 * <p>bell 属 FINISHERS（仅 ante 8 出现），实机采样需完整通关路径（成本极高且
 * 通关种子的 ante8 Boss 恰为 bell 的联合概率 ~1/5×0.05%）——按方法学以引擎锁
 * 补位，与 R235 的 psychic 锁同构：bell 限制（出牌必须含铃牌）只作用于 playHand，
 * 不作用于目标类消耗品；铃牌本身也是合法的消耗品目标。
 */
class BossBellConsumableTargetTest {


    /** 本类断言的是原版中文文案，故固定跑在 zh_CN 下（同时兼作 zh_CN 覆盖度检查）。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    private RunState bellRound() {
        RunState s = Engine.createRun("red", 0, "ANYSEED1", null);
        s.bossKey = "bell";
        s.bossQueue.clear();
        s.bossQueue.add("bell");
        s.nextBlind = "boss";
        Engine.selectBlind(s, Data.BlindType.BOSS, false);
        assertEquals(Phase.ROUND, s.phase);
        assertTrue(s.bellCardId != null, "bell 回合应已指定铃牌");
        return s;
    }

    @Test
    void bellRestrictsPlayOnlyNotConsumables() {
        RunState s = bellRound();
        // 出牌不含铃牌 → 拒绝（文案指向翠绿铃）
        Card nonBell = s.hand.stream().filter(c -> c.id() != s.bellCardId).findFirst().orElseThrow();
        Engine.PlayResult r = Engine.playHand(s, java.util.List.of(nonBell.id()));
        assertFalse(r.ok);
        assertTrue(r.err.contains("翠绿铃"), "拒绝文案应指向翠绿铃：" + r.err);
        // 目标类消耗品不受限：战车作用于非铃牌
        s.consumables.add(new Consumable("tarot", "chariot"));
        Consumables.Result cr = Consumables.use(s, 0, java.util.List.of(nonBell.id()));
        assertTrue(cr.ok, "bell 回合目标类消耗品应可使用: " + cr.err);
        assertEquals(Data.Enhancement.STEEL, s.hand.stream().filter(c -> c.id() == nonBell.id())
                .findFirst().orElseThrow().enh());
    }

    @Test
    void bellCardItselfIsValidTarget() {
        RunState s = bellRound();
        Card bell = s.hand.stream().filter(c -> c.id() == s.bellCardId).findFirst().orElseThrow();
        s.consumables.add(new Consumable("tarot", "strength"));
        Consumables.Result cr = Consumables.use(s, 0, java.util.List.of(bell.id()));
        assertTrue(cr.ok, "铃牌本身应仍是合法消耗品目标: " + cr.err);
    }
}
