package cn.quotidietium.balatro.command;

import cn.quotidietium.balatro.BalatroPlugin;
import cn.quotidietium.balatro.engine.Engine;
import cn.quotidietium.balatro.i18n.Lang;
import cn.quotidietium.balatro.session.GameSession;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /balatro 命令（别名 blt / joker），仅玩家可用。
 *
 * <p>子命令（序号均从 1 起）：
 * <ul>
 *   <li>通用（仅命令）：{@code gui | play [牌组] [赌注] [挑战] [种子] | status | endless | top | quit | version}</li>
 *   <li>回合（全息等价）：{@code playcard <序号...> | disc <序号...>}</li>
 *   <li>盲注选择（全息等价）：{@code go | skip}</li>
 *   <li>商店（全息等价）：{@code shop | buy <序号> | buybag <序号> | buyvoucher | reroll | next}</li>
 *   <li>消耗品（全息等价）：{@code cons | use <序号> [手牌序号...]}</li>
 *   <li>补充包（全息等价）：{@code packs | pick <序号> | skipack}</li>
 *   <li>出售（全息等价）：{@code sellj <序号> | sellc <序号>}</li>
 * </ul>
 * 标注「全息等价」的命令均可通过全息牌桌右键完成相同操作（命令为备用）；
 * 标注「仅命令」的为管理/信息类命令，无全息对应。
 * {@code cancel} 仅为全息确认框「[取消]」按钮的回执，不列入帮助。
 * 分页详细玩法与单命令详情见 {@link BalatroHelp}。
 */
