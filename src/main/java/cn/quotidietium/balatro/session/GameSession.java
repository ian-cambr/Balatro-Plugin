package cn.quotidietium.balatro.session;

import cn.quotidietium.balatro.BalatroPlugin;
import cn.quotidietium.balatro.api.RunSummary;
import cn.quotidietium.balatro.api.event.BalatroAnteClearEvent;
import cn.quotidietium.balatro.api.event.BalatroBlindResultEvent;
import cn.quotidietium.balatro.api.event.BalatroHandScoreEvent;
import cn.quotidietium.balatro.api.event.BalatroRunEndEvent;
import cn.quotidietium.balatro.engine.Card;
import cn.quotidietium.balatro.engine.Data;
import cn.quotidietium.balatro.engine.Engine;
import cn.quotidietium.balatro.engine.Phase;
import cn.quotidietium.balatro.engine.RunState;
import cn.quotidietium.balatro.i18n.Lang;
import cn.quotidietium.balatro.render.RoundBoard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * 单个玩家的一局会话：包装 {@link RunState}，提供玩家可调用的动作（出牌/弃牌），
 * 并在关键节点（计分/盲注结算/底注通过/本局结束）触发自定义事件与调用扩展服务。
 *
 * <p>0.1.0：盲注选择自动推进（无手动选择/商店），胜出后直接进入下一盲注。
 */
public final class GameSession {

    private final BalatroPlugin plugin;
    private final Player player;
    private final RunState state;
    private RoundBoard board;
    private boolean aborted;

    public GameSession(BalatroPlugin plugin, Player player, String deckKey, int stakeIdx, String seed) {
        this(plugin, player, deckKey, stakeIdx, seed, null);
    }

    public GameSession(BalatroPlugin plugin, Player player, String deckKey, int stakeIdx, String seed, String challenge) {
        this.plugin = plugin;
        this.player = player;
        this.state = Engine.createRun(deckKey, stakeIdx, seed, challenge);
    }

    public BalatroPlugin plugin() {
        return plugin;
    }

    public Player player() {
        return player;
    }

    public RunState state() {
        return state;
    }

    public RoundBoard board() {
        return board;
    }

    public boolean isAborted() {
        return aborted;
    }

    /** 开始本局：触发 RunStart 事件（可取消），未取消则进入第一个小盲注回合。 */
    public boolean start() {
        BalatroPlugin.RunStartDecision decision = plugin.fireRunStart(player.getUniqueId(), state.seed, state.deckKey, state.stakeIdx);
        if (decision.cancelled()) {
            aborted = true;
            return false;
        }
        autoAdvance();
        if (state.phase == Phase.ROUND) {
            board = new RoundBoard(this);
            try {
                board.spawn(state);
            } catch (RuntimeException ex) {
                // 生成中途失败（世界/区块异常等）：回收已生成的部分实体，避免无会话追踪的泄漏
                despawnBoard();
                throw ex;
            }
        }
        return true;
    }

