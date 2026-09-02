package cn.quotidietium.balatro.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * plugin.yml 声明与代码实现的一致性锁定（R107）。
 *
 * <p>R86 曾人工核对「usage 全 26 命令 / 别名 / api-version / ${version}」，但属一次性核对——
 * 未来新增子命令忘改 usage、别名被误删、版本宏漂移都不会被发现（玩家侧表现为 Tab 能补全
 * 但 usage 提示缺失，属「表述与实现不符」）。本测试把该核对固化为构建时锁定：
 * <ul>
 *   <li>usage（词边界）覆盖 SUBS 全部条目（cancel 除外——按设计为全息回执不列入）；</li>
 *   <li>usage 中出现的每个命令名 ∈ SUBS（无凭空多出的声明）；</li>
 *   <li>aliases / main / api-version / ${version} 宏 / 权限声明精确匹配预期。</li>
 * </ul>
 * 失败即提示「新增/改名子命令须同步 plugin.yml usage（或反之）」。
 */
class PluginYmlConsistencyTest {

    /** 按设计不列入 usage 的 SUBS 条目（全息确认框回执，见 BalatroCommand Javadoc）。 */
    private static final Set<String> USAGE_EXEMPT = Set.of("cancel");

    @Test
    void usageCoversExactlySubcommands() throws Exception {
        String yml = readPluginYml();
        List<String> subs = subsFromSource();

        String usage = extractValue(yml, "usage");
        assertTrue(usage.startsWith("/balatro"), "usage 应以 /balatro 开头");

        // 提取 usage 中声明的命令名：按 '|' 分段，剥掉可选 "/balatro " 前缀，取首个词
        Set<String> declared = new HashSet<>();
        for (String seg : usage.split("\\|")) {
            String s = seg.trim();
            if (s.startsWith("/balatro")) s = s.substring("/balatro".length()).trim();
            if (s.isEmpty()) continue;
            String first = s.split("\\s+")[0];
            // 跳过形如 [页码] 的参数段（前段参数被 | 切开的场景）
            if (first.startsWith("[")) continue;
            declared.add(first);
        }

        // ① usage ⊇ SUBS − 豁免集（词边界精确：防 "hand" 误匹配等）
        Set<String> missingInUsage = new HashSet<>();
        for (String sub : subs) {
            if (USAGE_EXEMPT.contains(sub)) continue;
            if (!declared.contains(sub)) missingInUsage.add(sub);
        }
        assertEquals(Set.of(), missingInUsage, "SUBS 中的子命令未列入 usage: " + missingInUsage);

        // ② usage ⊆ SUBS（usage 不得声明不存在的命令）
        Set<String> phantom = new HashSet<>(declared);
        phantom.removeAll(subs);
        assertEquals(Set.of(), phantom, "usage 声明了 SUBS 之外的命令（已删未清?）: " + phantom);
    }

    @Test
    void metadataExact() throws Exception {
        String yml = readPluginYml();
        List<String> subs = subsFromSource();
        assertTrue(subs.contains("cancel"), "SUBS 应含 cancel（豁免集的前提）");

        assertEquals("Balatro", extractValue(yml, "name"));
        assertEquals("cn.quotidietium.balatro.BalatroPlugin", extractValue(yml, "main"));
        assertEquals("'26.2'", extractValue(yml, "api-version"), "api-version 须锁定 26.2");
        assertEquals("'${version}'", extractValue(yml, "version"),
                "version 须为构建期宏（processResources expand），防两处版本漂移");
        assertEquals("[blt, joker]", extractValue(yml, "aliases"), "别名集须与文档一致（README/BalatroCommand Javadoc）");
        assertEquals("[Vault]", extractValue(yml, "softdepend"), "Vault 软依赖声明");

        // 权限：balatro.play 实施（default true）+ balatro.admin 预留
        assertTrue(yml.contains("balatro.play:"), "须声明 balatro.play");
        assertTrue(Pattern.compile("(?s)balatro\\.play:.*?default:\\s*true").matcher(yml).find(),
                "balatro.play 默认须为 true（全体玩家可用）");
        assertTrue(yml.contains("balatro.admin:"), "须声明 balatro.admin（预留）");
    }

    /** 行级提取顶层或命令段内的单行标量值（匹配最后一个同名键——usage/name 等在文件中唯一）。 */
    private static String extractValue(String yml, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + ":\\s*(.+)$", Pattern.MULTILINE).matcher(yml);
        String v = null;
        while (m.find()) v = m.group(1).trim();
        if (v == null) throw new AssertionError("plugin.yml 缺少键: " + key);
        return v;
    }

    /**
     * 从 BalatroCommand.java 源码提取 SUBS 字面量（测试类路径无 paper-api，无法加载该类；
     * 与 RngStreamInventoryTest 同款源码扫描模式）。
     */
    private static List<String> subsFromSource() throws IOException {
        Path src = Path.of("src", "main", "java", "cn", "quotidietium", "balatro",
                "command", "BalatroCommand.java");
        assertTrue(Files.isRegularFile(src), "BalatroCommand.java 应存在（测试从项目根运行）");
        Matcher m = Pattern.compile("SUBS\\s*=\\s*Arrays\\.asList\\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(Files.readString(src, StandardCharsets.UTF_8));
        assertTrue(m.find(), "应能定位 SUBS = Arrays.asList(...) 声明");
        List<String> subs = new ArrayList<>();
        Matcher strs = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (strs.find()) subs.add(strs.group(1));
        assertTrue(!subs.isEmpty(), "SUBS 应非空");
        return subs;
    }

    private static String readPluginYml() throws IOException {
        Path p = Path.of("src", "main", "resources", "plugin.yml");
        assertTrue(Files.isRegularFile(p), "plugin.yml 应存在（测试从项目根运行）");
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
