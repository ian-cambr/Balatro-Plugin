package cn.quotidietium.balatro.engine;

/**
 * 消耗品（塔罗/星球/幻灵），对应 balatro state.consumables[i]。
 * kind: tarot / planet / spectral。
 */
public final class Consumable {
    public final String kind;
    public final String key;
    public Data.Edition edition; // 负片等
    public int sellBonus;

    public Consumable(String kind, String key) {
        this.kind = kind;
        this.key = key;
    }

    public String name() {
        return switch (kind) {
            case "tarot" -> Data.Tarot.byKey(key).displayName();
            case "planet" -> Data.Planet.byKey(key).displayName();
            case "spectral" -> Data.Spectral.byKey(key).displayName();
            default -> key;
        };
    }

    public String desc() {
        return switch (kind) {
            case "tarot" -> Data.Tarot.byKey(key).desc();
            case "planet" -> Data.Planet.byKey(key).desc();
            case "spectral" -> Data.Spectral.byKey(key).desc();
            default -> "";
        };
    }
}
