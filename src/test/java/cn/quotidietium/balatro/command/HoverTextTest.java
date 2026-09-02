package cn.quotidietium.balatro.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * {@link HoverText} 聊天帮助增强测试：
 * 命令令牌的悬浮详情/点击回填、纯文本保真、未知命令与无令牌行的安全兜底。
 */
class HoverTextTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** 收集组件树中所有带悬浮事件的组件。 */
    private static List<Component> hoverable(Component root) {
        List<Component> out = new ArrayList<>();
        if (root.hoverEvent() != null) out.add(root);
        for (Component child : root.children()) out.addAll(hoverable(child));
        return out;
    }

    /**
     * 取点击事件的文本载荷。
     *
     * <p>Adventure 5（Paper 26.2）起 {@code ClickEvent} 泛型化，{@code value()} 由
     * 类型化的 {@code payload()} 取代；SUGGEST_COMMAND/RUN_COMMAND 的载荷为
     * {@link ClickEvent.Payload.Text}。
     */
    private static String clickValue(ClickEvent<?> ev) {
        return ((ClickEvent.Payload.Text) ev.payload()).value();
    }

    /** 收集组件树中所有带点击事件的组件。 */
    private static List<Component> clickable(Component root) {
        List<Component> out = new ArrayList<>();
        if (root.clickEvent() != null) out.add(root);
        for (Component child : root.children()) out.addAll(clickable(child));
        return out;
    }

    @Test
    void commandTokenGetsHoverAndClick() {
        Component c = HoverText.commandify("§e/balatro play§f 开始一局");
        assertEquals("/balatro play 开始一局", PLAIN.serialize(c), "纯文本内容应保持不变");
        List<Component> hov = hoverable(c);
        assertEquals(1, hov.size(), "应恰好一个可悬浮令牌");
        String hoverText = PLAIN.serialize((Component) hov.get(0).hoverEvent().value());
        assertTrue(hoverText.contains("开始一局"), "悬浮应含命令标题");
        assertTrue(hoverText.contains("/balatro play"), "悬浮应含用法");
        assertTrue(hoverText.contains("例"), "悬浮应含使用举例");
        List<Component> clk = clickable(c);
        assertEquals(1, clk.size());
        ClickEvent<?> click = clk.get(0).clickEvent();
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
        assertEquals("/balatro play", clickValue(click), "点击应回填命令主键");
    }

    @Test
    void aliasTokenResolvesToPrimaryKey() {
        Component c = HoverText.commandify("§e/balatro pc§f 出牌");
        List<Component> clk = clickable(c);
        assertEquals(1, clk.size(), "别名 pc 应解析到 playcard");
        assertEquals("/balatro playcard", clickValue(clk.get(0).clickEvent()));
    }

    @Test
    void multipleTokensInOneLine() {
        Component c = HoverText.commandify("§e/balatro buy <序号>§f 买卡 §7|§e /balatro reroll§f 重掷");
        assertEquals(2, hoverable(c).size(), "两个命令令牌都应可悬浮");
        // § 颜色码在纯文本序列化中被剥除
        assertEquals("/balatro buy <序号> 买卡 | /balatro reroll 重掷", PLAIN.serialize(c));
    }

    @Test
    void unknownCommandHasNoHover() {
        Component c = HoverText.commandify("§e/balatro frobnicate§f 不存在");
        assertTrue(hoverable(c).isEmpty(), "未知命令不应有悬浮");
        assertTrue(clickable(c).isEmpty(), "未知命令不应有点击");
        assertEquals("/balatro frobnicate 不存在", PLAIN.serialize(c));
    }

    @Test
    void noTokenLinePassThrough() {
        String line = "§7这是一行没有命令的帮助文本。";
        Component c = HoverText.commandify(line);
        assertEquals(LEGACY.deserialize(line), c, "无令牌行应原样反序列化");
        assertTrue(hoverable(c).isEmpty());
    }

    @Test
    void tokenForKnownKeyDirectly() {
        Component t = HoverText.token("quit", "quit");
        assertNotNull(t.hoverEvent());
        assertNotNull(t.clickEvent());
        assertEquals("quit", PLAIN.serialize(t));
        assertEquals("/balatro quit", clickValue(t.clickEvent()));
    }

    @Test
    void tokenForUnknownKeyIsPlain() {
        Component t = HoverText.token("nosuchcmd", "nosuchcmd");
        assertNull(t.hoverEvent());
        assertNull(t.clickEvent());
        assertEquals("nosuchcmd", PLAIN.serialize(t));
    }

    @Test
    void everyRegisteredCommandHoverContainsUsageExample() {
        // 抽查若干带「例：」的命令，悬浮中必须出现举例（UX 硬性要求）
        for (String key : new String[]{"play", "playcard", "use", "help"}) {
            BalatroHelp.CmdHelp help = BalatroHelp.findCommand(key);
            assertNotNull(help, "命令应已注册：" + key);
            String hover = PLAIN.serialize(HoverText.hoverFor(help));
            assertTrue(hover.contains("/balatro " + key), "悬浮应含用法：" + key);
            assertTrue(hover.contains("例"), "悬浮应含使用举例：" + key);
        }
    }
}