    /** 出牌（cardIds 为手牌中的卡 id）。 */
    public Engine.PlayResult play(List<Integer> cardIds) {
        if (state.phase != Phase.ROUND) {
            return Engine.PlayResult.err(Lang.t("err.not_in_round"));
        }
        Data.BlindType bt = state.blindType;
        int anteBefore = state.ante;
        long target = state.blindTarget;

        Engine.PlayResult r = Engine.playHand(state, cardIds);

        // 计分事件（用于实时展示）——仅在实际发生计分时发出；
        // 被拒绝的出牌（选牌非法/Boss 限制等）不产生计分，不应发事件。
        if (r.ok) {
            plugin.fireHandScore(player.getUniqueId(),
                    r.type == null ? "-" : r.type.key,
                    r.score, state.roundScore, target, state.handsLeft);
        }

        if (r.ok && r.won) {
            plugin.fireBlindResult(player.getUniqueId(), anteBefore, bt.key, target, state.roundScore, true);
            safeService("RewardService.onBlindCleared",
                    () -> plugin.services().reward().onBlindCleared(player.getUniqueId(), anteBefore, bt.key));
            // 底注真正清空（引擎已进入商店）才发 AnteClear：双 Boss 挑战击败第一个 Boss 后
            // 立即接第二个 Boss（phase=BLIND_SELECT），底注尚未清空，不该发过关奖励
            if (bt == Data.BlindType.BOSS && state.phase == Phase.SHOP) {
                plugin.fireAnteClear(player.getUniqueId(), anteBefore);
                safeService("RewardService.onAnteCleared",
                        () -> plugin.services().reward().onAnteCleared(player.getUniqueId(), anteBefore));
            }
            if (bt == Data.BlindType.BOSS && state.phase == Phase.BLIND_SELECT) {
                // 双 Boss 挑战转场提示（命令与全息出牌路径都会经过这里）
                player.sendMessage(Lang.t("msg.second_boss", Engine.bossDef(state).displayName()));
            }
            // 防御性守卫：playHand/endRound/openShop 路径不设 state.won（仅 Engine.nextRound 在
            // 击败 ante8 boss 后设 true），故此处当前不可达——真正触发通关 finishRun 的是
            // nextRound()（玩家 /balatro next 后）。保留作防御，若未来引擎在 playHand 内直接
            // 通关则此分支生效，避免漏发 RunEnd。
            if (state.won) {
                finishRun(true, state.ante);
            }
            // else：phase 已为 SHOP（endRound→openShop），等待 /balatro next 推进
        } else if (r.ok && r.lost) {
            plugin.fireBlindResult(player.getUniqueId(), anteBefore, bt.key, target, state.roundScore, false);
            finishRun(false, anteBefore);
        }
        if (board != null) {
            board.clearSelection();
            board.update(state);
        }
        return r;
    }

    /** 弃牌。 */
    public Engine.PlayResult discard(List<Integer> cardIds) {
        Engine.PlayResult r = Engine.discard(state, cardIds);
        if (board != null) {
            board.clearSelection();
            board.update(state);
        }
        return r;
    }

    /** 销毁牌桌实体（会话结束时调用）。 */
    public void despawnBoard() {
        if (board != null) {
            board.despawn();
            board = null;
        }
    }

    /** 继续无尽模式（通关后）。 */
    public boolean continueEndless() {
        if (Engine.continueEndless(state)) {
            autoAdvance();
            if (board != null) board.update(state);
            return true;
        }
        return false;
    }

    /** 离开商店，进入下一盲注的【选择阶段】（不自动开始；用 go/skip 选择）。 */
    public boolean nextRound() {
        if (state.phase != Phase.SHOP) return false;
        Engine.nextRound(state);
        if (state.won) {
            finishRun(true, state.ante);
            return true;
        }
        // 停在 BLIND_SELECT，等待玩家 go（开始）/ skip（跳过获标签）
        if (board != null) board.update(state);
        return true;
    }

    /**
     * 在盲注选择阶段：开始当前盲注（{@code skip=false}）或跳过并获标签（{@code skip=true}）。
     * Boss 盲注不可跳过。返回是否成功推进。
     */
    public boolean chooseBlind(boolean skip) {
        if (state.phase != Phase.BLIND_SELECT) return false;
        boolean ok = Engine.selectBlind(state, Data.BlindType.byKey(state.nextBlind), skip);
        if (!ok) return false;
        if (board != null) board.update(state); // 开始→回合；跳过→下一盲注选择
        return true;
    }

    /** 购买商店第 idx 张商品（卡牌行）。 */
    public boolean buyCard(int idx) {
        if (state.phase != Phase.SHOP) return false;
        boolean ok = cn.quotidietium.balatro.engine.shop.Shop.buyCard(state, idx);
        if (board != null) board.update(state);
        return ok;
    }

