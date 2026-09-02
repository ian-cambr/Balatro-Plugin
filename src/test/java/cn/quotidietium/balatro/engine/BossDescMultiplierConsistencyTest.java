package cn.quotidietium.balatro.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * R139：Boss desc 倍率表述与 blindTarget 实测倍率的**语义一致性**锁定。
 *
 * <p>缺陷（#69）：R126 把紫罗兰之瓶代码对齐真版 6×（原 REF 3×），但 Data.Boss.VESSEL
 * 的 desc 字符串漏改为「目标分 ×3」——该 desc 经「Boss 效果：」提示与全息简介直达玩家，
 * 属玩家可见的表述-实现不符。Boss desc 不在 golden data.txt 锁内（无 BOSS 行），
 * 此前无任何测试守护 desc 与代码的倍率一致。
 *
 * <p>守门口径（R135 教训：语义断言优于 key 断言）：解析 desc 中的「×N」，
 * 与同状态下 blindTarget 实测的目标分/基础分比值逐个比对——desc 改了代码没改、
 * 或代码改了 desc 漏改，两侧任一漂移都会失败。
 */
class BossDescMultiplierConsistencyTest {


    /** 黄金数据来自原版中文 data.js，故这些断言固定跑在 zh_CN 下。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    private static final Pattern X_N = Pattern.compile("×(\\d+)");

    /** 以底注 1（base=300、BOSS 默认 2×）实测各倍率 Boss 的目标分，与 desc 声称值比对。 */
    @Test
    void wallAndVesselDescMatchActualMultiplier() {
        checkOneBoss("wall");
        checkOneBoss("vessel");
    }

    private static void checkOneBoss(String bossKey) {
        RunState s = Engine.createRun("red", 0, "BOSSX" + bossKey, null);
        // 保持在盲注选择阶段（blindBase 只依赖 ante），把本底注 Boss 换成被测者
        s.bossQueue.clear();
        s.bossQueue.add(bossKey);

        long bossTarget = Engine.blindTarget(s, Data.BlindType.BOSS);
        // 基准：同状态普通 Boss（默认 2×）——用未匹配任何倍率分支的 Boss 反推 base
        s.bossQueue.set(0, "hook");
        long plainTarget = Engine.blindTarget(s, Data.BlindType.BOSS);
        long base = plainTarget / 2; // BOSS 默认 mult=2

        Data.Boss boss = Data.Boss.byKey(bossKey);
        Matcher m = X_N.matcher(boss.desc());
        assertTrue(m.find(), bossKey + " 的 desc 应含「×N」倍率表述：" + boss.desc());
        int claimed = Integer.parseInt(m.group(1));
        long actual = bossTarget / base;
        assertEquals(claimed, actual,
                bossKey + " desc 声称 ×" + claimed + " 但 blindTarget 实测 ×" + actual
                        + "（desc 与代码漂移，R139 #69 同类）");
    }
}
