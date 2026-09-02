package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.i18n.Lang;

/**
 * 一张游戏牌，对应 balatro {@code Engine.makeCard} 产出的对象。
 *
 * <p>字段全部可变（rank/suit 会被塔罗/幻灵改写；enh/edition/seal 会被附加；
 * chipBonus 为徒步者等永久加成；debuff 由 Boss/挑战置位；facedown 由 Boss 置位）。
 * 相等性以唯一 {@link #id} 为准——即便两张牌 rank+suit 相同也能区分。
 *
 * <p>rank：2..14（11=J 12=Q 13=K 14=A），石头牌为 0；
 * suit：0..3（黑桃/红桃/梅花/方块），石头牌为 -1。
 *
 * <p><b>P13 性能：位打包表示</b>。原 10 字段布局（3 枚举引用 + 2 int + long + 3 boolean）
 * 每张 ~56B，一副 52 张 ≈ 2.9KB——为构局路径剩余分配的大头。现将可变状态压入单个
 * {@code int bits}（rank 6b | suit+1 3b | enh ord+1 4b | edition ord+1 3b | seal ord+1 3b |
 * debuff/facedown/broken 各 1b，共 22b），对象缩至 32B（−~43%），整副牌的遍历
 * （洗牌/整理/全清 debuff）缓存密度同步提升。全部访问仍经既有 getter/setter，
 * 公开签名与语义逐字不变；枚举解码走静态 values() 缓存数组（类加载一次）。
 *
 * <p><b>域约定</b>（与游戏规则域一致，越界值按位截断存储）：rank ∈ [0,63]、
 * suit ∈ [-1,6]、枚举为 {@link Data} 三枚举的常量或 null。游戏内所有写入点
 * （构局/塔罗/幻灵/小丑/Boss）均在此域内（rank 0..14、suit -1..3）。
 */
public final class Card {
    // ---- 位布局（LSB 起）----
    private static final int RANK_MASK = 0x3F;                       // [0,6)  rank
    private static final int SUIT_SHIFT = 6, SUIT_MASK = 0x7;        // [6,9)  suit+1（石头 -1 → 0）
    private static final int ENH_SHIFT = 9, ENH_MASK = 0xF;          // [9,13) Enhancement.ordinal()+1（0=null）
    private static final int ED_SHIFT = 13, ED_MASK = 0x7;           // [13,16) Edition.ordinal()+1
    private static final int SEAL_SHIFT = 16, SEAL_MASK = 0x7;       // [16,19) Seal.ordinal()+1
    private static final int F_DEBUFF = 1 << 19;
    private static final int F_FACEDOWN = 1 << 20;
    private static final int F_BROKEN = 1 << 21; // 玻璃牌破碎（计分后销毁，从牌组移除）

    /** 枚举解码表（类加载一次；ordinal 即数组下标）。 */
    private static final Data.Enhancement[] ENH_VALUES = Data.Enhancement.values();
    private static final Data.Edition[] EDITION_VALUES = Data.Edition.values();
    private static final Data.Seal[] SEAL_VALUES = Data.Seal.values();

    private final int id;
    private int bits;
    private long chipBonus;

    public Card(int id, int rank, int suit) {
        this.id = id;
        this.bits = (rank & RANK_MASK) | ((suit + 1) & SUIT_MASK) << SUIT_SHIFT;
    }

    public int id() {
        return id;
    }

    public int rank() {
        return bits & RANK_MASK;
    }

    public void setRank(int rank) {
        bits = (bits & ~RANK_MASK) | (rank & RANK_MASK);
    }

    public int suit() {
        return ((bits >> SUIT_SHIFT) & SUIT_MASK) - 1;
    }

    public void setSuit(int suit) {
        bits = (bits & ~(SUIT_MASK << SUIT_SHIFT)) | (((suit + 1) & SUIT_MASK) << SUIT_SHIFT);
    }

    public Data.Enhancement enh() {
        int c = (bits >> ENH_SHIFT) & ENH_MASK;
        return c == 0 ? null : ENH_VALUES[c - 1];
    }

    public void setEnh(Data.Enhancement enh) {
        int c = enh == null ? 0 : enh.ordinal() + 1;
        bits = (bits & ~(ENH_MASK << ENH_SHIFT)) | (c << ENH_SHIFT);
    }

    public Data.Edition edition() {
        int c = (bits >> ED_SHIFT) & ED_MASK;
        return c == 0 ? null : EDITION_VALUES[c - 1];
    }

    public void setEdition(Data.Edition edition) {
        int c = edition == null ? 0 : edition.ordinal() + 1;
        bits = (bits & ~(ED_MASK << ED_SHIFT)) | (c << ED_SHIFT);
    }

    public Data.Seal seal() {
        int c = (bits >> SEAL_SHIFT) & SEAL_MASK;
        return c == 0 ? null : SEAL_VALUES[c - 1];
    }