    /** 购买第 idx 个补充包。 */
    public boolean buyPack(int idx) {
        if (state.phase != Phase.SHOP) return false;
        boolean ok = cn.quotidietium.balatro.engine.shop.Shop.buyPack(state, idx);
        if (board != null) board.update(state);
        return ok;
    }

    /** 购买第 idx 张优惠券（0-based）。 */
    public boolean buyVoucher(int idx) {
        if (state.phase != Phase.SHOP) return false;
        boolean ok = cn.quotidietium.balatro.engine.shop.Shop.buyVoucher(state, idx);
        if (board != null) board.update(state);
        return ok;
    }

    /** 商店重掷；返回本次费用，-1 表示失败。 */
    public long reroll() {
        if (state.phase != Phase.SHOP) return -1;
        long cost = cn.quotidietium.balatro.engine.shop.Shop.reroll(state);
        if (cost >= 0 && board != null) board.update(state);
        return cost;
    }

    /** 使用消耗品 idx；cardIds 为目标手牌卡 id（可空）。 */
    public cn.quotidietium.balatro.engine.consumable.Consumables.Result useConsumable(int idx, List<Integer> cardIds) {
        var r = cn.quotidietium.balatro.engine.consumable.Consumables.use(state, idx, cardIds);
        if (r.ok && board != null) board.update(state);
        return r;
    }

    /** 从当前补充包选第 idx 张。 */
    public boolean pickPack(int idx) {
        boolean ok = cn.quotidietium.balatro.engine.shop.Packs.pick(state, idx);
        if (board != null) board.update(state);
        return ok;
    }

    /** 跳过当前补充包。 */
    public boolean skipPack() {
        boolean ok = cn.quotidietium.balatro.engine.shop.Packs.skip(state);
        if (board != null) board.update(state);
        return ok;
    }

    /** 出售第 idx 张小丑。 */
    public boolean sellJoker(int idx) {
        boolean ok = state.sellJoker(idx);
        if (ok && board != null) board.update(state);
        return ok;
    }

    /** 出售第 idx 个消耗品。 */
    public boolean sellConsumable(int idx) {
        boolean ok = state.sellConsumable(idx);
        if (ok && board != null) board.update(state);
        return ok;
    }

    /** 盲注选择阶段自动推进到下一盲注。 */
    private void autoAdvance() {
        if (state.phase == Phase.BLIND_SELECT && !state.endlessPending) {
            Engine.selectBlind(state, Data.BlindType.byKey(state.nextBlind), false);
        }
    }

    private void finishRun(boolean won, int anteReached) {
        plugin.fireRunEnd(player.getUniqueId(), won, anteReached, state.seed, state.deckKey, state.stakeIdx);
        // 可替换服务逐一隔离：任一第三方实现（Reward/Stats/WinCounter）抛异常
        // 不应跳过后续服务（如 reward 异常吃掉统计落盘），各自记日志后继续。
        safeService("RewardService.onRunEnd",
                () -> plugin.services().reward().onRunEnd(player.getUniqueId(), won, anteReached));
        safeService("StatsService.record", () -> plugin.services().stats().record(new RunSummary(
                player.getUniqueId(), won, anteReached, state.seed, state.deckKey, state.stakeIdx,
                System.currentTimeMillis())));
        // 通关 ante 8（或无尽中继续通关更高 ante）时递增独立通关计数器（供聚合排行榜）
        if (won) {
            safeService("WinCounter.increment", () -> {
                cn.quotidietium.balatro.api.service.WinCounter wc = plugin.services().winCounter();
                if (wc != null) wc.increment(player.getUniqueId());
            });
        }
        sendRunStats(won, anteReached);
        if (!won) {
            // 失败：销毁牌桌并移除会话，玩家可立刻 /balatro play 再来一局。
            // RunEnd 事件监听器可能已在回调中 end 旧局 + start 新局——此时 Map 里已是新会话。
            // endIfCurrent 防误杀新局：仅当本会话仍是当前会话时才 end。
            // 但若已被替换，旧会话的牌桌实体仍需回收（否则泄漏到世界直到重启）。
            if (!plugin.sessionManager().endIfCurrent(player, this)) {
                // 已被替换：仅回收本（旧）会话的牌桌实体，不触碰新会话
                try {
                    despawnBoard();
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning(Lang.t("log.old_board_reclaim_failed", player.getName(), ex));
                }
            }
        }
        // 通关(won)：保留会话与牌桌，玩家可选 /endless 继续或 /quit 结束
    }

