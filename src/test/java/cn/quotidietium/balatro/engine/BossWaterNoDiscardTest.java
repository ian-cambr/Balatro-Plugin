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
 * water Boss（水：本回合没有弃牌次数）× 消耗品交叉锁（R238）。
 *
 * <p>与 psychic/bell 锁同构：water 只把弃牌次数归零（弃牌守卫拒绝），不限制出牌与
 * 目标类消耗品。实机双盲推进不稳定（脚本层），语义由本锁闭合；弃牌拒绝文案同时锁定。
 */
class BossWaterNoDiscardTest {


    /** 本类断言的是原版中文文案，故固定跑在 zh_CN 下（同时兼作 zh_CN 覆盖度检查）。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    private RunState waterRound() {
        RunState s = Engine.createRun("red", 0, "W238w183", null);
        assertEquals("water", s.bossKey);
        s.nextBlind = "boss";
        Engine.selectBlind(s, Data.BlindType.BOSS, false);
        assertEquals(Phase.ROUND, s.phase);
        assertEquals(0, s.discardsLeft, "水 Boss 回合弃牌次数应为 0");
        return s;
    }

    @Test
    void waterBlocksDiscardButNotPlayOrConsumables() {
        RunState s = waterRound();
        int id = s.hand.get(0).id();
        // 弃牌被拒（文案指向水）
        Engine.PlayResult d = Engine.discard(s, java.util.List.of(id));
        assertFalse(d.ok);
        assertTrue(d.err.contains("水") || d.err.contains("弃牌"), "拒绝文案: " + d.err);
        // 出牌不受限
        Engine.PlayResult p = Engine.playHand(s, java.util.List.of(id));
        assertTrue(p.ok, "water 不限制出牌: " + p.err);
    }

    @Test
    void waterRoundConsumableTargetsStillWork() {
        RunState s = waterRound();
        int id = s.hand.get(1).id();
        s.consumables.add(new Consumable("tarot", "chariot"));
        Consumables.Result cr = Consumables.use(s, 0, java.util.List.of(id));
        assertTrue(cr.ok, "water 回合目标类消耗品应可使用: " + cr.err);
        assertEquals(Data.Enhancement.STEEL, s.hand.stream().filter(c -> c.id() == id)
                .findFirst().orElseThrow().enh());
    }
}
