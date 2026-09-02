package cn.quotidietium.balatro.command;

import cn.quotidietium.balatro.i18n.Lang;
import java.util.ArrayList;
import java.util.List;

/**
 * /balatro help 的分页帮助内容（聊天框显示，每页 ≤ 6 行）。
 *
 * <p>每页 = 标题行 + 至多 5 行正文（合计 ≤ 6 行）。页码与总页数由 {@link #sendPage} 注入。
 * 内容覆盖：开始/牌组/赌注/挑战/回合/商店/消耗品/出售排行/全息交互/计分要素。
 */
final class BalatroHelp {

    /** 一页帮助：标题（显示在页眉）+ 正文行。 */
    private record Page(String titleKey, String[] bodyKeys) {

        String title() {
            return tr(titleKey);
        }

        String[] body() {
            return trAll(bodyKeys);
        }
    }

    private static final List<Page> PAGES = new ArrayList<>();

    static {
        add("help.page.start.title", new String[]{
                "help.page.start.1",
                "help.page.start.2",
                "help.page.start.3",
                "help.page.start.4",
                "help.page.start.5"
        });
        add("help.page.deck.title", new String[]{
                "help.page.deck.1",
                "help.page.deck.2",
                "help.page.deck.3",
                "help.page.deck.4",
                "help.page.deck.5"
        });
        add("help.page.stake.title", new String[]{
                "help.page.stake.1",
                "help.page.stake.2",
                "help.page.stake.3",
                "help.page.stake.4",
                "help.page.stake.5"
        });
        add("help.page.challenge1.title", new String[]{
                "help.page.challenge1.1",
                "help.page.challenge1.2",
                "help.page.challenge1.3",
                "help.page.challenge1.4",
                "help.page.challenge1.5"
        });
        add("help.page.challenge2.title", new String[]{
                "help.page.challenge2.1",
                "help.page.challenge2.2",
                "help.page.challenge2.3",
                "help.page.challenge2.4",
                "help.page.challenge2.5"
        });
        add("help.page.round.title", new String[]{
                "help.page.round.1",
                "help.page.round.2",
                "help.page.round.3",
                "help.page.round.4",
                "help.page.round.5"
        });
        add("help.page.shop.title", new String[]{
                "help.page.shop.1",
                "help.page.shop.2",
                "help.page.shop.3",
                "help.page.shop.4",
                "help.page.shop.5"
        });
        add("help.page.shopsell.title", new String[]{
                "help.page.shopsell.1",
                "help.page.shopsell.2",
                "help.page.shopsell.3",
                "help.page.shopsell.4",
                "help.page.shopsell.5"
        });
        add("help.page.cons.title", new String[]{
                "help.page.cons.1",
                "help.page.cons.2",
                "help.page.cons.3",
                "help.page.cons.4",
                "help.page.cons.5"
        });
        add("help.page.misc.title", new String[]{
                "help.page.misc.1",
                "help.page.misc.2",
                "help.page.misc.3",
                "help.page.misc.4",
                "help.page.misc.5"
        });
        add("help.page.board.title", new String[]{
                "help.page.board.1",
                "help.page.board.2",
                "help.page.board.3",
                Lang.t("board.help.auto"),
                "help.page.board.5"
        });
        add("help.page.score.title", new String[]{
                "help.page.score.1",
                "help.page.score.2",
                "help.page.score.3",
                "help.page.score.4",
                "help.page.score.5"
        });
    }

    /**
     * 取文案：有对应词条就翻译，否则原样返回。
     *
     * <p>帮助表里混着少量纯 ASCII 的用法行（如 {@code §e/balatro gui}），它们不是词条，
     * 直接透传。
     */
    private static String tr(String keyOrLiteral) {
        return Lang.has(keyOrLiteral) ? Lang.t(keyOrLiteral) : keyOrLiteral;
    }

    private static String[] trAll(String[] keysOrLiterals) {
        String[] out = new String[keysOrLiterals.length];
        for (int i = 0; i < out.length; i++) out[i] = tr(keysOrLiterals[i]);
        return out;
    }