    public void setSeal(Data.Seal seal) {
        int c = seal == null ? 0 : seal.ordinal() + 1;
        bits = (bits & ~(SEAL_MASK << SEAL_SHIFT)) | (c << SEAL_SHIFT);
    }

    // ---- P13 性能：热路径快速谓词 ----
    // `enh() == X` 在位打包后是「shift+mask+数组取+指针比」；热循环（牌型判定过滤/计分
    // 分支/花色适配）里以本谓词替代为「shift+mask+int 比」——无解码数组访问。
    // 语义与 `enh() == e` 恒等（编码即 ordinal+1，单射）。

    /** 等价于 {@code enh() == e}（无解码数组访问的热路径谓词）。 */
    public boolean isEnh(Data.Enhancement e) {
        return ((bits >> ENH_SHIFT) & ENH_MASK) == e.ordinal() + 1;
    }

    /** 等价于 {@code edition() == e}（同 {@link #isEnh}）。 */
    public boolean isEdition(Data.Edition e) {
        return ((bits >> ED_SHIFT) & ED_MASK) == e.ordinal() + 1;
    }

    /** 等价于 {@code seal() == s}（同 {@link #isEnh}）。 */
    public boolean isSeal(Data.Seal s) {
        return ((bits >> SEAL_SHIFT) & SEAL_MASK) == s.ordinal() + 1;
    }

    public long chipBonus() {
        return chipBonus;
    }

    public void addChipBonus(long bonus) {
        this.chipBonus += bonus;
    }

    public boolean debuff() {
        return (bits & F_DEBUFF) != 0;
    }

    public void setDebuff(boolean debuff) {
        if (debuff) bits |= F_DEBUFF;
        else bits &= ~F_DEBUFF;
    }

    public boolean facedown() {
        return (bits & F_FACEDOWN) != 0;
    }

    public void setFacedown(boolean facedown) {
        if (facedown) bits |= F_FACEDOWN;
        else bits &= ~F_FACEDOWN;
    }

    public boolean isBroken() {
        return (bits & F_BROKEN) != 0;
    }

    public void setBroken(boolean broken) {
        if (broken) bits |= F_BROKEN;
        else bits &= ~F_BROKEN;
    }

    /**
     * 石头牌：enh 为 STONE 即视为石头（对齐原版各处按 {@code enh==="stone"} 判定），
     * 兼容牌组构建/高塔转化的 0/-1 壳。marble 生成的石头牌为黑桃 2 壳 + STONE 增强，
     * 仅凭 rank/suit 会漏判（排序/渲染/持有效果都依赖本方法）。
     */
    public boolean isStone() {
        // P13 性能：单次位段比较（原经 enh() 解码数组）
        return ((bits >> ENH_SHIFT) & ENH_MASK) == Data.Enhancement.STONE.ordinal() + 1
                || (bits & RANK_MASK) == 0 || suit() < 0;
    }

    /**
     * 设置牌的增强，正确处理石头牌转换（对齐真版）。
     *
     * <p>真版语义（[Reddit](https://www.reddit.com/r/balatro/comments/1bn9dpi/) +
     * [Stone cards Wiki](https://balatrowiki.org/w/Stone_cards)）：增强替换原增强；
     * 石头牌被转为非 stone 增强后不再是石头，须恢复合法 rank/suit。REF engine.js 此处未恢复
     * （REF bug：石头牌转其他增强/setEnh(null) 后 rank/suit 仍 0/-1，isStone 按 rank==0 仍判石头
     * 但 enh 已非 stone，HandEval/scoreOneCard 按 enh!=stone 当普通牌，rank=0/suit=-1 参与判定致混乱）。
     *
     * <p>消耗品（magician/empress/hierophant/lovers/chariot/justice/devil/tower）、
     * 小丑（vampire 移除增强、midas 变黄金）共用本方法保证石头牌转换的状态一致性。
     *
     * @param newEnh 新增强（含 STONE 与 null）
     */
    public void applyEnhancement(Data.Enhancement newEnh) {
        setEnh(newEnh);
        if (newEnh == Data.Enhancement.STONE) {
            setRank(0);
            setSuit(-1);
        } else if (this.rank() == 0 || this.suit() < 0) {
            // 从石头转为普通增强/无增强：恢复合法底层（无底层记录时用黑桃2，对齐 marble 石头壳默认）
            if (this.rank() < 2) setRank(2);
            if (this.suit() < 0) setSuit(0);
        }
    }

    /** 人头牌 J/Q/K。 */
    public boolean isFace() {
        int r = rank();
        return r >= 11 && r <= 13;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Card c)) return false;
        return this.id == c.id;
    }

    /** P4 性能：直接返回 id——原 Objects.hash(id) 每次调用分配 varargs 数组。 */
    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        if (isStone()) return Lang.t("card.stone");
        return Data.Suit.byIndex(suit()).symbol + Data.rankName(rank());
    }
}
