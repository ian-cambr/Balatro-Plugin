package cn.quotidietium.balatro.listener;

import cn.quotidietium.balatro.BalatroPlugin;
import cn.quotidietium.balatro.i18n.Lang;
import cn.quotidietium.balatro.render.RoundBoard;
import cn.quotidietium.balatro.session.GameSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * 全息牌桌交互（统一规则）。
 *
 * <p>命中机制：用 {@link Interaction} 实体承载点击——它有 width×height 原生命中盒，
 * 玩家右键它确定触发 {@link PlayerInteractEntityEvent}。动作编进 scoreboard tag（{@code balatro_i_<action>}）。
 *
 * <p><b>统一交互规则</b>：
 * <ul>
 *   <li><b>Shift + 右键</b> = 查看该卡简介（发到聊天框）；按钮等无简介的则执行操作。</li>
 *   <li><b>直接右键</b> = 使用/操作（选中手牌 / 购买商品 / 选择补充包卡 / 使用消耗品 / 出售小丑 / 出牌弃牌…）。</li>
 * </ul>
 * 含每玩家 150ms 节流与点击音效。
 */
public final class BoardListener implements Listener {

    private static final String TAG_PREFIX = "balatro_i_";
    private static final long THROTTLE_MS = 150;

    private final BalatroPlugin plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public BoardListener(BalatroPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Interaction)) return;

        Player player = event.getPlayer();
        GameSession session = plugin.sessionManager().get(player);
        if (session == null || session.board() == null) return;
        // 归属校验：只接受本人本牌桌创建的命中盒。客户端不可信（可被篡改、可对任意
        // 实体 id 发交互包），其他玩家牌桌或来路不明的 balatro_i_* 标签实体一律拒绝。
        if (!session.board().ownsInteraction(entity)) return;
        // 归属确认即取消：本牌桌命中盒的点击一律由插件吞并——包括被节流的点击。
        // （此前取消在节流之后：被节流的点击不取消，事件漏给原版行为与其他插件。）
        event.setCancelled(true);

        String action = null;
        for (String t : entity.getScoreboardTags()) {
            if (t.startsWith(TAG_PREFIX)) {
                action = t.substring(TAG_PREFIX.length());
                break;
            }
        }
        if (action == null) return;
        if (!throttle(player)) return;

        try {
            dispatch(player, session, session.board(), action, player.isSneaking());
        } catch (RuntimeException ex) {
            // 交互派发绝不向事件/网络层抛异常（防止异常客户端输入造成踢出/报错）
            plugin.getLogger().warning(Lang.t("log.board_interact_failed", player.getName(), action, ex));
        }
    }

    private void dispatch(Player player, GameSession session, RoundBoard board, String act, boolean sneak) {
        // Shift + 右键：查看简介（若有），不执行操作
        if (sneak) {
            Component info = board.infoFor(session.state(), act);
            if (info != null) {
                player.sendMessage(info);
                click(player, 1.6f);
                return;
            }
            // 按钮等无简介：继续执行操作
        }

        // 直接右键（或无简介的 Shift 右键）：执行使用/操作
        switch (act) {
            case "play" -> { board.playSelected(); click(player, 1.2f); }
            case "discard" -> { board.discardSelected(); click(player, 0.8f); }
            case "reroll" -> {
                if (session.reroll() < 0) fail(player, Lang.t("err.reroll_failed"));
                else board.refreshShopInfo(); // 商品/价格全变，刷新聊天框简介防误导
                click(player, 1.0f);
            }
            case "next" -> { session.nextRound(); click(player, 1.2f); }
            case "go" -> { session.chooseBlind(false); click(player, 1.2f); }
            case "skip" -> {
                if (!session.chooseBlind(true)) fail(player, Lang.t("err.skip_boss"));
                click(player, 0.8f);
            }
            case "skipack" -> { session.skipPack(); click(player, 0.8f); }
            default -> {
                if (act.startsWith("card:")) {
                    Integer id = parseIntSafe(act.substring("card:".length()));
                    if (id != null) {
                        boolean changed = board.toggleSelect(id);
                        if (changed) click(player, 1.6f); // 超限拒绝时不播音效（不作出反应）
                    }
                } else if (act.startsWith("shop:")) {
                    Integer i = parseIntSafe(act.substring("shop:".length()));
                    if (i != null) {
                        if (!session.buyCard(i)) fail(player, Lang.t("err.buy_card_failed"));
                        click(player, 1.0f);
                    }
                } else if (act.startsWith("shoppack:")) {
                    Integer i = parseIntSafe(act.substring("shoppack:".length()));
                    if (i != null) {
                        if (!session.buyPack(i)) fail(player, Lang.t("err.buy_failed"));
                        click(player, 1.0f);
                    }
                } else if (act.startsWith("pick:")) {
                    Integer i = parseIntSafe(act.substring("pick:".length()));
                    if (i != null) {
                        if (!session.pickPack(i)) fail(player, Lang.t("err.pick_failed"));
                        click(player, 1.2f);
                    }
                } else if (act.startsWith("joker:")) {
                    Integer i = parseIntSafe(act.substring("joker:".length()));
                    if (i != null) {
                        board.sendSellConfirm(player, i);
                        click(player, 0.8f);
                    }
                } else if (act.startsWith("voucher:")) {
                    Integer i = parseIntSafe(act.substring("voucher:".length()));
                    if (i != null) {
                        if (!session.buyVoucher(i)) fail(player, Lang.t("err.buy_failed"));
                        click(player, 1.0f);
                    }
                } else if (act.startsWith("cons:")) {
                    Integer i = parseIntSafe(act.substring("cons:".length()));
                    if (i != null) {
                        // 商店阶段右键消耗品 = 出售（对齐原版：商店里持有的消耗品可点击出售）；
                        // 回合阶段右键消耗品 = 使用（保持原有行为）。
                        if (session.state().phase == cn.quotidietium.balatro.engine.Phase.SHOP) {
                            board.sendSellConsumableConfirm(player, i);
                        } else {
                            board.sendUseConfirm(player, i);
                        }
                        click(player, 1.0f);
                    }
                }
            }
        }
    }

    /** 交互失败提示（聊天框）。 */
    private static void fail(Player player, String msg) {
        player.sendMessage(Component.text(msg, net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    /** 宽松整数解析：非法（含越界/空/非数字）返回 null，绝不抛出。 */
    private static Integer parseIntSafe(String s) {
        if (s == null || s.isEmpty() || s.length() > 9) return null;
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return null;
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    private void click(Player player, float pitch) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 1.0f, pitch);
    }

    private boolean throttle(Player player) {
        long now = System.currentTimeMillis();
        long last = lastClick.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < THROTTLE_MS) return false;
        lastClick.put(player.getUniqueId(), now);
        return true;
    }

    /** 玩家离线即清理其节流记录，避免长期运行下 map 无限增长。 */
    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }
}
