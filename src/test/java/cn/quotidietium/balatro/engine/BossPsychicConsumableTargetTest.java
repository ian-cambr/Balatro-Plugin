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
 * psychic Boss 回合 × 目标类消耗品交叉锁（R235）。
 *
 * <p>锁三点：①psychic 只限制 playHand（必须满 5 张），**不限制**目标类消耗品使用——
 * 确认链的引擎侧前提；②被选为目标的手牌即使处于 Boss 干扰语境（psychic 回合）照常
 * 增强生效；③facedown/debuff 牌作为目标仍合法（targets 只按 id 查手牌）。
 * 与实机 check32 互补：此处锁引擎语义，实机锁 @ids UI 链。
 */
class BossPsychicConsumableTargetTest {


    /** 本类断言的是原版中文文案，故固定跑在 zh_CN 下（同时兼作 zh_CN 覆盖度检查）。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    /** 直接进入 ante1 的 psychic Boss 回合（B2351 种子 bossKey=psychic，扫描锁定）。 */
    private RunState psychicRound() {
        RunState s = Engine.createRun("red", 0, "B2351", null);
        assertEquals("psychic", s.bossKey);
        s.nextBlind = "boss"; // 跳过 small/big（本锁只关心 psychic 语境，盲注推进已有 BossEndToEnd 覆盖）
        Engine.selectBlind(s, Data.BlindType.BOSS, false);
        assertEquals(Phase.ROUND, s.phase);
        return s;
    }

    @Test
    void psychicRejectsShortPlayButAllowsTargetConsumable() {
        RunState s = psychicRound();
        int id = s.hand.get(0).id();
        // ①出牌限制生效：1 张被拒
        Engine.PlayResult r = Engine.playHand(s, java.util.List.of(id));
        assertFalse(r.ok);
        assertTrue(r.err.contains("通灵者"), "拒绝文案应指向 psychic：" + r.err);
        // ②目标类消耗品不受 psychic 限制：战车把目标牌变钢铁
        s.consumables.add(new Consumable("tarot", "chariot"));
        Consumables.Result cr = Consumables.use(s, 0, java.util.List.of(id));
        assertTrue(cr.ok, "psychic 回合目标类消耗品应可使用: " + cr.err);
        assertEquals(Data.Enhancement.STEEL, s.hand.stream().filter(c -> c.id() == id)
                .findFirst().orElseThrow().enh());
    }

    @Test
    void facedownCardRemainsValidTarget() {
        RunState s = psychicRound();
        Card t = s.hand.get(1);
        t.setFacedown(true); // mark/facedown 语境（Boss 干扰面）
        s.consumables.add(new Consumable("tarot", "strength"));
        Consumables.Result cr = Consumables.use(s, 0, java.util.List.of(t.id()));
        assertTrue(cr.ok, "面朝下牌应仍为合法目标: " + cr.err);
        // strength 对面朝下牌 +1 且翻开（apply 分支 setFacedown(false)）
        assertFalse(t.facedown(), "使用后应翻开");
    }
}
