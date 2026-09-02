package cn.quotidietium.balatro.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.quotidietium.balatro.engine.joker.JokerRegistry;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * R141：标签/挑战 desc 中「」引用名的一致性锁定。
 *
 * <p>缺陷族（#71/#72）：挑战 desc 曾使用游戏内**不存在**的物品名（乘公交/女皇/戏法/
 * 怨灵/全是 6！/抓取·橙舌·夜贼——实名分别为 搭便车/皇后/魔术技巧/幽灵/全是 6/
 * 补给手·顺手牵羊·窃贼），四个开包标签 desc 的「大号」档位措辞与实际开启的「巨型」
 * （Mega）档不符。此类缺陷逐条人工核对难以穷尽，改为系统性守门：**desc 中的每个
 * 「…」token 必须命中命名全集**（小丑/优惠券/补充包/塔罗/星球/幻灵/Boss/牌组的
 * 显示名）——任何未来的 desc 改动引用了不存在（或写错）的名字即失败。
 */
class DescNameReferenceTest {


    /** 黄金数据来自原版中文 data.js，故这些断言固定跑在 zh_CN 下。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    private static final Pattern QUOTED = Pattern.compile("「([^」]+)」");

    /** 全部合法显示名（标签/挑战 desc 可能引用的一切实体命名）。 */
    private static Set<String> nameUniverse() {
        Set<String> names = new HashSet<>();
        for (var j : JokerRegistry.allJokersOrdered()) {
            names.add(JokerRegistry.nameOf(j.key()));
        }
        for (Data.Voucher v : Data.VOUCHERS) names.add(v.displayName());
        for (Data.Pack p : Data.PACKS) names.add(p.displayName());
        for (Data.Tarot t : Data.TAROTS) names.add(t.displayName());
        for (Data.Planet p : Data.PLANETS) names.add(p.displayName());
        for (Data.Spectral sp : Data.SPECTRALS) names.add(sp.displayName());
        for (Data.Boss b : Data.Boss.values()) names.add(b.displayName());
        for (var d : Data.DECKS) names.add(d.name());
        return names;
    }

    @Test
    void allQuotedNamesInTagAndChallengeDescsExist() {
        Set<String> universe = nameUniverse();
        StringBuilder bad = new StringBuilder();
        for (Data.Tag t : Data.TAGS) collectBad(t.key(), t.desc(), universe, bad);
        for (Data.Challenge c : Data.CHALLENGES) collectBad(c.key(), c.desc(), universe, bad);
        assertTrue(bad.length() == 0,
                "desc 引用了命名全集之外的名字（应使用实际显示名）：" + bad);
    }

    private static void collectBad(String key, String desc, Set<String> universe, StringBuilder bad) {
        Matcher m = QUOTED.matcher(desc);
        while (m.find()) {
            String token = m.group(1);
            if (!universe.contains(token)) {
                if (bad.length() > 0) bad.append("；");
                bad.append("[").append(key).append("]「").append(token).append("」");
            }
        }
    }
}