    private static void add(String title, String[] body) {
        PAGES.add(new Page(title, body));
    }

    /** 总页数。 */
    static int totalPages() {
        return PAGES.size();
    }

    /**
     * 把第 {@code page} 页（1 起）发给玩家；越界则提示范围。
     *
     * @return 实际是否发送了一页（页码合法）。
     */
    static boolean sendPage(org.bukkit.command.CommandSender sender, int page) {
        if (page < 1 || page > PAGES.size()) {
            sender.sendMessage(Lang.t("help.usage.range", PAGES.size()));
            return false;
        }
        for (String line : linesFor(page)) {
            // commandify：行内 /balatro 命令令牌变为可悬浮（详情+举例）可点击（回填）组件
            sender.sendMessage(HoverText.commandify(line));
        }
        return true;
    }

    /**
     * 第 {@code page} 页（1 起）会被发送的全部行（页眉 + 正文）。
     * 用于测试断言「每页 ≤ 6 行」。页码越界返回空列表。
     */
    static java.util.List<String> linesFor(int page) {
        if (page < 1 || page > PAGES.size()) return java.util.List.of();
        Page p = PAGES.get(page - 1);
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(Lang.t("help.page.header", page, PAGES.size(), p.title()));
        for (String line : p.body()) lines.add("§f" + line);
        return lines;
    }

    // ================= 单命令详情（/balatro help <命令名>） =================

    /** 一条命令的帮助：主键、别名、标题、正文行。包内可见：{@link HoverText} 复用为悬浮详情。 */
    record CmdHelp(String key, String[] aliases, String titleKey, String[] bodyKeys) {

        String title() {
            return tr(titleKey);
        }

        String[] body() {
            return trAll(bodyKeys);
        }
    }

    private static final List<CmdHelp> COMMANDS = new ArrayList<>();

