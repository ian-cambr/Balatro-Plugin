package cn.quotidietium.balatro.render;

import cn.quotidietium.balatro.engine.Card;
import cn.quotidietium.balatro.engine.Data;
import cn.quotidietium.balatro.engine.Engine;
import cn.quotidietium.balatro.engine.HandEval;
import cn.quotidietium.balatro.engine.JokerInstance;
import cn.quotidietium.balatro.engine.Consumable;
import cn.quotidietium.balatro.engine.Phase;
import cn.quotidietium.balatro.engine.RunState;
import cn.quotidietium.balatro.engine.consumable.Consumables;
import cn.quotidietium.balatro.i18n.Lang;
import cn.quotidietium.balatro.session.GameSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 单个玩家回合的全息牌桌（TextDisplay 文字牌面）。
 *
 * <p><b>稳定实体（无闪烁）</b>：状态栏/按钮/各牌区槽位均为持久 TextDisplay，{@link #update}
 * 只原地改写文本/背景/位置/标签，不再 clear+respawn——故选牌、补牌时面板不闪烁。
 * 命中检测走 {@link org.bukkit.entity.Interaction} 实体（R151 修正注释：原「World.rayTraceEntities」
 * 为前代架构）：可点击元素由 {@link #placeInteraction} 摆放不可见命中盒，动作编进
 * {@code balatro_i_<action>} 标签，{@code BoardListener} 经 PlayerInteractEntityEvent 派发。
 * {@code balatro_card_<id>}/{@code balatro_act_*} 为遗留身份标记（现无消费方，保留无害）。
 *
 * <p><b>卡牌化外观</b>：每张牌以带背景色的色块呈现（点数 + 花色 + 增强/版本/蜡封角标），
 * 选中时改为绿色背景并向上抬起；手牌已由 {@link Engine#sortHand} 整理为点数降序，
 * 故呈现给玩家的始终是排列好的手牌。状态栏下方实时显示当前选中牌的牌型与基础筹码×倍率。
 *
 * <p>定位：以玩家眼部位置 + 朝向构造"前向基"，板面悬浮于玩家眼前；CENTER 朝向、私有可见（仅本人）。
 */
public final class RoundBoard {

    // ---- 布局常量 ----
    private static final double FORWARD = 2.6;
    private static final double CARD_SPACING = 0.78;
    private static final double HAND_Y = 0.0;
    private static final double SELECT_LIFT = 0.5; // 选中上移约半张牌高
    private static final double STATUS_Y = 2.5;
    private static final double EVAL_Y = 2.0;
    private static final double JOKER_Y = 1.4;
    private static final double JOKER_SPACING = 0.82;
    private static final double CONS_Y = 0.78;
    private static final double CONS_SPACING = 0.72;
    private static final double BUTTON_Y = -1.15;

    // ---- 牌面单元尺寸（设计规则：高:宽 = 1:0.62，竖向扑克牌）----
    // 全高 1.10 × 全宽 0.682 ≈ 1 : 0.620。命中盒沿用此比例，保证点击区与牌位
    // 布局呈 1:0.62。渲染牌面（TextDisplay）见 cardFace，随文字自适应、字符宽度
    // 不一，像素比例需实机以 CARD_TEXT_SCALE / 牌面行结构微调。
    private static final double CARD_H = 1.10;    // 牌面全高
    private static final double CARD_W = 0.682;   // 牌面全宽 = 0.62 × CARD_H
    private static final double CARD_HW = CARD_W / 2.0;  // 命中半宽 0.341
    private static final double CARD_HH = CARD_H / 2.0;  // 命中半高 0.55
    /** 选牌数量上限（对齐引擎出牌/弃牌最多 5 张）。超过则拒绝选中并聊天提示。 */
    private static final int MAX_SELECT = 5;

    // ---- 其余命中盒（点击检测用，与视觉尺寸解耦；可独立调大以保证好点） ----
    // R150：全库不变量「命中盒宽 ≤ 行内间距」——商店牌行内间距 1.15，原 0.6×2=1.2 违反
    // （相邻盒重叠 0.05，是唯一违规行；其余行均 ≤ 或相切）。对齐至 1.15 与间距相切
    // （与补充包行 1.6=1.6 同款处理），纯命中盒调整零视觉影响。
    private static final double BTN_HW = 0.55;
    private static final double BTN_HH = 0.24;
    private static final double SHOPCARD_HW = 0.575;
    private static final double SHOPCARD_HH = 0.4;
    private static final double PACK_HW = 0.8;
    private static final double PACK_HH = 0.35;
    private static final double JOKER_HW = 0.4;
    private static final double JOKER_HH = 0.28;
    private static final double CONS_HW = 0.36;
    private static final double CONS_HH = 0.28;
    private static final double VOUCHER_HW = 0.55;
    private static final double VOUCHER_HH = 0.24;

    /** 卡牌文字缩放（默认 1.0 约 0.5 格高，偏小；放大使牌面更清晰）。 */
    private static final float CARD_TEXT_SCALE = 1.5f;
    /**
     * 牌面每行补白到的「显示宽度」（半角=1，符号/中文=2）。
     * <p>TextDisplay 的可见宽度由文字内容决定，短文字（2~3 字符）天然很窄；
     * 把每行用空格补到此宽度 → 背景色块统一变宽，使牌面呈 高:宽=1:0.62。
     * <p><b>实机调参旋钮</b>：偏窄则增大（如 6）、偏宽则减小（如 4），无需改其他代码。
     */
    private static final int CARD_TEXT_COLS = 5;
    private static final float JOKER_TEXT_SCALE = 1.0f;
    private static final float FRAME_TEXT_SCALE = 1.0f;

    private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);

    // ---- 配色 ----
    private static final Color BG_STATUS = Color.fromARGB(190, 0, 0, 0);
    private static final Color BG_NORMAL = Color.fromARGB(235, 30, 30, 46);
    private static final Color BG_RED = Color.fromARGB(235, 56, 28, 34);
    private static final Color BG_SELECTED = Color.fromARGB(245, 56, 165, 90);
    private static final Color BG_FACEDOWN = Color.fromARGB(235, 50, 60, 92);
    private static final Color BG_DEBUFF = Color.fromARGB(220, 60, 60, 64);
    private static final Color BG_BUTTON_PLAY = Color.fromARGB(225, 46, 120, 70);
    private static final Color BG_BUTTON_DISC = Color.fromARGB(225, 120, 50, 60);
    private static final Color BG_BUTTON = Color.fromARGB(225, 55, 70, 120);
    private static final Color BG_SOLD = Color.fromARGB(180, 40, 40, 48);

    private static final TextColor C_RED = TextColor.color(240, 90, 90);
    private static final TextColor C_DARK = TextColor.color(236, 236, 246);
    private static final TextColor C_ENH = TextColor.color(255, 220, 120);
    private static final TextColor C_SEAL = TextColor.color(120, 200, 255);
    private static final TextColor C_EDITION = TextColor.color(220, 180, 255);

    private final GameSession session;
    /** 牌桌所在世界（生成/迁移时锁定；不随玩家实时位置取值，避免跨世界混放实体）。 */
    private org.bukkit.World world;
    private Vector origin;
    private Vector forward;
    private Vector right; // 玩家右手方向（板面横向）

    // ---- 持久实体 ----
    private final List<TextDisplay> all = new ArrayList<>(); // 全部实体（despawn 用）
    private TextDisplay statusBar;
    private TextDisplay evalBar;
    private TextDisplay playBtn;
    private TextDisplay discBtn;
    private TextDisplay rerollBtn;
    private TextDisplay nextBtn;
    private TextDisplay skipackBtn;
    private final List<TextDisplay> handSlots = new ArrayList<>();
    private final List<TextDisplay> jokerSlots = new ArrayList<>();
    private final List<TextDisplay> consSlots = new ArrayList<>();
    private final List<TextDisplay> shopSlots = new ArrayList<>();
    private final List<TextDisplay> packSlots = new ArrayList<>();
    private final List<TextDisplay> voucherSlots = new ArrayList<>();

    private final Set<Integer> selected = new HashSet<>();
    private Phase activePhase = null;
    /** 自愈重入保护：relocate→spawn→update 链路内不再二次触发整体重建。 */
    private boolean selfHealing = false;

    /** 可点击元素用不可见 Interaction 命中盒承载（MC 原生交互，右键确定触发 PlayerInteractEntityEvent）。 */
    private final List<Interaction> interactions = new ArrayList<>();
    private int interactionIdx = 0;

    public RoundBoard(GameSession session) {
        this.session = session;
        setBasis(session.player().getEyeLocation());
    }

    /**
     * 由眼部位置重算板面基（origin/forward/right/world）。
     * 水平化朝向：牌桌正立悬浮于眼前（不随玩家俯仰倾斜），且命中平面与实体位置平面一致。
     */
    private void setBasis(Location eye) {
        Vector dir = eye.getDirection();
        dir.setY(0);
        if (dir.lengthSquared() < 1.0E-6) dir.setX(1); // 玩家纯俯/仰视时给默认水平方向
        dir.normalize();
        this.world = eye.getWorld();
        this.origin = eye.toVector().add(dir.clone().multiply(FORWARD));
        this.forward = dir;
        Vector up = new Vector(0, 1, 0);
        this.right = dir.clone().getCrossProduct(up).normalize();
    }

    /**
     * 把整张牌桌迁移到玩家当前眼前（跨世界传送 / 重生 / 远距离传送后调用）。
     *
     * <p>牌桌实体锚定世界坐标；玩家换世界或远传后旧板留在原地（甚至不同世界），
     * 必须按新位置重建：重算基 → despawn 旧实体 → 原位 respawn。保留已选中牌。
     */
    public void relocate(Location eye) {
        Set<Integer> keep = new HashSet<>(selected);
        setBasis(eye);
        despawn();
        selected.addAll(keep);
        spawn(session.state());
    }

    /** 初次生成。 */
    public void spawn(RunState state) {
        statusBar = mkFrame("balatro_status", BG_STATUS);
        evalBar = mkFrame("balatro_eval", BG_STATUS);
        playBtn = mkFrame("balatro_act_play", BG_BUTTON_PLAY);
        discBtn = mkFrame("balatro_act_discard", BG_BUTTON_DISC);
        rerollBtn = mkFrame("balatro_reroll", BG_BUTTON);
        nextBtn = mkFrame("balatro_next", BG_BUTTON_PLAY);
        skipackBtn = mkFrame("balatro_skipack", BG_BUTTON_DISC);
        update(state);
        sendControls();
    }

    /** 向玩家发送操作说明（聊天框）。 */
    private void sendControls() {
        Player p = session.player();
        p.sendMessage(Component.text(Lang.t("board.help.title"), NamedTextColor.GOLD));
        p.sendMessage(Component.text(Lang.t("board.help.rmb"), NamedTextColor.WHITE)
                .append(Component.text(Lang.t("board.help.rmb.detail"), NamedTextColor.GRAY)));
        p.sendMessage(Component.text(Lang.t("board.help.shift"), NamedTextColor.AQUA)
                .append(Component.text(Lang.t("board.help.shift.detail"), NamedTextColor.GRAY)));
        p.sendMessage(Component.text(Lang.t("board.help.auto"), NamedTextColor.DARK_GRAY));
    }

    /**
     * 状态变更后刷新（原地改写，不 clear+respawn）。
     *
     * <p><b>异常安全</b>：reflow 可能因 Bukkit 世界操作（teleport/spawnEntity/remove）抛
     * {@link RuntimeException}（如区块卸载、世界异常）。本方法保证：
     * <ul>
     *   <li>{@link #hideExtraInteractions} 在 finally 中始终执行——即使 reflow 抛异常，
     *       未被本帧重新分配的 Interaction 也被零尺寸+去标签，避免陈旧 balatro_i_* 命中盒残留。</li>
     *   <li>阶段切换原子性：若 reflow 抛异常，{@code activePhase} 回退到切换前的值（prev），
     *       使下一帧 update 重新走 hideAll+reflow 路径完整重建，而非在半成品状态上叠加。</li>
     * </ul>
     */
    public void update(RunState state) {
        // 自愈：任一持久实体已失效即整体重建。牌桌宽约 8 格可横跨区块边界——周边区块
        // 卸载会杀死部分非持久实体（中心 statusBar 仍存活），留下「半死牌桌」：
        // 死位无牌面、命中盒失效且 update 原地改写为 no-op。仅查 statusBar 覆盖不了
        // 这种部分死亡，故扫描全部持久实体（约 50 个，点击节流 150ms 下代价可忽略）。
        if (!selfHealing && statusBar != null && hasInvalidEntity()) {
            selfHealing = true;
            try {
                relocate(session.player().getEyeLocation());
            } finally {
                selfHealing = false;
            }
            return; // relocate 内 spawn→update 已完成本次刷新
        }
        // 剪除已不在手牌中的选中 id（消耗品销毁/改写手牌、出牌弃牌等都会改变手牌构成），
        // 避免陈旧选中导致「选中 N」计数虚高、出牌时混入无效 id。
        if (!selected.isEmpty()) {
            selected.removeIf(id -> findInHand(state, id) == null);
        }
        interactionIdx = 0;
        Phase prev = activePhase;
        boolean phaseChanged = prev != state.phase;
        if (phaseChanged) {
            hideAll();
            activePhase = state.phase;
        }
        boolean reflowOk = false;
        try {
            switch (state.phase) {
                case SHOP -> reflowShop(state);
                case PACK -> reflowPack(state);
                case BLIND_SELECT -> reflowBlindSelect(state);
                default -> reflowRound(state);
            }
            reflowOk = true;
        } finally {
            // 无论 reflow 成功或抛异常，都必须清理本帧未使用的 Interaction，
            // 避免陈旧 balatro_i_* 命中盒残留导致误点击。
            hideExtraInteractions();
            // 若 reflow 失败且回退了阶段，保证下一帧重新完整重建
            if (!reflowOk && phaseChanged) {
                activePhase = prev;
            }
        }
        // 进入商店/补充包时，自动把所有简介发到聊天框便于判断（仅 reflow 成功时）
        if (reflowOk && phaseChanged) {
            if (state.phase == Phase.SHOP) sendShopInfo(state);
            else if (state.phase == Phase.PACK) sendPackInfo(state);
        }
    }

    /**
     * 在板面局部 (lx, ly) 中心放置一个 Interaction 命中盒（半宽 hw、半高 hh），承载点击。
     * 复用实体池：不足则创建。命中盒 foot 在 {@code at(lx, ly-hh)}，向上 hh*2，故中心在 ly。
     */
    private void placeInteraction(double lx, double ly, double hw, double hh, String action) {
        Interaction inter;
        if (interactionIdx < interactions.size()) {
            inter = interactions.get(interactionIdx);
        } else {
            inter = Holo.interaction(session.plugin(), session.player(), at(0, 0), action,
                    (float) (hw * 2), (float) (hh * 2), true);
            interactions.add(inter);
        }
        inter.setInteractionWidth((float) (hw * 2));
        inter.setInteractionHeight((float) (hh * 2));
        inter.teleport(at(lx, ly - hh)); // foot 在 ly-hh，命中盒中心在 ly
        inter.getScoreboardTags().removeIf(t -> t.startsWith("balatro_i_"));
        inter.addScoreboardTag("balatro_i_" + action);
        interactionIdx++;
    }

    /** 隐藏本轮未使用的 Interaction（零尺寸 + 去标签，使其不可命中）。 */
    private void hideExtraInteractions() {
        for (int i = interactionIdx; i < interactions.size(); i++) {
            Interaction inter = interactions.get(i);
            inter.setInteractionWidth(0f);
            inter.setInteractionHeight(0f);
            inter.getScoreboardTags().removeIf(t -> t.startsWith("balatro_i_"));
        }
    }

    private TextDisplay mkFrame(String tag, Color bg) {
        TextDisplay d = Holo.text(session.plugin(), session.player(), at(0, 0), tag, Component.empty(), bg, true);
        all.add(d);
        return d;
    }

    /** 取第 i 个槽位（不足则创建）。 */
    private TextDisplay slot(List<TextDisplay> slots, int i, Color bg) {
        while (slots.size() <= i) {
            TextDisplay d = Holo.text(session.plugin(), session.player(), at(0, 0),
                    "balatro_slot", Component.empty(), bg, true);
            slots.add(d);
            all.add(d);
        }
        return slots.get(i);
    }

    private void hide(TextDisplay d) {
        d.text(Component.empty());
        d.setBackgroundColor(TRANSPARENT);
        // 清掉遗留身份标签（balatro_card_<id>/balatro_act_* 等），仅保留 balatro_board/
        // balatro_slot 基础标识；命中由 Interaction 实体承载（R159 修正：原文「射线命中」
        // 为前代机制措辞残留——中英双语注释须双语关键词检索才能查全）。
        d.getScoreboardTags().removeIf(t -> !t.equals("balatro_board") && !t.equals("balatro_slot"));
    }

    private void ensureTag(TextDisplay d, String tag) {
        if (!d.getScoreboardTags().contains(tag)) d.addScoreboardTag(tag);
    }

    private static final Quaternionf Q_IDENTITY = new Quaternionf();

    /**
     * 设置实体整体缩放（放大文字与背景，使牌面更清晰、命中盒与之相称）。
     * P7 性能：唯一调用点是常量 {@code CARD_TEXT_SCALE}，Transformation 值不变——
     * 缓存为静态单例，免每次更新每张牌 new Transformation + 2×Vector3f。
     * setTransformation 读取数值写入实体元数据，不持有该实例，共享安全。
     */
    private static final Transformation CARD_SCALE_TRANSFORM = new Transformation(
            new Vector3f(0, 0, 0), Q_IDENTITY,
            new Vector3f(CARD_TEXT_SCALE, CARD_TEXT_SCALE, CARD_TEXT_SCALE), Q_IDENTITY);

    private static void setScale(TextDisplay d, float scale) {
        d.setTransformation(CARD_SCALE_TRANSFORM);
    }

    private void hideAll() {
        for (TextDisplay d : all) hide(d);
    }

    /** 是否存在已失效的持久实体（区块卸载杀死的非持久 Display/Interaction）。 */
    private boolean hasInvalidEntity() {
        for (TextDisplay d : all) {
            if (!d.isValid()) return true;
        }
        for (Interaction inter : interactions) {
            if (!inter.isValid()) return true;
        }
        return false;
    }

    // ================= 回合视图 =================

    private void reflowRound(RunState state) {
        // 状态栏
        String blind = state.blindType == null ? "-" : state.blindType.key;
        String boss = state.blindType == Data.BlindType.BOSS && !state.bossQueue.isEmpty()
                ? "（" + Data.Boss.byKey(state.bossQueue.get(0)).displayName() + "）" : "";
        var sb = Component.text()
                .append(Component.text(Lang.t("board.round.ante", state.ante, blindName(blind), boss), NamedTextColor.GOLD)).appendNewline()
                .append(Component.text(Lang.t("board.round.score", state.roundScore, state.blindTarget), NamedTextColor.WHITE)).appendNewline()
                .append(Component.text(Lang.t("board.round.counts", state.handsLeft, state.discardsLeft, state.money,
                        selected.size()), NamedTextColor.YELLOW));
        // Boss 回合：在状态栏追加 Boss 效果描述，让玩家随时看到当前 Boss 的干扰效果
        if (state.blindType == Data.BlindType.BOSS && !state.bossQueue.isEmpty()) {
            sb.appendNewline().append(Component.text("Boss：" + Data.Boss.byKey(state.bossQueue.get(0)).desc(), NamedTextColor.LIGHT_PURPLE));
        }
        sb.appendNewline().append(Component.text(Lang.t("board.round.hint"), NamedTextColor.DARK_GRAY));
        statusBar.text(sb.build());
        statusBar.teleport(at(0, STATUS_Y));

        // 实时牌型评估
        evalBar.text(evalText(state));
        evalBar.teleport(at(0, EVAL_Y));

        // 小丑行
        int jn = state.jokers.size();
        for (int i = 0; i < jn; i++) {
            JokerInstance ji = state.jokers.get(i);
            double x = (i - (jn - 1) / 2.0) * JOKER_SPACING;
            TextDisplay d = slot(jokerSlots, i, BG_NORMAL);
            TextColor jc = ji.debuff ? NamedTextColor.DARK_GRAY : TextColor.color(255, 220, 120);
            d.text(Component.text("🃏" + ji.def.displayName(), jc));
            d.setBackgroundColor(ji.debuff ? BG_DEBUFF : BG_NORMAL);
            d.teleport(at(x, JOKER_Y));
            placeInteraction(x, JOKER_Y, JOKER_HW, JOKER_HH, "joker:" + i); // 右键查看简介
        }
        for (int i = jn; i < jokerSlots.size(); i++) hide(jokerSlots.get(i));

        // 消耗品行（右键查看简介）
        int cn = state.consumables.size();
        for (int i = 0; i < cn; i++) {
            Consumable c = state.consumables.get(i);
            double x = (i - (cn - 1) / 2.0) * CONS_SPACING;
            TextDisplay d = slot(consSlots, i, BG_NORMAL);
            d.text(Component.text(consLabel(c), TextColor.color(180, 220, 255)));
            d.setBackgroundColor(BG_NORMAL);
            d.teleport(at(x, CONS_Y));
            placeInteraction(x, CONS_Y, CONS_HW, CONS_HH, "cons:" + i); // 右键查看简介
        }
        for (int i = cn; i < consSlots.size(); i++) hide(consSlots.get(i));

        // 手牌（已按点数整理）
        int n = state.hand.size();
        for (int i = 0; i < n; i++) {
            Card card = state.hand.get(i);
            boolean sel = selected.contains(card.id());
            double x = (i - (n - 1) / 2.0) * CARD_SPACING;
            double y = HAND_Y + (sel ? SELECT_LIFT : 0);
            TextDisplay d = slot(handSlots, i, BG_NORMAL);
            setScale(d, CARD_TEXT_SCALE);
            d.text(cardFace(card, sel));
            d.setBackgroundColor(cardBg(card, sel));
            setCardTag(d, card.id());
            // 显示向下平移半个牌高：TextDisplay 文字自实体位置向上生长，而命中盒以 y 为中心，
            // 两者差半张牌；下移 CARD_H/2 使可见牌面中心对齐命中盒中心。
            d.teleport(at(x, y - CARD_HH));
            placeInteraction(x, y, CARD_HW, CARD_HH, "card:" + card.id());
        }
        for (int i = n; i < handSlots.size(); i++) hide(handSlots.get(i));

        // 按钮
        int selN = selected.size();
        playBtn.text(Component.text(Lang.t("board.btn.play", selN > 0 ? " (" + selN + ")" : ""), NamedTextColor.GREEN));
        ensureTag(playBtn, "balatro_act_play");
        playBtn.teleport(at(-1.15, BUTTON_Y));
        placeInteraction(-1.15, BUTTON_Y, BTN_HW, BTN_HH, "play");
        discBtn.text(Component.text(Lang.t("board.btn.discard", selN > 0 ? " (" + selN + ")" : ""), NamedTextColor.RED));
        ensureTag(discBtn, "balatro_act_discard");
        discBtn.teleport(at(1.15, BUTTON_Y));
        placeInteraction(1.15, BUTTON_Y, BTN_HW, BTN_HH, "discard");

        // 回合阶段不用的按钮隐藏
        hide(rerollBtn);
        hide(nextBtn);
        for (TextDisplay d : voucherSlots) hide(d);
        hide(skipackBtn);
        clearShopPackSlots();
    }

    /** 当前选中牌的牌型 + 基础筹码×倍率（不计小丑），无选中则给提示。 */
    private Component evalText(RunState state) {
        if (selected.isEmpty()) {
            return Component.text(Lang.t("board.hint.select"), NamedTextColor.GRAY);
        }
        List<Card> cards = new ArrayList<>();
        for (Card c : state.hand) if (selected.contains(c.id())) cards.add(c);
        HandEval.Result res = Engine.evaluateHand(state, cards);
        if (res == null || res.type == null) {
            return Component.text(Lang.t("board.hand.invalid"), NamedTextColor.GRAY);
        }
        int lvl = state.handLevels.getOrDefault(res.type, 1);
        long chips = res.type.chipsAtLevel(lvl);
        long mult = res.type.multAtLevel(lvl);
        return Component.text()
                .append(Component.text(res.type.displayName() + "  ", NamedTextColor.AQUA))
                .append(Component.text(Lang.t("board.hand.chips", chips), NamedTextColor.WHITE))
                .append(Component.text("  ×  ", NamedTextColor.GRAY))
                .append(Component.text(Lang.t("board.hand.mult", mult), NamedTextColor.WHITE))
                .append(Component.text("  (Lv" + lvl + ")", NamedTextColor.DARK_GRAY))
                .build();
    }

    /**
     * 牌面文本：统一 3 行的竖向块（行1=版本/点数/蜡封 · 行2=花色 · 行3=失效或增强），
     * 每行用空格补到 {@link #CARD_TEXT_COLS} 显示宽度 → 背景色块统一变宽，呈 高:宽=1:0.62。
     */
    private Component cardFace(Card card, boolean selected) {
        int cols = CARD_TEXT_COLS;
        if (card.facedown()) {
            String fdText = Lang.t("card.hidden");
            return padCenter(Component.text(fdText, NamedTextColor.WHITE), displayWidth(fdText), cols).appendNewline()
                    .append(padCenter(Component.text(" ", NamedTextColor.WHITE), 1, cols)).appendNewline()
                    .append(padCenter(Component.text(" ", NamedTextColor.WHITE), 1, cols));
        }
        if (card.isStone()) {
            String st1 = Lang.t("board.card.stone1");
            String st2 = Lang.t("board.card.stone2");
            return padCenter(Component.text(st1, NamedTextColor.GRAY), displayWidth(st1), cols).appendNewline()
                    .append(padCenter(Component.text(st2, NamedTextColor.GRAY), displayWidth(st2), cols)).appendNewline()
                    .append(padCenter(Component.text(" ", NamedTextColor.GRAY), 1, cols));
        }
        Data.Suit s = Data.Suit.byIndex(card.suit());
        TextColor col = selected ? NamedTextColor.WHITE : (s.isRed() ? C_RED : C_DARK);
        String edStr = editionSym(card.edition());
        String rankStr = Data.rankName(card.rank());
        String sealStr = sealSym(card.seal());
        int l1w = displayWidth(edStr) + displayWidth(rankStr) + displayWidth(sealStr);
        Component line1 = padCenter(
                Component.text(edStr, C_EDITION).append(Component.text(rankStr, col)).append(Component.text(sealStr, C_SEAL)),
                l1w, cols);
        Component line2 = padCenter(Component.text(s.symbol, col), displayWidth(s.symbol), cols);
        String l3text;
        TextColor l3col;
        if (card.debuff()) {
            l3text = Lang.t("board.card.debuff_short");
            l3col = NamedTextColor.DARK_GRAY;
        } else if (card.enh() != null) {
            l3text = shortEnh(card.enh());
            l3col = C_ENH;
        } else {
            l3text = " ";
            l3col = col;
        }
        Component line3 = padCenter(Component.text(l3text, l3col), displayWidth(l3text), cols);
        return line1.appendNewline().append(line2).appendNewline().append(line3);
    }

    /** 字符串的显示宽度：ASCII（含空格/字母/数字）=1，其余（花色·版本·蜡封符号、中文）=2。 */
    private static int displayWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += (s.charAt(i) < 0x80) ? 1 : 2;
        }
        return w;
    }

    /**
     * 把一个组件（已知其内容显示宽度 {@code contentWidth}）用空格居中补到 {@code target} 显示宽度，
     * 使 TextDisplay 背景色块统一变宽。补白用空格（参与背景宽度），不拉伸文字字形。
     */
    private static Component padCenter(Component content, int contentWidth, int target) {
        int pad = Math.max(0, target - contentWidth);
        if (pad == 0) return content;
        int left = pad / 2;
        int right = pad - left;
        Component out = content;
        if (left > 0) out = Component.text(" ".repeat(left)).append(out);
        if (right > 0) out = out.append(Component.text(" ".repeat(right)));
        return out;
    }

    private Color cardBg(Card card, boolean selected) {
        if (selected) return BG_SELECTED;
        if (card.facedown()) return BG_FACEDOWN;
        if (card.debuff()) return BG_DEBUFF;
        if (card.isStone()) return BG_NORMAL;
        Data.Suit s = Data.Suit.byIndex(card.suit());
        return s.isRed() ? BG_RED : BG_NORMAL;
    }

    private void setCardTag(TextDisplay d, int cardId) {
        d.getScoreboardTags().removeIf(t -> t.startsWith("balatro_card_"));
        d.addScoreboardTag("balatro_card_" + cardId);
    }

    // ================= 盲注选择视图 =================

    private void reflowBlindSelect(RunState state) {
        Data.BlindType bt = Data.BlindType.byKey(state.nextBlind);
        long target = Engine.blindTarget(state, bt);
        String boss = bt == Data.BlindType.BOSS && !state.bossQueue.isEmpty()
                ? "（" + Data.Boss.byKey(state.bossQueue.get(0)).displayName() + "）" : "";
        var sb = Component.text()
                .append(Component.text(Lang.t("board.blind.ante", state.ante, blindName(bt.key), boss), NamedTextColor.GOLD)).appendNewline()
                .append(Component.text(Lang.t("board.blind.target", target), NamedTextColor.WHITE)).appendNewline()
                .append(Component.text(Lang.t("board.blind.money", state.money), NamedTextColor.YELLOW));
        // 盲注选择阶段遇 Boss：显示 Boss 效果描述，便于玩家决定开始还是跳过
        if (bt == Data.BlindType.BOSS && !state.bossQueue.isEmpty()) {
            sb.appendNewline().append(Component.text("Boss：" + Data.Boss.byKey(state.bossQueue.get(0)).desc(), NamedTextColor.LIGHT_PURPLE));
        }
        statusBar.text(sb.build());
        statusBar.teleport(at(0, STATUS_Y));
        hide(evalBar);

        playBtn.text(Component.text(Lang.t("board.btn.start_blind"), NamedTextColor.GREEN));
        ensureTag(playBtn, "balatro_act_play");
        playBtn.teleport(at(-1.15, BUTTON_Y));
        placeInteraction(-1.15, BUTTON_Y, BTN_HW, BTN_HH, "go");

        boolean canSkip = bt != Data.BlindType.BOSS;
        discBtn.text(Component.text(Lang.t(canSkip ? "board.btn.skip_tag" : "board.btn.boss_no_skip"),
                canSkip ? NamedTextColor.RED : NamedTextColor.DARK_GRAY));
        ensureTag(discBtn, "balatro_act_discard");
        discBtn.teleport(at(1.15, BUTTON_Y));
        if (canSkip) placeInteraction(1.15, BUTTON_Y, BTN_HW, BTN_HH, "skip");

        hide(rerollBtn);
        hide(nextBtn);
        for (TextDisplay d : voucherSlots) hide(d);
        hide(skipackBtn);
        clearRoundSlots();
        clearShopPackSlots();
    }

    // ================= 商店视图 =================

    private void reflowShop(RunState state) {
        var shop = state.shop;
        statusBar.text(Component.text()
                .append(Component.text(Lang.t("board.shop.title", state.money), NamedTextColor.GOLD)).appendNewline()
                .append(Component.text(Lang.t("board.shop.hint"), NamedTextColor.GRAY))
                .build());
        statusBar.teleport(at(0, STATUS_Y));
        hide(evalBar);

        // 商店阶段：隐藏手牌（不出牌），但显示持有的小丑/消耗品（可点击出售，对齐原版）。
        for (TextDisplay d : handSlots) hide(d);
        if (shop == null) {
            clearRoundSlots();
            hide(rerollBtn);
            hide(nextBtn);
            for (TextDisplay d : voucherSlots) hide(d);
            return;
        }

        // —— 持有小丑行（y=2.1，借用 evalBar 上方位置）：右键 = 出售确认 ——
        // 商店 6 行布局 Y 坐标经 R83 调整，确保相邻行命中盒间距 ≥0.12 格（消除 0.02 格薄间距）：
        // 小丑(2.1)→商品(1.2)→补充包(0.1)→消耗品(-0.65)→优惠券(-1.35)→按钮(-2.05)
        int jn = state.jokers.size();
        for (int i = 0; i < jn; i++) {
            JokerInstance ji = state.jokers.get(i);
            double x = (i - (jn - 1) / 2.0) * JOKER_SPACING;
            TextDisplay d = slot(jokerSlots, i, BG_NORMAL);
            TextColor jc = ji.debuff ? NamedTextColor.DARK_GRAY : TextColor.color(255, 220, 120);
            int sv = state.sellValue(ji);
            // 永恒小丑不标售价（不可售）；其余标注售价（绿色，用 Component 拼接非 § 码）
            var jokerText = Component.text("🃏" + ji.def.displayName(), jc);
            if (!ji.eternal) {
                jokerText = jokerText.append(Component.text(" $" + sv, NamedTextColor.GREEN));
            }
            d.text(jokerText);
            d.setBackgroundColor(ji.debuff ? BG_DEBUFF : BG_NORMAL);
            d.teleport(at(x, 2.1));
            placeInteraction(x, 2.1, JOKER_HW, JOKER_HH, "joker:" + i); // 右键出售 / Shift+右键查看简介
        }
        for (int i = jn; i < jokerSlots.size(); i++) hide(jokerSlots.get(i));

        int n = shop.cards.size();
        for (int i = 0; i < n; i++) {
            var c = shop.cards.get(i);
            double x = (i - (n - 1) / 2.0) * 1.15;
            TextDisplay d = slot(shopSlots, i, BG_NORMAL);
            TextColor col = c.sold ? NamedTextColor.DARK_GRAY
                    : (c.kind.equals("joker") ? TextColor.color(255, 220, 120) : NamedTextColor.WHITE);
            d.text(Component.text((c.sold ? Lang.t("board.tag.sold") : "") + shopCardLabel(c) + " $" + c.price, col));
            d.setBackgroundColor(c.sold ? BG_SOLD : BG_NORMAL);
            setIndexedTag(d, "balatro_shopcard_", i);
            d.teleport(at(x, 1.2));
            if (!c.sold) placeInteraction(x, 1.2, SHOPCARD_HW, SHOPCARD_HH, "shop:" + i);
        }
        for (int i = n; i < shopSlots.size(); i++) hide(shopSlots.get(i));

        int pn = shop.packs.size();
        for (int i = 0; i < pn; i++) {
            var p = shop.packs.get(i);
            double x = (i - (pn - 1) / 2.0) * 1.6;
            TextDisplay d = slot(packSlots, i, BG_NORMAL);
            d.text(Component.text((p.sold ? Lang.t("board.tag.sold") : "") + "📦" + p.name + " $" + p.price, NamedTextColor.AQUA));
            d.setBackgroundColor(p.sold ? BG_SOLD : BG_NORMAL);
            setIndexedTag(d, "balatro_shoppack_", i);
            d.teleport(at(x, 0.1));
            if (!p.sold) placeInteraction(x, 0.1, PACK_HW, PACK_HH, "shoppack:" + i);
        }
        for (int i = pn; i < packSlots.size(); i++) hide(packSlots.get(i));

        // —— 持有消耗品行（y=-0.65）：右键 = 出售确认 ——
        int cn = state.consumables.size();
        for (int i = 0; i < cn; i++) {
            Consumable c = state.consumables.get(i);
            double x = (i - (cn - 1) / 2.0) * CONS_SPACING;
            int sv = RunState.sellValue(c);
            TextDisplay d = slot(consSlots, i, BG_NORMAL);
            d.text(Component.text(consLabel(c), TextColor.color(180, 220, 255))
                    .append(Component.text(" $" + sv, NamedTextColor.GREEN)));
            d.setBackgroundColor(BG_NORMAL);
            d.teleport(at(x, -0.65));
            placeInteraction(x, -0.65, CONS_HW, CONS_HH, "cons:" + i); // 右键出售 / Shift+右键查看简介
        }
        for (int i = cn; i < consSlots.size(); i++) hide(consSlots.get(i));

        // 优惠券（可能多张：voucher 标签追加）。y=-1.35 与上方消耗品(y=-0.65)保持 0.18 格间距。
        // 专属 VOUCHER_HW/HH（此前误用 PACK_HW=0.8 致多券水平重叠——R82 修复）。
        int vn = shop.vouchers.size();
        for (int i = 0; i < vn; i++) {
            var vch = shop.vouchers.get(i);
            double x = vn == 1 ? 0 : (i - (vn - 1) / 2.0) * (VOUCHER_HW * 2 + 0.1);
            TextDisplay d = slot(voucherSlots, i, BG_NORMAL);
            d.text(Component.text("🎫" + vch.name + " $" + vch.price
                    + (vch.sold ? Lang.t("board.tag.sold_short") : ""), NamedTextColor.LIGHT_PURPLE));
            d.setBackgroundColor(vch.sold ? BG_SOLD : BG_NORMAL);
            setIndexedTag(d, "balatro_shopvoucher_", i);
            d.teleport(at(x, -1.35));
            if (!vch.sold) placeInteraction(x, -1.35, VOUCHER_HW, VOUCHER_HH, "voucher:" + i);
        }
        for (int i = vn; i < voucherSlots.size(); i++) hide(voucherSlots.get(i));
        rerollBtn.text(Component.text(Lang.t("board.btn.reroll"), NamedTextColor.YELLOW));
        ensureTag(rerollBtn, "balatro_reroll");
        rerollBtn.teleport(at(-1.5, -2.05));
        placeInteraction(-1.5, -2.05, BTN_HW, BTN_HH, "reroll");
        nextBtn.text(Component.text(Lang.t("board.btn.next_round"), NamedTextColor.GREEN));
        ensureTag(nextBtn, "balatro_next");
        nextBtn.teleport(at(1.5, -2.05));
        placeInteraction(1.5, -2.05, BTN_HW, BTN_HH, "next");
        hide(playBtn);
        hide(discBtn);
        hide(skipackBtn);
    }

    // ================= 补充包视图 =================

    private void reflowPack(RunState state) {
        var pack = state.pack;
        statusBar.text(Component.text()
                .append(Component.text(Lang.t("board.pack.title", pack == null ? "?" : pack.def.displayName(),
                        pack == null ? 0 : pack.left), NamedTextColor.GOLD)).appendNewline()
                .append(Component.text(Lang.t("board.pack.hint"), NamedTextColor.GRAY))
                .build());
        statusBar.teleport(at(0, STATUS_Y));
        hide(evalBar);
        clearRoundSlots();

        if (pack == null) {
            hide(skipackBtn);
            return;
        }
        int n = pack.cards.size();
        for (int i = 0; i < n; i++) {
            var c = pack.cards.get(i);
            double x = (i - (n - 1) / 2.0) * 1.15;
            TextDisplay d = slot(packSlots, i, BG_NORMAL);
            d.text(Component.text((c.taken ? Lang.t("board.tag.taken") : "") + packCardLabel(c),
                    c.taken ? NamedTextColor.DARK_GRAY : NamedTextColor.WHITE));
            d.setBackgroundColor(c.taken ? BG_SOLD : BG_NORMAL);
            setIndexedTag(d, "balatro_pick_", i);
            d.teleport(at(x, 0.9));
            if (!c.taken) placeInteraction(x, 0.9, SHOPCARD_HW, SHOPCARD_HH, "pick:" + i);
        }
        for (int i = n; i < packSlots.size(); i++) hide(packSlots.get(i));

        skipackBtn.text(Component.text(Lang.t("board.btn.skip"), NamedTextColor.RED));
        ensureTag(skipackBtn, "balatro_skipack");
        skipackBtn.teleport(at(0, -0.8));
        placeInteraction(0, -0.8, BTN_HW, BTN_HH, "skipack");
        hide(playBtn);
        hide(discBtn);
        hide(rerollBtn);
        hide(nextBtn);
        for (TextDisplay d : voucherSlots) hide(d);
    }

    // ================= 聊天框简介 =================

    /** 重新发送商店简介（重掷后商品/价格全变，此前自动发送的简介已过期）。 */
    public void refreshShopInfo() {
        if (session.state().phase == Phase.SHOP) sendShopInfo(session.state());
    }

    /** 进入商店：把所有商品简介发到玩家聊天框（便于判断购买）。 */
    private void sendShopInfo(RunState state) {
        var shop = state.shop;
        if (shop == null) return;
        Player p = session.player();
        p.sendMessage(Component.text(Lang.t("board.shop.header", state.money), NamedTextColor.GOLD));
        for (int i = 0; i < shop.cards.size(); i++) {
            var c = shop.cards.get(i);
            p.sendMessage(infoLine((i + 1) + ". ", kindLabel(c.kind), c.name, c.price, c.sold, c.desc));
        }
        for (var pk : shop.packs) {
            p.sendMessage(infoLine("📦 ", Lang.t("kind.pack"), pk.name, pk.price, pk.sold, pk.desc));
        }
        for (int vi = 0; vi < shop.vouchers.size(); vi++) {
            var vch = shop.vouchers.get(vi);
            p.sendMessage(infoLine("🎫[" + (vi + 1) + "] ", Lang.t("kind.voucher"), vch.name, vch.price, vch.sold, vch.desc));
        }
        p.sendMessage(Component.text(Lang.t("board.shop.info_hint"), NamedTextColor.GRAY));
    }

    /** 进入补充包：把所有卡简介发到玩家聊天框。 */
    private void sendPackInfo(RunState state) {
        var pack = state.pack;
        if (pack == null) return;
        Player p = session.player();
        p.sendMessage(Component.text(Lang.t("board.pack.header", pack.def.displayName(), pack.left), NamedTextColor.GOLD));
        for (int i = 0; i < pack.cards.size(); i++) {
            var c = pack.cards.get(i);
            p.sendMessage(infoLine((i + 1) + ". ", kindLabel(c.kind), c.name, 0, c.taken, c.desc));
        }
        p.sendMessage(Component.text(Lang.t("board.pack.info_hint"), NamedTextColor.GRAY));
    }

    private static Component infoLine(String prefix, String tag, String name, long price, boolean gone, String desc) {
        return Component.text(prefix + "[" + tag + "] " + name + (price > 0 ? "  $" + price : "") + (gone ? Lang.t("board.tag.gone") : ""),
                        NamedTextColor.YELLOW)
                .appendNewline().append(Component.text(desc == null ? "" : desc, NamedTextColor.GRAY));
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "joker", "tarot", "planet", "spectral", "playing" -> Lang.t("kind." + kind);
            default -> kind;
        };
    }

    /** 右键单个元素时返回其简介（小丑/消耗品/商品/补充包卡/优惠券/手牌；按钮返回 null）。 */
    public Component infoFor(RunState state, String action) {
        try {
            if (action.startsWith("card:")) {
                int id = Integer.parseInt(action.substring("card:".length()));
                for (Card c : state.hand) {
                    if (c.id() == id) return playingCardInfo(c);
                }
            } else if (action.startsWith("joker:")) {
                int i = Integer.parseInt(action.substring("joker:".length()));
                if (i >= 0 && i < state.jokers.size()) {
                    JokerInstance j = state.jokers.get(i);
                    return Component.text("🃏 " + Lang.t("board.joker.line", j.def.displayName(),
                            j.debuff ? Lang.t("board.joker.debuffed") : ""), NamedTextColor.GOLD)
                            .appendNewline().append(Component.text(j.def.desc(), NamedTextColor.GRAY));
                }
            } else if (action.startsWith("cons:")) {
                int i = Integer.parseInt(action.substring("cons:".length()));
                if (i >= 0 && i < state.consumables.size()) {
                    Consumable c = state.consumables.get(i);
                    Component info = Component.text("[" + kindLabel(c.kind) + "] " + c.name(), NamedTextColor.AQUA)
                            .appendNewline().append(Component.text(c.desc(), NamedTextColor.GRAY));
                    String req = targetReqText(Consumables.effectiveUseInfo(state, c.key));
                    if (req != null) {
                        info = info.appendNewline().append(Component.text(
                                Lang.t("req.hint", req), NamedTextColor.YELLOW));
                    }
                    return info;
                }
            } else if (action.startsWith("shop:") && state.shop != null) {
                int i = Integer.parseInt(action.substring("shop:".length()));
                if (i >= 0 && i < state.shop.cards.size()) {
                    var c = state.shop.cards.get(i);
                    return infoLine((i + 1) + ". ", kindLabel(c.kind), c.name, c.price, c.sold, c.desc);
                }
            } else if (action.startsWith("shoppack:") && state.shop != null) {
                int i = Integer.parseInt(action.substring("shoppack:".length()));
                if (i >= 0 && i < state.shop.packs.size()) {
                    var pk = state.shop.packs.get(i);
                    return infoLine("📦 ", Lang.t("kind.pack"), pk.name, pk.price, pk.sold, pk.desc);
                }
            } else if (action.startsWith("voucher:") && state.shop != null) {
                int i = Integer.parseInt(action.substring("voucher:".length()));
                if (i >= 0 && i < state.shop.vouchers.size()) {
                    var v = state.shop.vouchers.get(i);
                    return infoLine("🎫[" + (i + 1) + "] ", Lang.t("kind.voucher"), v.name, v.price, v.sold, v.desc);
                }
            } else if (action.startsWith("pick:") && state.pack != null) {
                int i = Integer.parseInt(action.substring("pick:".length()));
                if (i >= 0 && i < state.pack.cards.size()) {
                    var c = state.pack.cards.get(i);
                    return infoLine((i + 1) + ". ", kindLabel(c.kind), c.name, 0, c.taken, c.desc);
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /** 手牌扑克牌简介：花色点数 + 增强/版本/蜡封说明。 */
    private static Component playingCardInfo(Card c) {
        // 面朝下牌身份保密（R238 修复）：mark Boss/xray 的隐藏机制不得被简介通道击穿——
        // 板面已渲染「？」，简介与 status 同样只给「面朝下」，增强/版本/蜡封一并隐藏。
        if (c.facedown()) {
            return Component.text(Lang.t("board.card.facedown"), NamedTextColor.GRAY);
        }
        Component head = c.isStone()
                ? Component.text(Lang.t("card.stone_card"), NamedTextColor.GRAY)
                : Component.text(Data.Suit.byIndex(c.suit()).displayName() + " " + Data.rankName(c.rank()), NamedTextColor.WHITE);
        Component body = Component.empty();
        if (c.enh() != null) {
            body = body.appendNewline().append(Component.text(c.enh().displayName() + "：" + c.enh().desc(), NamedTextColor.YELLOW));
        }
        if (c.edition() != null) {
            body = body.appendNewline().append(Component.text(c.edition().displayName() + "：" + c.edition().desc(), TextColor.color(220, 180, 255)));
        }
        if (c.seal() != null) {
            body = body.appendNewline().append(Component.text(c.seal().displayName() + "：" + c.seal().desc(), TextColor.color(120, 200, 255)));
        }
        if (c.debuff()) {
            body = body.appendNewline().append(Component.text(Lang.t("board.card.debuffed"), NamedTextColor.DARK_RED));
        }
        return head.append(body);
    }

    // ================= 角标 / 标签 =================

    private static String shortEnh(Data.Enhancement e) {
        return Lang.t("board.enh." + e.key);
    }

    private static String editionSym(Data.Edition e) {
        if (e == null) return "";
        return switch (e) {
            case FOIL -> "✦";
            case HOLO -> "✧";
            case POLY -> "❉";
            case NEGATIVE -> "➖";
        };
    }

    private static String sealSym(Data.Seal s) {
        if (s == null) return "";
        return switch (s) {
            case GOLD -> "ᚷ";
            case RED -> "ᚱ";
            case BLUE -> "ᛒ";
            case PURPLE -> "ᛈ";
        };
    }

    private static String shopCardLabel(cn.quotidietium.balatro.engine.shop.Shop.CardItem c) {
        return switch (c.kind) {
            case "joker", "playing" -> Lang.t("kind." + c.kind) + " " + c.name;
            default -> c.kind + " " + c.name;
        };
    }

    private static String packCardLabel(cn.quotidietium.balatro.engine.shop.Packs.PackCard c) {
        return switch (c.kind) {
            case "joker", "playing" -> Lang.t("kind." + c.kind) + " " + c.name;
            default -> c.kind + " " + c.name;
        };
    }

    private static String consLabel(Consumable c) {
        return switch (c.kind) {
            case "tarot" -> Lang.t("kind.tarot") + " " + Data.Tarot.byKey(c.key).displayName();
            case "planet" -> Lang.t("kind.planet") + " " + Data.Planet.byKey(c.key).displayName();
            case "spectral" -> Lang.t("kind.spectral") + " " + Data.Spectral.byKey(c.key).displayName();
            default -> c.kind + " " + c.key;
        };
    }

    private static String blindName(String key) {
        return switch (key) {
            case "small", "big", "boss" -> Lang.t("blind." + key);
            default -> key;
        };
    }

    private void setIndexedTag(TextDisplay d, String prefix, int idx) {
        d.getScoreboardTags().removeIf(t -> t.startsWith(prefix));
        d.addScoreboardTag(prefix + idx);
    }

    private void clearRoundSlots() {
        for (TextDisplay d : handSlots) hide(d);
        for (TextDisplay d : jokerSlots) hide(d);
        for (TextDisplay d : consSlots) hide(d);
    }

    private void clearShopPackSlots() {
        for (TextDisplay d : shopSlots) hide(d);
        for (TextDisplay d : packSlots) hide(d);
    }

    /** 板面上某 (横向, 垂直) 偏移处的世界坐标（世界取生成/迁移时锁定的世界）。 */
    private Location at(double rightOffset, double upOffset) {
        Vector v = origin.clone()
                .add(right.clone().multiply(rightOffset))
                .add(new Vector(0, upOffset, 0));
        return new Location(world, v.getX(), v.getY(), v.getZ());
    }

    // ---- 交互（由 BoardListener 经 tag 派发） ----

    /**
     * 切换某张手牌的选中态。
     *
     * <p>选牌上限 {@value #MAX_SELECT} 张（对齐引擎出牌/弃牌 1~5 张）。已达上限再选新牌时：
     * <b>不作出反应</b>（不选中、不重渲染、不播音效），仅在聊天框提醒上限。返回是否真的改变了选中态。
     */
    public boolean toggleSelect(int cardId) {
        if (selected.remove(cardId)) {
            update(session.state());
            return true;
        }
        // 拒绝不在当前手牌中的 id（陈旧实体点击/伪造交互均不可选中）
        if (findInHand(session.state(), cardId) == null) return false;
        if (selected.size() >= MAX_SELECT) {
            session.player().sendMessage(Component.text(
                    Lang.t("err.select_limit", MAX_SELECT), NamedTextColor.RED));
            return false;
        }
        selected.add(cardId);
        update(session.state());
        return true;
    }

    private static Card findInHand(RunState state, int cardId) {
        for (Card c : state.hand) {
            if (c.id() == cardId) return c;
        }
        return null;
    }

    public void playSelected() {
        if (selected.isEmpty()) return;
        cn.quotidietium.balatro.engine.Engine.PlayResult r = session.play(selectedIdsInHandOrder());
        selected.clear();
        if (!r.ok && r.err != null) {
            session.player().sendMessage(Component.text(r.err, NamedTextColor.RED));
        }
    }

    public void discardSelected() {
        if (selected.isEmpty()) return;
        cn.quotidietium.balatro.engine.Engine.PlayResult r = session.discard(selectedIdsInHandOrder());
        selected.clear();
        if (!r.ok && r.err != null) {
            session.player().sendMessage(Component.text(r.err, NamedTextColor.RED));
        }
    }

    /**
     * 当前选中牌 id，按手牌从左到右序（R225：确定性——弃牌的紫蜡封流序、消耗品
     * 目标次序（死神「左变右」）不再随 HashSet 哈希布局漂移）。
     */
    private List<Integer> selectedIdsInHandOrder() {
        List<Integer> ids = new ArrayList<>(selected.size());
        for (Card card : session.state().hand) if (selected.contains(card.id())) ids.add(card.id());
        return ids;
    }

    public void clearSelection() {
        selected.clear();
    }

    // ---- 使用/出售确认提示（聊天框可点击按钮）----

    /**
     * 右键小丑：在聊天框弹出「确认出售」提示（名称 · 说明 · 售价 · 永恒判定），
     * 附可点击的「[确认出售]」(→ /balatro sellj) 与「[取消]」(→ /balatro cancel) 按钮。
     * 永恒小丑直接提示不可出售，不给按钮。
     */
    public void sendSellConfirm(Player player, int i) {
        RunState st = session.state();
        if (i < 0 || i >= st.jokers.size()) return;
        JokerInstance j = st.jokers.get(i);
        if (j.eternal) {
            player.sendMessage(Component.text(Lang.t("confirm.eternal", j.def.displayName()), NamedTextColor.RED));
            return;
        }
        int val = st.sellValue(j);
        player.sendMessage(Component.text(Lang.t("confirm.sell.title"), NamedTextColor.GOLD));
        player.sendMessage(Component.text("🃏 " + Lang.t("confirm.joker_line", j.def.displayName(),
                j.debuff ? Lang.t("confirm.joker_debuffed") : ""), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(j.def.desc(), NamedTextColor.GRAY));
        player.sendMessage(Component.text(Lang.t("confirm.price", val), NamedTextColor.GREEN));
        // 按钮携带期望 joker key：确认后到点击前牌序可能被改写（幻灵/命令出售等），
        // 命令层校验不一致则取消执行，防止序号错位卖错小丑
        player.sendMessage(confirmButtons("/balatro sellj " + (i + 1) + " " + j.def.key(), Lang.t("act.sell"), "$" + val));
    }

    /**
     * 右键消耗品：在聊天框弹出「确认使用」提示（名称 · 说明 · 目标需求 · 手动命令），
     * 附「[确认使用]」(→ /balatro use) 与「[取消]」按钮。
     *
     * <p>R225：目标类消耗品的确认命令携带确认时刻选中牌的卡 id 快照（@id 令牌，
     * 手牌从左到右序）。此前按钮不带任何目标——右键选中牌后确认仍报
     * 「请选择 N 张手牌」，21 种目标类消耗品（光环造镭射/死神/力量…）在全息侧
     * 事实上不可用。选中数不满足需求时不带令牌，由引擎给出精确数量错误（提示行
     * 已预告需求）；命令层校验快照 id 仍在手牌（TOCTOU 防错位）。
     */
    public void sendUseConfirm(Player player, int i) {
        RunState st = session.state();
        if (i < 0 || i >= st.consumables.size()) return;
        Consumable c = st.consumables.get(i);
        int sellVal = RunState.sellValue(c);
        Consumables.UseInfo info = Consumables.effectiveUseInfo(st, c.key);
        String req = targetReqText(info);
        String cmd = "/balatro use " + (i + 1) + " " + c.kind + ":" + c.key;
        player.sendMessage(Component.text(Lang.t("confirm.use.title"), NamedTextColor.GOLD));
        player.sendMessage(Component.text("[" + kindLabel(c.kind) + "] " + c.name(), NamedTextColor.AQUA));
        player.sendMessage(Component.text(c.desc(), NamedTextColor.GRAY));
        if (req != null && st.phase == Phase.ROUND) {
            List<Integer> sel = selectedIdsInHandOrder();
            boolean fit = sel.size() >= info.minTargets() && sel.size() <= info.maxTargets();
            player.sendMessage(Component.text(
                    Lang.t(fit ? "req.selected_fit" : "req.selected_nofit", req, sel.size()),
                    fit ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            if (fit) {
                StringBuilder sb = new StringBuilder(cmd).append(" @");
                for (int k = 0; k < sel.size(); k++) {
                    if (k > 0) sb.append(',');
                    sb.append(sel.get(k));
                }
                cmd = sb.toString();
            }
        } else if (req != null) {
            player.sendMessage(Component.text(Lang.t("req.round_only", req), NamedTextColor.YELLOW));
        }
        player.sendMessage(Component.text(
                Lang.t("confirm.manual_cmd", i + 1, sellVal), NamedTextColor.DARK_GRAY));
        // 按钮携带期望 kind:key + 目标快照 @id（命令层双重校验：列表/手牌变化均取消防错位）
        player.sendMessage(confirmButtons(cmd, Lang.t("act.use"), null));
    }

    /**
     * 右键消耗品（商店阶段）：在聊天框弹出「确认出售」提示（名称 · 说明 · 售价），
     * 附可点击的「[确认出售]」(→ /balatro sellc) 与「[取消]」(→ /balatro cancel) 按钮。
     *
     * <p>与 {@link #sendSellConfirm}（小丑版）结构对称，消除原先消耗品仅在「确认使用」
     * 对话框中以灰色文字提及出售、且无可点击按钮的不对称。
     *
     * <p>R225：引擎允许在商店使用「无目标且不限回合」的消耗品（星球/非目标塔罗/部分
     * 幻灵，Consumables.useInfo 元数据），此前商店右键只有出售按钮——此类牌现在同时
     * 提供「[确认使用]」。目标类/回合限幻灵不提供（引擎会拒，提示行已预告）。
     */
    public void sendSellConsumableConfirm(Player player, int i) {
        RunState st = session.state();
        if (i < 0 || i >= st.consumables.size()) return;
        Consumable c = st.consumables.get(i);
        int val = RunState.sellValue(c);
        String sellCmd = "/balatro sellc " + (i + 1) + " " + c.kind + ":" + c.key;
        player.sendMessage(Component.text(Lang.t("confirm.sell.title"), NamedTextColor.GOLD));
        player.sendMessage(Component.text("[" + kindLabel(c.kind) + "] " + c.name(), NamedTextColor.AQUA));
        player.sendMessage(Component.text(c.desc(), NamedTextColor.GRAY));
        player.sendMessage(Component.text(Lang.t("confirm.price", val), NamedTextColor.GREEN));
        Consumables.UseInfo info = Consumables.effectiveUseInfo(st, c.key);
        Component buttons;
        if (!info.needsTargets() && !info.roundOnly()) {
            Component use = Component.text(Lang.t("confirm.use_now"), NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/balatro use " + (i + 1) + " " + c.kind + ":" + c.key))
                    .hoverEvent(HoverEvent.showText(Component.text(Lang.t("confirm.use_now_hover"), NamedTextColor.GRAY)));
            // 按钮携带期望 kind:key：确认后到点击前消耗品列表可能已变化（使用/出售收缩列表），
            // 命令层校验不一致则取消执行，防止序号错位卖错/用错消耗品
            buttons = Component.text(" ").append(use).append(Component.text("   "))
                    .append(confirmButtons(sellCmd, Lang.t("act.sell"), "$" + val));
        } else {
            buttons = confirmButtons(sellCmd, Lang.t("act.sell"), "$" + val);
        }
        player.sendMessage(buttons);
    }

    /** 目标需求提示（如「需选中 1 张手牌」/「需选中 1–2 张手牌」）；无需目标返回 null。 */
    private static String targetReqText(Consumables.UseInfo info) {
        if (!info.needsTargets()) return null;
        return info.minTargets() == info.maxTargets()
                ? Lang.t("req.exact", info.maxTargets())
                : Lang.t("req.range", info.minTargets(), info.maxTargets());
    }

    /** 生成「[确认X] [取消]」两个可点击按钮。 */
    private static Component confirmButtons(String confirmCmd, String action, String gain) {
        String confirmHover = gain != null
                ? Lang.t("btn.confirm_hover_gain", action, gain)
                : Lang.t("btn.confirm_hover", action);
        Component confirm = Component.text(Lang.t("btn.confirm", action), NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand(confirmCmd))
                .hoverEvent(HoverEvent.showText(Component.text(confirmHover, NamedTextColor.GRAY)));
        Component cancel = Component.text(Lang.t("btn.cancel"), NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/balatro cancel"))
                .hoverEvent(HoverEvent.showText(Component.text(Lang.t("btn.cancel_hover"), NamedTextColor.GRAY)));
        return Component.text(" ").append(confirm).append(Component.text("   ")).append(cancel);
    }

    /**
     * 该实体是否属于本牌桌（交互归属校验）。
     *
     * <p>牌桌实体虽为本人私有可见，但客户端可被篡改、且实体 id 可被枚举/探测：
     * 玩家可尝试对任意实体 id 发送交互包（服务端仅校验距离）。交互派发前必须确认
     * 被点击的 {@link Interaction} 确实由本玩家的本牌桌创建，否则拒绝。
     */
    public boolean ownsInteraction(org.bukkit.entity.Entity entity) {
        return entity != null && interactions.contains(entity);
    }

    /** 销毁全部实体。 */
    public void despawn() {
        for (TextDisplay d : all) {
            if (d.isValid()) d.remove();
        }
        for (Interaction inter : interactions) {
            if (inter.isValid()) inter.remove();
        }
        all.clear();
        interactions.clear();
        handSlots.clear();
        jokerSlots.clear();
        consSlots.clear();
        shopSlots.clear();
        packSlots.clear();
        voucherSlots.clear();
        selected.clear();
        activePhase = null;
        interactionIdx = 0;
    }
}
