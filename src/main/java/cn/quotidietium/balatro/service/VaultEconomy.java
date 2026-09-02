package cn.quotidietium.balatro.service;

import cn.quotidietium.balatro.api.service.EconomyService;
import cn.quotidietium.balatro.i18n.Lang;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * 基于 Vault 的经济适配（softdepend）。
 *
 * <p>为避免引入编译期依赖（VaultAPI 在部分网络无法拉取），通过反射访问 Vault 的
 * {@code net.milkbowl.vault.economy.Economy}。运行期无 Vault 时 {@link #available()} 为 false。
 */
public final class VaultEconomy implements EconomyService {

    private final Object econ;
    private final Method getBalance;
    private final Method has;
    private final Method deposit;
    private final Method withdraw;
    /** 可选日志：经济调用失败在 FINE 级别留痕（此前静默吞掉，集成故障无从排查）。 */
    private final java.util.logging.Logger logger;

    public VaultEconomy() {
        this(null);
    }

    public VaultEconomy(java.util.logging.Logger logger) {
        this.logger = logger;
        Object e = null;
        Method gb = null, h = null, d = null, w = null;
        try {
            Class<?> econCls = Class.forName("net.milkbowl.vault.economy.Economy");
            Object rsp = Bukkit.getServer().getServicesManager().getRegistration(econCls);
            if (rsp != null) {
                Object provider = rsp.getClass().getMethod("getProvider").invoke(rsp);
                if (provider != null) {
                    // 先解析全部方法；任一缺失（非标准 Economy 实现）则整体视为不可用，
                    // 避免 econ 非空但方法为 null 时 available() 误报 true、调用处静默空操作。
                    gb = econCls.getMethod("getBalance", OfflinePlayer.class);
                    h = econCls.getMethod("has", OfflinePlayer.class, double.class);
                    d = econCls.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                    w = econCls.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
                    e = provider; // 四个方法全部解析成功才认定可用
                }
            }
        } catch (Throwable ignored) {
            // Vault 不存在或结构异常 → 不可用
        }
        this.econ = e;
        this.getBalance = gb;
        this.has = h;
        this.deposit = d;
        this.withdraw = w;
    }

    public boolean available() {
        return econ != null;
    }

    private OfflinePlayer offline(UUID p) {
        return Bukkit.getOfflinePlayer(p);
    }

    @Override
    public long balance(UUID player) {
        if (econ == null) return 0;
        try {
            return (long) ((Number) getBalance.invoke(econ, offline(player))).doubleValue();
        } catch (Throwable e) {
            logFine("getBalance", e);
            return 0;
        }
    }

    @Override
    public boolean has(UUID player, long amount) {
        if (econ == null) return amount <= 0;
        try {
            return (boolean) has.invoke(econ, offline(player), (double) amount);
        } catch (Throwable e) {
            logFine("has", e);
            return false;
        }
    }

    @Override
    public void deposit(UUID player, long amount) {
        if (econ == null) return;
        try {
            deposit.invoke(econ, offline(player), (double) amount);
        } catch (Throwable e) {
            logFine("depositPlayer", e);
        }
    }

    @Override
    public void withdraw(UUID player, long amount) {
        if (econ == null) return;
        try {
            withdraw.invoke(econ, offline(player), (double) amount);
        } catch (Throwable e) {
            logFine("withdrawPlayer", e);
        }
    }

    /** 经济调用失败留痕（FINE：默认配置不刷屏，排查时可开级别）。 */
    private void logFine(String op, Throwable e) {
        if (logger != null) {
            logger.log(java.util.logging.Level.FINE, Lang.t("log.vault_failed", op, e));
        }
    }
}
