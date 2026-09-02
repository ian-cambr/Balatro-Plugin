package cn.quotidietium.balatro.command;

import cn.quotidietium.balatro.i18n.Lang;
import java.util.List;

/**
 * {@code /balatro version} 的版本与版权信息（纯逻辑，零 Bukkit 依赖，可单测）。
 *
 * <p>版本号由调用方从 plugin.yml（构建时注入 Gradle 版本）读取后传入，
 * 本类不硬编码版本号，杜绝发版时两处漂移。
 */
final class VersionInfo {

    /** 版本与版权信息行；{@code version} 为当前插件版本。 */
    static List<String> lines(String version) {
        return List.of(
                Lang.t("about.title"),
                Lang.t("about.version", version),
                Lang.t("about.author"),
                Lang.t("about.collaborator"),
                Lang.t("about.license"),
                Lang.t("about.source"),
                Lang.t("about.commercial"));
    }

    private VersionInfo() {
    }
}
