package cn.quotidietium.balatro.session;

import cn.quotidietium.balatro.BalatroPlugin;
import cn.quotidietium.balatro.i18n.Lang;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * 会话管理器：每玩家至多一局（{@code Map<UUID, GameSession>}）。
 * 退出即弃：结束会话即销毁 RunState（不存档），后续由渲染层负责回收实体。
 */
public final class SessionManager {

    private final BalatroPlugin plugin;
    private final Map<UUID, GameSession> sessions = new HashMap<>();

    public SessionManager(BalatroPlugin plugin) {
        this.plugin = plugin;
    }

    /** 开始一局；若已有进行中的局则返回 null（由调用方提示）。 */
    public GameSession start(Player player, String deckKey, int stakeIdx, String seed) {
        return start(player, deckKey, stakeIdx, seed, null);
    }

    /** 开始一局（带挑战模式）；若已有进行中的局则返回 null。 */
    public GameSession start(Player player, String deckKey, int stakeIdx, String seed, String challenge) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) return null;
        // 兜底校验（命令层已先行校验并提示）：种子来自客户端，不接受超长/非法字符集
        if (seed != null && !cn.quotidietium.balatro.engine.Rng.isValidSeed(seed)) {
            plugin.getLogger().warning(Lang.t("log.bad_seed", player.getName()));
            return null;
        }
        GameSession session = new GameSession(plugin, player, deckKey, stakeIdx, seed, challenge);
        boolean started;
        try {
            started = session.start();
        } catch (RuntimeException ex) {
            // 开局流程异常（如牌桌实体生成失败）：会话不入表，记日志后按开局失败处理
            plugin.getLogger().warning(Lang.t("log.start_failed", player.getName(), ex));
            return null;
        }
        if (!started) {
            // RunStart 被取消
            return null;
        }
        // putIfAbsent 防重入：fireRunStart 的事件监听器可能已为本玩家开过新局
        // （递归 start），直接 put 会覆盖并丢失新局的追踪（其牌桌实体泄漏）。
        if (sessions.putIfAbsent(id, session) != null) {
            plugin.getLogger().warning(Lang.t("log.start_reentry", player.getName()));
            try {
                session.despawnBoard();
            } catch (RuntimeException ignored) {
            }
            return null;
        }
        return session;
    }

    public GameSession get(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public boolean isActive(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** 结束会话（退出即弃）。 */
    public void end(Player player) {
        GameSession s = sessions.remove(player.getUniqueId());
        if (s != null) {
            try {
                s.despawnBoard();
            } catch (RuntimeException ex) {
                // 会话已移除；实体回收失败仅记日志（与 shutdownAll 同一兜底策略）
                plugin.getLogger().warning(Lang.t("log.board_reclaim_failed", player.getName(), ex));
            }
        }
    }

    /**
     * 仅当 {@code expected} 仍是本玩家当前会话时才结束它。
     *
     * <p>防重入误杀：BalatroRunEndEvent 等事件监听器可能在事件回调中先 end 再 start
     * （自动重开插件）。此时 Map 里已是新会话，调用方持有的旧会话不得再误杀新局。
     *
     * @return {@code true} 若本会话仍是当前会话并已结束；{@code false} 若已被替换（调用方
     *         需自行回收旧会话的牌桌实体，否则泄漏）。
     */
    public boolean endIfCurrent(Player player, GameSession expected) {
        if (sessions.get(player.getUniqueId()) != expected) return false;
        end(player);
        return true;
    }

    /** 关闭全部（onDisable / reload）：逐一销毁牌桌实体，避免世界内残留全息。 */
    public void shutdownAll() {
        for (GameSession s : sessions.values()) {
            try {
                s.despawnBoard();
            } catch (RuntimeException ignored) {
                // 关停阶段实体可能已随世界卸载失效，忽略个别清理失败
            }
        }
        sessions.clear();
    }
}
