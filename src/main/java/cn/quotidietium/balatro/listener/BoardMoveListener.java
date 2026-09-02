package cn.quotidietium.balatro.listener;

import cn.quotidietium.balatro.BalatroPlugin;
import cn.quotidietium.balatro.i18n.Lang;
import cn.quotidietium.balatro.session.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 玩家跨世界 / 远距离传送 / 死亡重生时，把全息牌桌迁移到玩家眼前。
 *
 * <p>牌桌实体锚定世界坐标且不跟随玩家移动。若不处理：跨世界后牌桌留在旧世界
 * （新世界里永久不可见、不可点，会话卡死在"有局无桌"状态）；远距离传送/重生后
 * 牌桌留在原地，玩家无法回去继续。迁移 = 按玩家新眼位重建牌桌（保留选中与局面）。
 */
public final class BoardMoveListener implements Listener {

    /** 同世界内触发迁移的传送距离阈值（格）。短距（末影珍珠等）不迁移。 */
    private static final double RELOCATE_DIST = 64.0;

    private final BalatroPlugin plugin;

    public BoardMoveListener(BalatroPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return; // 被其他插件取消的传送不迁移
        GameSession session = plugin.sessionManager().get(event.getPlayer());
        if (session == null || session.board() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        boolean worldChanged = from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld());
        // 先判世界（不同世界的 distanceSquared 无意义/抛异常），短路保证安全
        boolean needRelocate = worldChanged || from.distanceSquared(to) > RELOCATE_DIST * RELOCATE_DIST;
        if (needRelocate) {
            scheduleRelocate(event.getPlayer());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        GameSession session = plugin.sessionManager().get(event.getPlayer());
        if (session == null || session.board() == null) return;
        scheduleRelocate(event.getPlayer());
    }

    /** 延迟 1 tick 迁移：等传送/重生真正完成后再取玩家眼位，避免拿到旧坐标。 */
    private void scheduleRelocate(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            GameSession s = plugin.sessionManager().get(player);
            if (s == null || s.board() == null) return;
            try {
                s.board().relocate(player.getEyeLocation());
            } catch (RuntimeException ex) {
                plugin.getLogger().warning(Lang.t("log.board_move_failed", player.getName(), ex));
            }
        });
    }
}