    static {
        cmd("help", new String[]{"?"}, "help.cmd.help.title", new String[]{
                "help.cmd.help.1",
                "help.cmd.help.2",
                "help.cmd.help.3",
                "help.cmd.help.4"
        });
        cmd("gui", new String[]{"menu"}, "help.cmd.gui.title", new String[]{
                "§e/balatro gui",
                "help.cmd.gui.2",
                "help.cmd.gui.3",
                "help.cmd.gui.4"
        });
        cmd("play", new String[]{}, "help.cmd.play.title", new String[]{
                "help.cmd.play.1",
                "help.cmd.play.2",
                "help.cmd.play.3",
                "help.cmd.play.4",
                "help.cmd.play.5"
        });
        cmd("playcard", new String[]{"pc"}, "help.cmd.playcard.title", new String[]{
                "help.cmd.playcard.1",
                "help.cmd.playcard.2",
                "help.cmd.playcard.3",
                "help.cmd.playcard.4"
        });
        cmd("disc", new String[]{"discard"}, "help.cmd.disc.title", new String[]{
                "help.cmd.disc.1",
                "help.cmd.disc.2",
                "help.cmd.disc.3",
                "help.cmd.disc.4"
        });
        cmd("status", new String[]{"hand"}, "help.cmd.status.title", new String[]{
                "§e/balatro status",
                "help.cmd.status.2"
        });
        cmd("shop", new String[]{}, "help.cmd.shop.title", new String[]{
                "§e/balatro shop",
                "help.cmd.shop.2",
                "help.cmd.shop.3"
        });
        cmd("buy", new String[]{}, "help.cmd.buy.title", new String[]{
                "help.cmd.buy.1",
                "help.cmd.buy.2",
                "help.cmd.buy.3"
        });
        cmd("buybag", new String[]{"pack"}, "help.cmd.buybag.title", new String[]{
                "help.cmd.buybag.1",
                "help.cmd.buybag.2"
        });
        cmd("buyvoucher", new String[]{"voucher"}, "help.cmd.buyvoucher.title", new String[]{
                "help.cmd.buyvoucher.1",
                "help.cmd.buyvoucher.2",
                "help.cmd.buyvoucher.3"
        });
        cmd("reroll", new String[]{}, "help.cmd.reroll.title", new String[]{
                "§e/balatro reroll",
                "help.cmd.reroll.2"
        });
        cmd("next", new String[]{}, "help.cmd.next.title", new String[]{
                "§e/balatro next",
                "help.cmd.next.2"
        });
        cmd("go", new String[]{}, "help.cmd.go.title", new String[]{
                "§e/balatro go",
                "help.cmd.go.2",
                "help.cmd.go.3"
        });
        cmd("skip", new String[]{}, "help.cmd.skip.title", new String[]{
                "§e/balatro skip",
                "help.cmd.skip.2",
                "help.cmd.skip.3"
        });
        cmd("cons", new String[]{"consumables"}, "help.cmd.cons.title", new String[]{
                "§e/balatro cons",
                "help.cmd.cons.2"
        });
        cmd("use", new String[]{}, "help.cmd.use.title", new String[]{
                "help.cmd.use.1",
                "help.cmd.use.2",
                "help.cmd.use.3",
                "help.cmd.use.4",
                "help.cmd.use.5"
        });
        cmd("packs", new String[]{}, "help.cmd.packs.title", new String[]{
                "§e/balatro packs",
                "help.cmd.packs.2"
        });
        cmd("pick", new String[]{}, "help.cmd.pick.title", new String[]{
                "help.cmd.pick.1",
                "help.cmd.pick.2"
        });
        cmd("skipack", new String[]{}, "help.cmd.skipack.title", new String[]{
                "§e/balatro skipack",
                "help.cmd.skipack.2"
        });
        cmd("sellj", new String[]{}, "help.cmd.sellj.title", new String[]{
                "help.cmd.sellj.1",
                "help.cmd.sellj.2",
                "help.cmd.sellj.3"
        });
        cmd("sellc", new String[]{}, "help.cmd.sellc.title", new String[]{
                "help.cmd.sellc.1",
                "help.cmd.sellc.2",
                "help.cmd.sellc.3"
        });
        cmd("endless", new String[]{}, "help.cmd.endless.title", new String[]{
                "§e/balatro endless",
                "help.cmd.endless.2"
        });
        cmd("top", new String[]{}, "help.cmd.top.title", new String[]{
                "§e/balatro top",
                "help.cmd.top.2"
        });
        cmd("version", new String[]{"ver"}, "help.cmd.version.title", new String[]{
                "§e/balatro version",
                "help.cmd.version.2"
        });
        cmd("quit", new String[]{}, "help.cmd.quit.title", new String[]{
                "§e/balatro quit",
                "help.cmd.quit.2"
        });
    }

    private static void cmd(String key, String[] aliases, String title, String[] body) {
        COMMANDS.add(new CmdHelp(key, aliases, title, body));
    }

    /** 按主键或别名查找（大小写不敏感）；未找到返回 null。包内可见：{@link HoverText} 查询悬浮详情。 */
    static CmdHelp findCommand(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        for (CmdHelp c : COMMANDS) {
            if (c.key.equalsIgnoreCase(n)) return c;
            for (String a : c.aliases) if (a.equalsIgnoreCase(n)) return c;
        }
        return null;
    }

    /**
     * 发送指定命令的详情；未找到返回 false。
     */
    static boolean sendCommandHelp(org.bukkit.command.CommandSender sender, String name) {
        CmdHelp c = findCommand(name);
        if (c == null) return false;
        sender.sendMessage(HoverText.commandify("§6■ §e" + c.key() + "§6 — " + c.title()));
        for (String line : c.body()) sender.sendMessage(HoverText.commandify("§f" + line));
        return true;
    }

    /** 全部命令主键 + 别名（供 Tab 补全）。 */
    static List<String> commandKeys() {
        List<String> out = new ArrayList<>();
        for (CmdHelp c : COMMANDS) {
            out.add(c.key);
            for (String a : c.aliases) out.add(a);
        }
        return out;
    }

    /** 是否存在该命令（主键或别名）的帮助；大小写不敏感。供测试与外部查询。 */
    static boolean hasCommandHelp(String name) {
        return findCommand(name) != null;
    }

    private BalatroHelp() {
    }
}
