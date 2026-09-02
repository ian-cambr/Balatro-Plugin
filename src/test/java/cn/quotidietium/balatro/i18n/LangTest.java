package cn.quotidietium.balatro.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 语言系统测试：内置语言包的键必须逐一对齐，占位符必须一致，
 * 且缺失键要能沿 zh_CN → en_US → key 本身的顺序兜底。
 */
class LangTest {

    private static final String[] LOCALES = {"en_US", "zh_CN"};

    @AfterEach
    void restore() {
        Lang.reset();
    }

    private static Properties bundled(String locale) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Lang.class.getResourceAsStream("/lang/" + locale + ".properties")) {
            assertNotNull(in, "missing bundled language file: " + locale);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyBundledLocaleHasTheSameKeys() throws IOException {
        Set<String> en = new TreeSet<>(bundled("en_US").stringPropertyNames());
        for (String loc : LOCALES) {
            Set<String> other = new TreeSet<>(bundled(loc).stringPropertyNames());
            Set<String> missing = new TreeSet<>(en);
            missing.removeAll(other);
            Set<String> extra = new TreeSet<>(other);
            extra.removeAll(en);
            assertTrue(missing.isEmpty(), loc + " is missing keys: " + missing);
            assertTrue(extra.isEmpty(), loc + " has keys en_US lacks: " + extra);
        }
    }

    @Test
    void noValueIsBlank() throws IOException {
        for (String loc : LOCALES) {
            Properties p = bundled(loc);
            for (String k : p.stringPropertyNames()) {
                assertFalse(p.getProperty(k).isBlank(), loc + " has a blank value for " + k);
            }
        }
    }

    @Test
    void placeholdersMatchAcrossLocales() throws IOException {
        Properties en = bundled("en_US");
        for (String loc : LOCALES) {
            Properties p = bundled(loc);
            for (String k : en.stringPropertyNames()) {
                assertEquals(placeholders(en.getProperty(k)), placeholders(p.getProperty(k)),
                        "placeholder set differs for " + k + " in " + loc);
            }
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 2 < value.length(); i++) {
            if (value.charAt(i) == '{' && Character.isDigit(value.charAt(i + 1)) && value.charAt(i + 2) == '}') {
                out.add(value.substring(i, i + 3));
            }
        }
        return out;
    }

    @Test
    void defaultLocaleIsEnglish() {
        Lang.reset();
        assertEquals("en_US", Lang.locale());
        assertEquals("Joker", Lang.t("joker.joker.name"));
    }

    @Test
    void loadSwitchesLocale() {
        Lang.load("zh_CN", null);
        assertEquals("zh_CN", Lang.locale());
        assertEquals("小丑", Lang.t("joker.joker.name"));
    }

    @Test
    void unknownLocaleFallsBackToDefault() {
        Lang.load("xx_YY", null);
        assertEquals("en_US", Lang.locale());
        Lang.load(null, null);
        assertEquals("en_US", Lang.locale());
    }

    @Test
    void missingKeyIsReturnedVerbatimSoGapsAreVisible() {
        assertEquals("no.such.key.anywhere", Lang.t("no.such.key.anywhere"));
        assertFalse(Lang.has("no.such.key.anywhere"));
    }

    @Test
    void argumentsFillPositionalPlaceholders() {
        Lang.load("en_US", null);
        assertTrue(Lang.t("about.version", "1.2.3").contains("1.2.3"));
        assertFalse(Lang.t("about.version", "1.2.3").contains("{0}"));
    }
}