public final class BalatroCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "help", "gui", "play", "quit", "status", "playcard", "disc", "endless",
            "shop", "buy", "buybag", "buyvoucher", "reroll", "next", "go", "skip",
            "cons", "use", "packs", "pick", "skipack", "sellj", "sellc", "top",
            "version", "cancel");

    private final BalatroPlugin plugin;

    /** {@code /balatro top} 的每玩家节流间隔（毫秒）：聚合排行榜每次全量遍历统计记录，防宏刷。 */
    private static final long TOP_THROTTLE_MS = 1_000L;
    private final Map<UUID, Long> lastTop = new HashMap<>();

    public BalatroCommand(BalatroPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.t("cmd.err.player_only"));
            return true;
        }
        // 权限实施：plugin.yml 声明 balatro.play（默认 true）。默认配置行为不变；
        // 服务器经权限插件撤销后在此拦截（此前该节点仅为声明，未实际实施）。
        if (!player.hasPermission("balatro.play")) {
            player.sendMessage(Lang.t("cmd.err.no_permission"));
            return true;
        }
        // 命令层统一兜底：客户端输入一律不可信，任何子命令路径都不应向 Bukkit 命令分发器
        // 抛异常（否则触发难看的错误回显/日志刷屏）。各 cmdXxx 已对参数做防御，但第三方
        // 事件监听器（fireRunStart/fireHandScore 经 GameSession 间接调用）或意外的引擎状态
        // 仍可能抛 RuntimeException——在此最后一道兜住，记日志并向玩家给出友好提示。
        try {
            dispatch(player, args);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning(Lang.t("cmd.log.dispatch_failed",
                    player.getName(), java.util.Arrays.toString(args), ex));
            player.sendMessage(Lang.t("cmd.err.internal"));
        }
        return true;
    }

    private void dispatch(Player player, String[] args) {
        if (args.length == 0) {
            sendHelp(player);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "help", "?" -> cmdHelp(player, args);
            case "gui", "menu" -> cmdGui(player);
            case "play" -> cmdPlay(player, args);
            case "quit" -> cmdQuit(player);
            case "status", "hand" -> cmdStatus(player);
            case "playcard", "pc" -> cmdPlayCard(player, args);
            case "disc", "discard" -> cmdDiscard(player, args);
            case "endless" -> cmdEndless(player);
            case "shop" -> cmdShop(player);
            case "buy" -> cmdBuy(player, args);
            case "buybag", "pack" -> cmdBuyPack(player, args);
            case "buyvoucher", "voucher" -> cmdBuyVoucher(player, args);
            case "reroll" -> cmdReroll(player);
            case "next" -> cmdNext(player);
            case "go" -> cmdGo(player);
            case "skip" -> cmdSkip(player);
            case "cons", "consumables" -> cmdCons(player);
            case "use" -> cmdUse(player, args);
            case "packs" -> cmdPack(player);
            case "pick" -> cmdPick(player, args);
            case "skipack" -> cmdSkipPack(player);
            case "cancel" -> player.sendMessage(Lang.t("cmd.msg.cancelled"));
            case "top" -> cmdTop(player);
            case "version", "ver" -> cmdVersion(player);
            case "sellj" -> cmdSellJoker(player, args);
            case "sellc" -> cmdSellConsumable(player, args);
            default -> sendHelp(player);
        }
    }

    /** 打开开局向导 GUI（图形界面选择 模式/牌组/赌注/挑战/种子）。 */
    private void cmdGui(Player player) {
        if (plugin.sessionManager().isActive(player)) {
            player.sendMessage(Lang.t("gui.err.already_running"));
            return;
        }
        plugin.guiManager().openGui(player);
    }

    private void cmdPlay(Player player, String[] args) {
        if (plugin.sessionManager().isActive(player)) {
            player.sendMessage(Lang.t("gui.err.already_running"));
            return;
        }
        // 参数顺序不限：自动识别 牌组名 / 赌注数字 / 挑战名，其余视作种子。
        String deck = "red";
        int stake = 0;
        String challenge = null;
        String seed = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (isStakeArg(a)) {
                stake = a.charAt(0) - '0';
            } else if (deckKeyOf(a) != null) {
                deck = deckKeyOf(a); // 规范化为表内 key（引擎大小写敏感）
            } else if (challengeKeyOf(a) != null) {
                challenge = challengeKeyOf(a);
            } else {
                seed = a;
            }
        }
        // 种子来自客户端，必须校验（长度/字符集），拒绝非法输入
        if (seed != null && !cn.quotidietium.balatro.engine.Rng.isValidSeed(seed)) {
            player.sendMessage(Lang.t("cmd.err.bad_seed"));
            return;
        }
        GameSession s = plugin.sessionManager().start(player, deck, stake, seed, challenge);
        if (s == null) {
            player.sendMessage(Lang.t("gui.err.start_cancelled"));
            return;
        }
        sendRunInfo(player, s, deck, stake, challenge);
        player.sendMessage(s.handDebug());
    }

    /**
     * 开局时在聊天框给出本局完整信息，便于新手快速了解：
     * 种子/牌组/赌注/挑战名 + 各自效果 + 开局特殊持有（券/消耗品）+ 第一个 Boss + 操作提示。
     *
     * <p>public static：GUI 开局向导（gui 包）与命令层共用同一套展示，避免两处文案漂移。
     */
    public static void sendRunInfo(Player player, GameSession s, String deck, int stake, String challenge) {
        cn.quotidietium.balatro.engine.RunState st = s.state();
        cn.quotidietium.balatro.engine.Data.Deck dk = cn.quotidietium.balatro.engine.Data.deckByKey(deck);
        cn.quotidietium.balatro.engine.Data.Stake sk = cn.quotidietium.balatro.engine.Data.STAKES.get(stake);

        player.sendMessage(Lang.t("cmd.run.title"));
        StringBuilder head = new StringBuilder(Lang.t("cmd.run.head", st.seed, dk.name(), sk.name()));
        if (challenge != null) {
            cn.quotidietium.balatro.engine.Data.Challenge ch = findChallenge(challenge);
            if (ch != null) head.append("  ").append(Lang.t("cmd.run.head_challenge", ch.name()));
        }
        player.sendMessage(head.toString());
        player.sendMessage(Lang.t("cmd.run.deck_effect", dk.desc()));
        player.sendMessage(Lang.t("cmd.run.stake_effect", sk.desc()));
        if (challenge != null) {
            cn.quotidietium.balatro.engine.Data.Challenge ch = findChallenge(challenge);
            if (ch != null) player.sendMessage(Lang.t("cmd.run.challenge_effect", ch.desc()));
        }
        // 开局特殊持有：本局初始拥有的优惠券 / 消耗品（牌组或挑战带来）
        java.util.List<String> startItems = new java.util.ArrayList<>();
        for (String vk : st.vouchers) {
            try {
                startItems.add(Lang.t("cmd.run.voucher_item",
                        cn.quotidietium.balatro.engine.Data.voucherByKey(vk).displayName()));
            } catch (IllegalArgumentException ignored) {
                startItems.add(Lang.t("cmd.run.voucher_item", vk));
            }
        }
        for (var c : st.consumables) startItems.add(c.name());
        if (!startItems.isEmpty()) {
            player.sendMessage(Lang.t("cmd.run.start_items", String.join(" §7·§f ", startItems)));
        }
        // 第一个 Boss 盲注（让玩家提前规划）
        cn.quotidietium.balatro.engine.Data.Boss boss = Engine.bossDef(st);
        player.sendMessage(Lang.t("cmd.run.first_boss", boss.displayName(), boss.desc()));
        player.sendMessage(Lang.t("cmd.run.hint"));
    }

    private static cn.quotidietium.balatro.engine.Data.Challenge findChallenge(String key) {
        for (cn.quotidietium.balatro.engine.Data.Challenge c : cn.quotidietium.balatro.engine.Data.CHALLENGES) {
            if (c.key().equals(key)) return c;
        }
        return null;
    }

    private static boolean isStakeArg(String a) {
        if (a.length() != 1 || !Character.isDigit(a.charAt(0))) return false;
        int n = a.charAt(0) - '0';
        return n >= 0 && n <= 7;
    }

    /** 大小写不敏感匹配牌组，返回表内规范 key；不匹配返回 null。 */
    private static String deckKeyOf(String a) {
        for (var d : cn.quotidietium.balatro.engine.Data.DECKS) {
            if (d.key().equalsIgnoreCase(a)) return d.key();
        }
        return null;
    }

    /** 大小写不敏感匹配挑战，返回表内规范 key；不匹配返回 null。 */
    private static String challengeKeyOf(String a) {
        for (var c : cn.quotidietium.balatro.engine.Data.CHALLENGES) {
            if (c.key().equalsIgnoreCase(a)) return c.key();
        }
        return null;
    }

    private void cmdHelp(Player player, String[] args) {
        if (args.length < 2) {
            BalatroHelp.sendPage(player, 1);
            return;
        }
        String arg = args[1];
        // 数字 → 分页帮助
        try {
            int page = Integer.parseInt(arg);
            BalatroHelp.sendPage(player, page);
            return;
        } catch (NumberFormatException ignored) {
            // 非数字 → 视作命令名
        }
        if (!BalatroHelp.sendCommandHelp(player, arg)) {
            player.sendMessage(Lang.t("cmd.err.unknown_command", arg));
        }
    }

    private void cmdQuit(Player player) {
        if (!plugin.sessionManager().isActive(player)) {
            player.sendMessage(Lang.t("cmd.err.no_run"));
            return;
        }
        plugin.sessionManager().end(player);
        player.sendMessage(Lang.t("cmd.msg.quit"));
    }

    private void cmdStatus(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null) {
            player.sendMessage(Lang.t("cmd.err.no_run"));
            return;
        }
        player.sendMessage(s.handDebug());
    }

    private void cmdPlayCard(Player player, String[] args) {
        GameSession s = requireRound(player);
        if (s == null) return;
        List<Integer> ids = parseIndices(player, s, args, 1);
        if (ids == null) return;
        Engine.PlayResult r = s.play(ids);
        report(player, s, r);
    }

    private void cmdDiscard(Player player, String[] args) {
        GameSession s = requireRound(player);
        if (s == null) return;
        List<Integer> ids = parseIndices(player, s, args, 1);
        if (ids == null) return;
        Engine.PlayResult r = s.discard(ids);
        if (!r.ok) {
            player.sendMessage("§c" + r.err);
            return;
        }
        player.sendMessage(Lang.t("cmd.msg.discarded"));
        player.sendMessage(s.handDebug());
    }

    private void cmdEndless(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null) {
            player.sendMessage(Lang.t("cmd.err.no_run"));
            return;
        }
        if (s.continueEndless()) {
            player.sendMessage(Lang.t("cmd.msg.endless_on"));
            player.sendMessage(s.handDebug());
        } else {
            player.sendMessage(Lang.t("cmd.err.endless_unavailable"));
        }
    }

    private GameSession requireRound(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null) {
            player.sendMessage(Lang.t("cmd.err.no_run_play"));
            return null;
        }
        if (s.state().phase != cn.quotidietium.balatro.engine.Phase.ROUND) {
            player.sendMessage(Lang.t("cmd.err.not_round"));
            return null;
        }
        return s;
    }

    private List<Integer> parseIndices(Player player, GameSession s, String[] args, int from) {
        if (args.length <= from) {
            player.sendMessage(Lang.t("cmd.usage.playcard"));
            return null;
        }
        List<Integer> ids = new ArrayList<>();
        int handSize = s.state().hand.size();
        for (int i = from; i < args.length; i++) {
            int idx;
            try {
                idx = Integer.parseInt(args[i]);
            } catch (NumberFormatException e) {
                player.sendMessage(Lang.t("cmd.err.bad_index", args[i]));
                return null;
            }
            if (idx < 1 || idx > handSize) {
                player.sendMessage(Lang.t("cmd.err.index_range", idx, handSize));
                return null;
            }
            ids.add(s.state().hand.get(idx - 1).id());
        }
        return ids;
    }

    private void report(Player player, GameSession s, Engine.PlayResult r) {
        if (!r.ok) {
            player.sendMessage("§c" + r.err);
            return;
        }
        player.sendMessage(Lang.t("cmd.play.scored", r.type == null ? "-" : r.type.displayName(),
                r.score, s.state().roundScore, s.state().blindTarget));
        if (r.won) {
            if (s.state().won) {
                player.sendMessage(Lang.t("cmd.play.won", s.state().seed));
            } else if (s.state().phase == cn.quotidietium.balatro.engine.Phase.ROUND) {
                player.sendMessage(Lang.t("cmd.play.blind_cleared_next"));
                player.sendMessage(s.handDebug());
            } else {
                player.sendMessage(Lang.t("cmd.play.blind_cleared"));
            }
        } else if (r.lost) {
            player.sendMessage(Lang.t("cmd.play.lost"));
        } else {
            player.sendMessage(s.handDebug());
        }
    }

    private void cmdShop(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null || s.state().phase != cn.quotidietium.balatro.engine.Phase.SHOP) {
            player.sendMessage(Lang.t("cmd.err.not_shop"));
            return;
        }
        var shop = s.state().shop;
        player.sendMessage(Lang.t("cmd.shop.title", s.state().money));
        int i = 0;
        for (var c : shop.cards) {
            String label = shopCardLabel(c);
            String tail = c.sold ? " " + Lang.t("cmd.shop.sold") : " §a$" + c.price;
            player.sendMessage(Lang.t("cmd.line.indexed", i + 1, label, tail));
            i++;
        }
        int j = 0;
        for (var p : shop.packs) {
            player.sendMessage(Lang.t("cmd.shop.pack_line", j + 1, p.name, p.price,
                    p.sold ? " " + Lang.t("cmd.shop.sold") : ""));
            j++;
        }
        int vk = 0;
        for (var vch : shop.vouchers) {
            player.sendMessage(Lang.t("cmd.shop.voucher_line", vk + 1, vch.name, vch.price,
                    vch.sold ? " " + Lang.t("cmd.shop.sold") : ""));
            vk++;
        }
        player.sendMessage(Lang.t("cmd.shop.hint"));
    }

    private String shopCardLabel(cn.quotidietium.balatro.engine.shop.Shop.CardItem c) {
        return switch (c.kind) {
            case "joker" -> Lang.t("cmd.label.joker", c.name)
                    + (c.joker.edition != null ? "(" + c.joker.edition.displayName() + ")" : "");
            case "playing" -> Lang.t("cmd.label.playing", c.name);
            default -> c.kind + " " + c.name;
        };
    }

    private void cmdBuy(Player player, String[] args) {
        GameSession s = requireShop(player);
        if (s == null) return;
        int idx = parseOne(player, args);
        if (idx < 0) return;
        if (s.buyCard(idx)) player.sendMessage(Lang.t("cmd.msg.bought"));
        else player.sendMessage(Lang.t("cmd.err.buy_card_failed"));
        cmdShop(player);
    }

    private void cmdBuyPack(Player player, String[] args) {
        GameSession s = requireShop(player);
        if (s == null) return;
        int idx = parseOne(player, args);
        if (idx < 0) return;
        if (s.buyPack(idx)) {
            player.sendMessage(Lang.t("cmd.msg.bought_pack"));
            cmdPack(player); // 直接列出内容（与 cmdBuy 重列商店一致）；pick/skipack 提示在列表尾部
        } else {
            player.sendMessage(Lang.t("cmd.err.buy_failed"));
        }
    }

    private void cmdBuyVoucher(Player player, String[] args) {
        GameSession s = requireShop(player);
        if (s == null) return;
        // 多券时需指定序号；单券时允许省略（默认第 1 张），保持向后兼容
        int idx;
        if (args.length >= 2) {
            idx = parseOne(player, args);
            if (idx < 0) return;
        } else {
            if (s.state().shop.vouchers.size() == 1) idx = 0;
            else if (s.state().shop.vouchers.isEmpty()) {
                player.sendMessage(Lang.t("cmd.err.no_vouchers"));
                return;
            } else {
                player.sendMessage(Lang.t("cmd.err.many_vouchers"));
                return;
            }
        }
        if (s.buyVoucher(idx)) player.sendMessage(Lang.t("cmd.msg.bought_voucher"));
        else player.sendMessage(Lang.t("cmd.err.buy_voucher_failed"));
    }

    private void cmdReroll(Player player) {
        GameSession s = requireShop(player);
        if (s == null) return;
        long cost = s.reroll();
        if (cost < 0) player.sendMessage(Lang.t("cmd.err.reroll_failed"));
        else { player.sendMessage(Lang.t("cmd.msg.rerolled", cost)); cmdShop(player); }
    }

    private void cmdNext(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null || s.state().phase != cn.quotidietium.balatro.engine.Phase.SHOP) {
            player.sendMessage(Lang.t("cmd.err.not_shop"));
            return;
        }
        s.nextRound();
        promptBlindSelect(player, s);
    }

    private void cmdGo(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null || s.state().phase != cn.quotidietium.balatro.engine.Phase.BLIND_SELECT) {
            player.sendMessage(Lang.t("cmd.err.not_blind_select"));
            return;
        }
        if (s.chooseBlind(false)) {
            player.sendMessage(Lang.t("cmd.msg.blind_started"));
            player.sendMessage(s.handDebug());
        }
    }

    private void cmdSkip(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null || s.state().phase != cn.quotidietium.balatro.engine.Phase.BLIND_SELECT) {
            player.sendMessage(Lang.t("cmd.err.not_blind_select"));
            return;
        }
        if (!s.chooseBlind(true)) {
            player.sendMessage(Lang.t("cmd.err.skip_boss"));
            return;
        }
        // 跳过可能获得「立即开包」标签（standard/buffoon → 引擎进入补充包阶段）：
        // 全息路径由 board.update 自动列出简介，命令路径在此同步列出，避免玩家不知已进入开包
        if (s.state().phase == cn.quotidietium.balatro.engine.Phase.PACK && s.state().pack != null) {
            player.sendMessage(Lang.t("cmd.msg.skip_tag_pack"));
            cmdPack(player);
            return;
        }
        promptBlindSelect(player, s);
    }

    /** 提示当前盲注选择（开始/跳过）。 */
    private void promptBlindSelect(Player player, GameSession s) {
        if (s.state().phase != cn.quotidietium.balatro.engine.Phase.BLIND_SELECT) return;
        var bt = cn.quotidietium.balatro.engine.Data.BlindType.byKey(s.state().nextBlind);
        long target = cn.quotidietium.balatro.engine.Engine.blindTarget(s.state(), bt);
        if (bt == cn.quotidietium.balatro.engine.Data.BlindType.BOSS && !s.state().bossQueue.isEmpty()) {
            var bd = cn.quotidietium.balatro.engine.Data.Boss.byKey(s.state().bossQueue.get(0));
            player.sendMessage(Lang.t("cmd.blind.next_boss",
                    s.state().ante, blindName(bt.key), bd.displayName(), target));
            player.sendMessage(Lang.t("cmd.blind.boss_effect", bd.desc()));
        } else {
            player.sendMessage(Lang.t("cmd.blind.next", s.state().ante, blindName(bt.key), target));
        }
        player.sendMessage(bt == cn.quotidietium.balatro.engine.Data.BlindType.BOSS
                ? Lang.t("cmd.blind.hint_boss") : Lang.t("cmd.blind.hint"));
    }

    private static String blindName(String key) {
        return switch (key) {
            case "small" -> Lang.t("blind.small");
            case "big" -> Lang.t("blind.big");
            case "boss" -> Lang.t("blind.boss");
            default -> key;
        };
    }

    private GameSession requireShop(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null || s.state().phase != cn.quotidietium.balatro.engine.Phase.SHOP) {
            player.sendMessage(Lang.t("cmd.err.not_shop"));
            return null;
        }
        return s;
    }

    private int parseOne(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Lang.t("cmd.err.missing_index"));
            return -1;
        }
        // 用 long 解析再转 int：避免 Integer.MIN_VALUE 等极值在「-1」时溢出回绕为正数
        // （虽下游引擎层仍有越界兜底，但命令层应自行正确拦截，不依赖下游）。
        final long v;
        try {
            v = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Lang.t("cmd.err.bad_number", args[1]));
            return -1;
        }
        if (v < 1 || v > Integer.MAX_VALUE) {
            player.sendMessage(Lang.t("cmd.err.bad_number", args[1]));
            return -1;
        }
        return (int) v - 1; // 1-based → 0-based
    }

    private void cmdCons(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null) { player.sendMessage(Lang.t("cmd.err.no_run")); return; }
        if (s.state().consumables.isEmpty()) { player.sendMessage(Lang.t("cmd.msg.no_consumables")); return; }
        int i = 0;
        for (var c : s.state().consumables) {
            player.sendMessage("§d[" + (i + 1) + "] §f" + c.kind + " " + c.name() + " §7" + c.desc());
            i++;
        }
        player.sendMessage(Lang.t("cmd.cons.hint"));
    }

    private void cmdUse(Player player, String[] args) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null) { player.sendMessage(Lang.t("cmd.err.no_run")); return; }
        if (args.length < 2) { player.sendMessage(Lang.t("cmd.usage.use")); return; }
        // 统一经 parseOne 解析消耗品序号（与 buy/pick/sell 一致）：1-based→0-based + 越界/非数字拦截。
        // 此前手写解析是唯一缺 <0 拦截的序号命令（越界仅靠引擎兜底，且文案逊于其它命令）。
        int cidx = parseOne(player, args);
        if (cidx < 0) return;
        // 参数三形态（板端确认按钮只产生 ①+②，手动输入只产生 ③，混用 ②③ 拒绝）：
        //   ① kind:key 期望标识（含冒号）：确认后到点击前消耗品列表可能已变化（使用/出售
        //     收缩列表），序号可能指向另一个消耗品——校验不一致则取消（R52 防错位，向后兼容）。
        //   ② @id1,id2 目标快照令牌（R225）：全息「确认使用」携带确认时刻选中牌的卡 id。
        //   ③ 纯数字：手动手牌序号（1-based，旧路径不变）。
        String expectKindKey = null;
        List<Integer> snapshotIds = null;
        List<Integer> cardIds = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.isEmpty()) { player.sendMessage(Lang.t("cmd.err.bad_arg", a)); return; }
            if (a.indexOf(':') >= 0) {
                if (expectKindKey != null) { player.sendMessage(Lang.t("cmd.err.bad_arg", a)); return; }
                expectKindKey = a;
            } else if (a.charAt(0) == '@') {
                if (snapshotIds != null) { player.sendMessage(Lang.t("cmd.err.bad_arg", a)); return; }
                snapshotIds = UseTargets.parseAtIds(a);
                if (snapshotIds == null) { player.sendMessage(Lang.t("cmd.err.bad_token", a)); return; }
            } else {
                int hi;
                try {
                    hi = Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    player.sendMessage(Lang.t("cmd.err.bad_hand_index", a));
                    return;
                }
                if (hi < 1 || hi > s.state().hand.size()) { player.sendMessage(Lang.t("cmd.err.hand_index_range", a)); return; }
                cardIds.add(s.state().hand.get(hi - 1).id());
            }
        }
        if (expectKindKey != null && !consKindKeyAt(s, cidx).equals(expectKindKey)) {
            player.sendMessage(Lang.t("cmd.err.cons_changed_use"));
            return;
        }
        if (snapshotIds != null) {
            if (!cardIds.isEmpty()) { player.sendMessage(Lang.t("cmd.err.mixed_targets")); return; }
            // 目标侧 TOCTOU：快照 id 必须全部仍在手牌（确认到点击之间出牌/弃牌/销毁都会
            // 改写手牌），缺一即整体取消。序号会漂移而卡 id 不会——这是携带 @id 而非序号的原因。
            for (int id : snapshotIds) {
                if (handIdAt(s, id) == null) {
                    player.sendMessage(Lang.t("cmd.err.hand_changed_use"));
                    return;
                }
            }
            cardIds = snapshotIds;
        }
        var r = s.useConsumable(cidx, cardIds);
        if (!r.ok) player.sendMessage("§c" + r.err);
        else { player.sendMessage(Lang.t("cmd.msg.used")); cmdCons(player); }
    }

    /** 手牌中查找卡 id；未命中返回 null（目标快照 TOCTOU 校验用）。 */
    private static cn.quotidietium.balatro.engine.Card handIdAt(GameSession s, int id) {
        for (var c : s.state().hand) if (c.id() == id) return c;
        return null;
    }

    /** 当前消耗品 idx 处的期望标识（kind:key）；越界返回空串（必不匹配，走「列表已变化」取消）。 */
    private static String consKindKeyAt(GameSession s, int idx) {
        var cons = s.state().consumables;
        if (idx < 0 || idx >= cons.size()) return "";
        var c = cons.get(idx);
        return c.kind + ":" + c.key;
    }

    private void cmdPack(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null || s.state().phase != cn.quotidietium.balatro.engine.Phase.PACK || s.state().pack == null) {
            player.sendMessage(Lang.t("cmd.err.no_open_pack"));
            return;
        }
        var pack = s.state().pack;
        player.sendMessage(Lang.t("cmd.pack.title", pack.def.displayName(), pack.left));
        int i = 0;
        for (var c : pack.cards) {
            String label = switch (c.kind) {
                case "joker" -> Lang.t("cmd.label.joker", c.name);
                case "playing" -> Lang.t("cmd.label.playing", c.name);
                default -> c.kind + " " + c.name;
            };
            player.sendMessage(Lang.t("cmd.line.indexed", i + 1, label,
                    c.taken ? " " + Lang.t("cmd.pack.taken") : ""));
            i++;
        }
        player.sendMessage(Lang.t("cmd.pack.hint"));
    }

    private void cmdPick(Player player, String[] args) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null || s.state().pack == null) { player.sendMessage(Lang.t("cmd.err.no_pack")); return; }
        int idx = parseOne(player, args);
        if (idx < 0) return;
        if (s.pickPack(idx)) { player.sendMessage(Lang.t("cmd.msg.picked")); if (s.state().phase == cn.quotidietium.balatro.engine.Phase.PACK) cmdPack(player); }
        else player.sendMessage(Lang.t("cmd.err.pick_failed"));
    }

    private void cmdSkipPack(Player player) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null || s.state().pack == null) { player.sendMessage(Lang.t("cmd.err.no_pack")); return; }
        s.skipPack();
        player.sendMessage(Lang.t("cmd.msg.pack_skipped"));
    }

    private void cmdSellJoker(Player player, String[] args) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null) { player.sendMessage(Lang.t("cmd.err.no_run")); return; }
        int idx = parseOne(player, args);
        if (idx < 0) return;
        // 全息「确认出售」按钮在第 3 参数携带期望 joker key：确认后到点击前小丑列表
        // 可能已被改写（幻灵 hex/ankh、命令出售等），序号可能指向另一张小丑——
        // 校验不一致则取消，防止错位卖错。手动输入不带标识则跳过校验（向后兼容）。
        if (args.length >= 3 && !jokerKeyAt(s, idx).equals(args[2])) {
            player.sendMessage(Lang.t("cmd.err.joker_changed_sell"));
            return;
        }
        if (s.sellJoker(idx)) player.sendMessage(Lang.t("cmd.msg.joker_sold"));
        else player.sendMessage(Lang.t("cmd.err.sell_joker_failed"));
    }

    /** 当前小丑 idx 处的期望 key；越界返回空串（必不匹配，走「列表已变化」取消）。 */
    private static String jokerKeyAt(GameSession s, int idx) {
        var jokers = s.state().jokers;
        return (idx >= 0 && idx < jokers.size()) ? jokers.get(idx).def.key() : "";
    }

    private void cmdSellConsumable(Player player, String[] args) {
        GameSession s = plugin.sessionManager().get(player);
        if (s == null) { player.sendMessage(Lang.t("cmd.err.no_run")); return; }
        int idx = parseOne(player, args);
        if (idx < 0) return;
        // 全息「确认出售」按钮在第 3 参数携带期望 kind:key：确认后到点击前消耗品列表
        // 可能已变化（使用/出售收缩列表），序号可能指向另一个消耗品——
        // 校验不一致则取消，防止错位卖错。手动输入不带标识则跳过校验（向后兼容）。
        if (args.length >= 3 && !consKindKeyAt(s, idx).equals(args[2])) {
            player.sendMessage(Lang.t("cmd.err.cons_changed_sell"));
            return;
        }
        if (s.sellConsumable(idx)) player.sendMessage(Lang.t("cmd.msg.cons_sold"));
        else player.sendMessage(Lang.t("cmd.err.sell_failed"));
    }

    /** 显示版本与版权信息（版本号来自 plugin.yml，构建时注入 Gradle 版本）。 */
    private void cmdVersion(Player player) {
        for (String line : VersionInfo.lines(plugin.getPluginMeta().getVersion())) {
            player.sendMessage(line);
        }
    }

    private void cmdTop(Player player) {
        // 聚合排行榜每次全量遍历统计记录（上限上万条）：篡改客户端可宏刷这条只读命令，
        // 让主线程反复做聚合+排序——每玩家 1s 节流。
        long now = System.currentTimeMillis();
        Long last = lastTop.get(player.getUniqueId());
        if (last != null && now - last < TOP_THROTTLE_MS) {
            player.sendMessage(Lang.t("cmd.err.throttled"));
            return;
        }
        lastTop.put(player.getUniqueId(), now);
        // 无条件惰性清扫：每次调用都清理 >60s 的过期条目。
        // 此前仅在 size>128 时清扫——大量不同 UUID（离线模式/UUID 伪造）在 60s 内各调用一次
        // 会导致 Map 无限增长（慢速内存泄漏）。改为无条件清扫使 Map 大小自然受限于
        // 「过去 60s 内调用过 /top 的不同玩家数」，无需依赖 PlayerQuitEvent。
        lastTop.values().removeIf(t -> now - t > 60_000L);
        java.util.List<cn.quotidietium.balatro.api.PlayerStat> aggregated;
        try {
            aggregated = plugin.services().leaderboard().topAggregated(10);
        } catch (RuntimeException ex) {
            // 第三方排行榜服务异常：不向上击穿命令，降级为友好提示
            plugin.getLogger().warning(Lang.t("cmd.log.leaderboard_failed", ex));
            player.sendMessage(Lang.t("cmd.err.leaderboard_unavailable"));
            return;
        }
        if (aggregated.isEmpty()) { player.sendMessage(Lang.t("cmd.msg.no_records")); return; }
        // 补玩家名后在 Bukkit 层做完整三级排序：bestAnte 降序 → winCount 降序 → 玩家名升序
        java.util.List<String[]> rows = new java.util.ArrayList<>(); // {name, bestAnte, winCount}
        for (var ps : aggregated) {
            String name = plugin.getServer().getOfflinePlayer(ps.playerId()).getName();
            if (name == null) name = ps.playerId().toString().substring(0, 8);
            rows.add(new String[]{name, String.valueOf(ps.bestAnte()), String.valueOf(ps.winCount())});
        }
        rows.sort((a, b) -> {
            int anteA = Integer.parseInt(a[1]), anteB = Integer.parseInt(b[1]);
            if (anteA != anteB) return Integer.compare(anteB, anteA); // 降序
            int wcA = Integer.parseInt(a[2]), wcB = Integer.parseInt(b[2]);
            if (wcA != wcB) return Integer.compare(wcB, wcA); // 降序
            return a[0].compareToIgnoreCase(b[0]); // 玩家名升序
        });
        player.sendMessage(Lang.t("cmd.top.title"));
        int rank = 1;
        for (var row : rows) {
            int ante = Integer.parseInt(row[1]);
            int wc = Integer.parseInt(row[2]);
            String anteStr = ante > 8 ? Lang.t("cmd.top.endless", ante) : Lang.t("cmd.top.ante", ante);
            player.sendMessage(Lang.t("cmd.top.row", rank++, row[0], anteStr, wc));
        }
    }

    /**
     * 直接输入 /balatro（无参数 / 未知子命令）时的简要帮助。
     * 覆盖全部面向玩家的命令，按游戏阶段分组；详细玩法见 {@code /balatro help}。
     * {@code cancel} 不列出：它是全息出售确认框「[取消]」按钮的回执，非玩法命令。
     *
     * <p>每个命令令牌均为可悬浮（显示该命令的详细说明与使用举例）+ 可点击（回填命令）的组件，
     * 由 {@link HoverText} 从帮助注册表生成——文案只维护 {@link BalatroHelp} 一份。
     */
    private void sendHelp(Player player) {
        player.sendMessage(Lang.t("cmd.help.title"));
        player.sendMessage(HoverText.commandify(Lang.t("cmd.help.intro")));
        player.sendMessage(HoverText.commandify(Lang.t("cmd.help.full")));
        player.sendMessage(Lang.t("cmd.help.sec.general"));
        player.sendMessage(line(t("gui"), " ", Lang.t("cmd.help.d.gui"), "  ",
                t("play"), " ", Lang.t("cmd.help.d.play"), "  ",
                t("status"), " ", Lang.t("cmd.help.d.status")));
        player.sendMessage(line(t("endless"), " ", Lang.t("cmd.help.d.endless"), "  ",
                t("top"), " ", Lang.t("cmd.help.d.top"), "  ",
                t("quit"), " ", Lang.t("cmd.help.d.quit")));
        player.sendMessage(Lang.t("cmd.help.sec.round"));
        player.sendMessage(line(t("playcard"), " ", Lang.t("cmd.help.d.playcard"), "  ",
                t("disc"), " ", Lang.t("cmd.help.d.disc")));
        player.sendMessage(Lang.t("cmd.help.sec.blind"));
        player.sendMessage(line(t("go"), " ", Lang.t("cmd.help.d.go"), "  ",
                t("skip"), " ", Lang.t("cmd.help.d.skip")));
        player.sendMessage(Lang.t("cmd.help.sec.shop"));
        player.sendMessage(line(t("shop"), " ", Lang.t("cmd.help.d.view"), "  ",
                t("buy"), " ", Lang.t("cmd.help.d.buy"), "  ",
                t("buybag"), " ", Lang.t("cmd.help.d.buybag")));
        player.sendMessage(line(t("buyvoucher"), " ", Lang.t("cmd.help.d.buyvoucher"), "  ",
                t("reroll"), " ", Lang.t("cmd.help.d.reroll"), "  ",
                t("next"), " ", Lang.t("cmd.help.d.next")));
        player.sendMessage(Lang.t("cmd.help.sec.cons"));
        player.sendMessage(line(t("cons"), " ", Lang.t("cmd.help.d.view"), "  ",
                t("use"), " ", Lang.t("cmd.help.d.use")));
        player.sendMessage(Lang.t("cmd.help.sec.packs"));
        player.sendMessage(line(t("packs"), " ", Lang.t("cmd.help.d.view"), "  ",
                t("pick"), " ", Lang.t("cmd.help.d.pick"), "  ",
                t("skipack"), " ", Lang.t("cmd.help.d.skipack")));
        player.sendMessage(Lang.t("cmd.help.sec.sell"));
        player.sendMessage(line(t("sellj"), " ", Lang.t("cmd.help.d.sellj"), "  ",
                t("sellc"), " ", Lang.t("cmd.help.d.sellc")));
    }

    /** 可悬浮/可点击的命令令牌（显示裸命令名，悬浮 = 详情与举例，点击 = 回填 /balatro <主键>）。 */
    private static net.kyori.adventure.text.Component t(String key) {
        return HoverText.token(key, key);
    }

    /** 拼接一行：组件原样加入，字符串按灰色说明文字加入。 */
    private static net.kyori.adventure.text.Component line(Object... parts) {
        net.kyori.adventure.text.Component out = net.kyori.adventure.text.Component.empty();
        for (Object p : parts) {
            if (p instanceof net.kyori.adventure.text.Component c) {
                out = out.append(c);
            } else {
                out = out.append(net.kyori.adventure.text.Component.text(
                        String.valueOf(p), net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // 无 balatro.play 权限者不补全（与 onCommand 的权限实施一致，不暴露命令结构）
        if (!(sender instanceof Player) || !sender.hasPermission("balatro.play")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(args[0], SUBS);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("play")) {
            // 补全牌组名（其余参数顺序不限，牌组名是最有用的提示）
            java.util.List<String> decks = new java.util.ArrayList<>();
            for (var d : cn.quotidietium.balatro.engine.Data.DECKS) decks.add(d.key());
            decks.addAll(java.util.Arrays.asList("0", "1", "2", "3", "4", "5", "6", "7")); // 赌注
            return filter(args[args.length - 1], decks);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            // 页码 + 命令名（含别名）
            java.util.List<String> opts = new java.util.ArrayList<>();
            for (int p = 1; p <= BalatroHelp.totalPages(); p++) opts.add(Integer.toString(p));
            opts.addAll(BalatroHelp.commandKeys());
            return filter(args[1], opts);
        }
        return List.of();
    }

    private List<String> filter(String prefix, List<String> options) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(prefix.toLowerCase())) out.add(o);
        }
        return out;
    }
}