    /**
     * 调用可替换服务并兜底：第三方实现抛异常仅记日志，不向游戏流程传播。
     *
     * <p>捕获 {@link Exception}（含 {@link RuntimeException}）：第三方 RewardService/StatsService/
     * WinCounter 的任何受检/非受检异常都不应中断后续服务调用（如 reward 异常吃掉统计落盘）。
     * {@link Error}（如 {@link StackOverflowError}/{@link OutOfMemoryError}/{@link NoClassDefFoundError}）
     * 不捕获——这类表示 JVM/类加载级故障，应当向上传播让框架感知，而非静默吞掉。
     */
    private void safeService(String what, Runnable r) {
        try {
            r.run();
        } catch (Exception ex) {
            plugin.getLogger().warning(Lang.t("log.session_step_failed", what, player.getName(), ex));
        }
    }

    /** 向玩家发送本局统计（任何结束情况都发）。 */
    private void sendRunStats(boolean won, int anteReached) {
        player.sendMessage(Lang.t("summary.title"));
        player.sendMessage(Lang.t(won ? "summary.won" : "summary.lost")
                + Lang.t("summary.ante", anteReached));
        String deckName = state.deckKey;
        try {
            deckName = Data.deckByKey(state.deckKey).name();
        } catch (IllegalArgumentException ignored) {
        }
        String stakeName = (state.stakeIdx >= 0 && state.stakeIdx < Data.STAKES.size())
                ? Data.STAKES.get(state.stakeIdx).name() : String.valueOf(state.stakeIdx);
        StringBuilder mode = new StringBuilder(Lang.t("summary.seed", state.seed))
                .append(Lang.t("summary.deck", deckName))
                .append(Lang.t("summary.stake", stakeName));
        if (state.challenge != null) {
            for (Data.Challenge c : Data.CHALLENGES) {
                if (c.key().equals(state.challenge)) {
                    mode.append(Lang.t("summary.challenge", c.name()));
                    break;
                }
            }
        }
        player.sendMessage(mode.toString());
        player.sendMessage(Lang.t("summary.stats", state.statsHandsPlayed, state.jokers.size(), state.money));
        if (won) {
            player.sendMessage(Lang.t("summary.endless"));
        } else {
            player.sendMessage(Lang.t("summary.replay"));
        }
    }

    /** 调试用：手牌的可读简报。 */
    public String handDebug() {
        StringBuilder sb = new StringBuilder();
        sb.append("ante=").append(state.ante)
                .append(" blind=").append(state.blindType == null ? "-" : state.blindType.key)
                .append(" phase=").append(state.phase)
                .append(" score=").append(state.roundScore).append("/").append(state.blindTarget)
                .append(" hands=").append(state.handsLeft).append(" discards=").append(state.discardsLeft)
                .append(" $").append(state.money).append(Lang.t("status.hand"));
        List<String> cards = new ArrayList<>();
        for (int i = 0; i < state.hand.size(); i++) {
            Card c = state.hand.get(i);
            cards.add((i + 1) + ":" + cardLabel(c));
        }
        sb.append(String.join("  ", cards));
        return sb.toString();
    }

    private static String cardLabel(Card c) {
        if (c.facedown()) return Lang.t("card.hidden"); // 身份保密（R238）：与板面「？」一致，防 status 通道泄露
        if (c.isStone()) return Lang.t("card.stone");
        return Data.Suit.byIndex(c.suit()).symbol + Data.rankName(c.rank());
    }
}
