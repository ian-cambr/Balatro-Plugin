package cn.quotidietium.balatro.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 商店出售功能的帮助文案同步测试（0.4.21）。
 *
 * <p>验证：
 * <ul>
 *   <li>sellj / sellc 单命令详情含「商店阶段」表述（全息右键出售）。</li>
 *   <li>新增的「商店出售持有牌」帮助页存在且内容含关键提示（出售/商店/消耗品）。</li>
 *   <li>帮助总页数与分页约束未被破坏（每页 ≤ 6 行）。</li>
 * </ul>
 */
class ShopSellHelpTest {


    /** 本类断言的是原版中文文案，故固定跑在 zh_CN 下（同时兼作 zh_CN 覆盖度检查）。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    @Test
    void selljHelpMentionsShopPhase() {
        BalatroHelp.CmdHelp c = BalatroHelp.findCommand("sellj");
        assertNotNull(c, "sellj 应有命令详情");
        String joined = String.join("\n", c.body());
        assertTrue(joined.contains("商店"), "sellj 详情应提及商店阶段可右键出售");
    }

    @Test
    void sellcHelpMentionsShopPhase() {
        BalatroHelp.CmdHelp c = BalatroHelp.findCommand("sellc");
        assertNotNull(c, "sellc 应有命令详情");
        String joined = String.join("\n", c.body());
        assertTrue(joined.contains("商店"), "sellc 详情应提及商店阶段可右键出售");
    }

    @Test
    void shopSellPageExists() {
        int total = BalatroHelp.totalPages();
        boolean found = false;
        for (int p = 1; p <= total; p++) {
            List<String> lines = BalatroHelp.linesFor(p);
            // 标题行含「商店出售」
            if (lines.stream().anyMatch(l -> l.contains("商店出售"))) {
                found = true;
                // 正文应含出售与消耗品相关表述
                String body = String.join("\n", lines);
                assertTrue(body.contains("出售"), "商店出售页应含「出售」");
                assertTrue(body.contains("消耗品"), "商店出售页应含「消耗品」");
                assertTrue(body.contains("sellj") && body.contains("sellc"), "商店出售页应含 sellj 与 sellc 命令");
                break;
            }
        }
        assertTrue(found, "应存在「商店出售持有牌」帮助页");
    }

    @Test
    void paginationStillIntact() {
        int total = BalatroHelp.totalPages();
        assertTrue(total > 0);
        for (int p = 1; p <= total; p++) {
            int lines = BalatroHelp.linesFor(p).size();
            assertTrue(lines <= 6, "第 " + p + " 页 " + lines + " 行，超过 6 行上限");
        }
    }
}
