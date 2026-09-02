package cn.quotidietium.balatro.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code /balatro version} 输出版权信息测试（轮次特性：version 命令）。
 *
 * <p>锁定输出必须包含的全部要素：版本号（注入式，防发版漂移）、作者 ZTF3、协作者 Dalict、
 * Apache-2.0 协议、开源地址、商业版本归属说明。
 */
class VersionInfoTest {


    /** 本类断言的是原版中文文案，故固定跑在 zh_CN 下（同时兼作 zh_CN 覆盖度检查）。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    @Test
    void linesContainAllRequiredAttribution() {
        String all = String.join("\n", VersionInfo.lines("0.4.21"));
        assertTrue(all.contains("0.4.21"), "应包含版本号");
        assertTrue(all.contains("ZTF3"), "应包含插件作者 ZTF3");
        assertTrue(all.contains("Dalict"), "应包含协作者 Dalict");
        assertTrue(all.contains("Apache-2.0"), "应包含开源协议 Apache-2.0");
        assertTrue(all.contains("https://github.com/hershate/Balatro-Plugin"), "应包含开源地址");
        assertTrue(all.contains("商业版本"), "应包含商业版本归属说明");
    }

    @Test
    void versionIsInjectedNotHardcoded() {
        String all = String.join("\n", VersionInfo.lines("9.9.9-probe"));
        assertTrue(all.contains("9.9.9-probe"), "版本号必须由调用方注入（杜绝发版时两处漂移）");
    }

    @Test
    void linesAreNonEmpty() {
        List<String> lines = VersionInfo.lines("x");
        assertTrue(lines.size() >= 5, "版权信息应有多行，实际 " + lines.size());
        for (String line : lines) {
            assertTrue(!line.isBlank(), "不应有空白行");
        }
    }
}
