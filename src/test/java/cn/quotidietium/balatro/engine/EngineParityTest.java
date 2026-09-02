package cn.quotidietium.balatro.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 对照原版 engine.js 的行为一致性（parity）回归测试。
 *
 * <ul>
 *   <li>anaglyph（浮雕牌组）：击败 Boss 获得翻倍标签（原版 engine.js endRound boss 分支）。</li>
 *   <li>ScoreContext.gainConsumable：计分管线内发放消耗品（8 号球等），对齐原版 ctx.gainConsumable。</li>
 *   <li>RunState.gainConsumable：加入成功时给出"获得：名称"消息（原版 state.gainConsumable）。</li>
 * </ul>
 */
class EngineParityTest {


    /** 本类断言的是原版中文文案，故固定跑在 zh_CN 下（同时兼作 zh_CN 覆盖度检查）。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    /** 浮雕牌组击败 Boss 后应获得翻倍标签；其他牌组不应获得。 */
    @Test
    void anaglyphGrantsDoubleTagOnBossDefeat() {
        RunState s = Engine.createRun("anaglyph", 0, "ANAGLYPH");
        Engine.selectBlind(s, Data.BlindType.SMALL, false);
        // 把当前回合伪造成 Boss 盲注且目标分为 0（任意出牌即胜出 → endRound boss 分支）。
        // 固定用无出牌干扰的「高墙」，避开 psychic/bell 等对单张出牌的拦截。
        s.blindType = Data.BlindType.BOSS;
        s.bossQueue.clear();
        s.bossQueue.add("wall");
        s.blindTarget = 0;

        Engine.PlayResult r = Engine.playHand(s, List.of(s.hand.get(0).id()));

        assertTrue(r.ok);
        assertTrue(r.won, "目标 0 分应立即胜出盲注");
        assertTrue(s.tags.contains("double"), "浮雕牌组击败 Boss 应获得翻倍标签");
        assertTrue(s.doubleTagPending, "翻倍标签应处于待复制状态");
    }

    @Test
    void nonAnaglyphDeckGrantsNoDoubleTagOnBossDefeat() {
        RunState s = Engine.createRun("red", 0, "NOANAGLYPH");
        Engine.selectBlind(s, Data.BlindType.SMALL, false);
        s.blindType = Data.BlindType.BOSS;
        s.bossQueue.clear();
        s.bossQueue.add("wall");
        s.blindTarget = 0;

        Engine.PlayResult r = Engine.playHand(s, List.of(s.hand.get(0).id()));

        assertTrue(r.ok && r.won);
        assertFalse(s.tags.contains("double"), "非浮雕牌组击败 Boss 不应获得翻倍标签");
    }

    /** ScoreContext.gainConsumable：消耗品槽未满时入区并记事件；满时不加入、不记事件。 */
    @Test
    void scoreContextGainConsumableAddsAndLogs() {
        RunState s = Engine.createRun("red", 0, "CTXGAIN");
        List<String> events = new ArrayList<>();
        ScoreContext ctx = new ScoreContext(s, Data.HandType.HIGH, 0, 0, List.of(), List.of(), events);

        ctx.gainConsumable("tarot");
        assertEquals(1, s.consumables.size(), "第 1 张应入区");
        assertEquals("tarot", s.consumables.get(0).kind);
        assertEquals(1, events.size(), "加入成功应记一条事件");
        assertTrue(events.get(0).startsWith("获得："));

        ctx.gainConsumable("spectral");
        assertEquals(2, s.consumables.size(), "第 2 张应入区（默认 2 槽）");

        ctx.gainConsumable("planet");
        assertEquals(2, s.consumables.size(), "槽满时不应加入");
        assertEquals(2, events.size(), "槽满时不应记事件");
    }

    /** RunState.gainConsumable：加入成功给出消息（对齐原版），槽满不提示。 */
    @Test
    void stateGainConsumableMessagesOnSuccess() {
        RunState s = Engine.createRun("red", 0, "STGAIN");
        s.gainConsumable("planet");
        assertEquals(1, s.consumables.size());
        assertEquals(1, s.messages.size(), "加入成功应有消息");
        assertTrue(s.messages.get(0).startsWith("获得："));

        s.gainConsumable("tarot");
        s.gainConsumable("tarot"); // 第 3 张：槽满
        assertEquals(2, s.consumables.size());
        assertEquals(2, s.messages.size(), "槽满时不应新增消息");
    }
}
