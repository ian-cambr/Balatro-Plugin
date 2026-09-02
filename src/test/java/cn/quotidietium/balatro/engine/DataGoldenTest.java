package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Data 黄金用例：读取 {@code golden/data.txt}（由原版 data.js 产出），
 * 断言花色/点数/牌型/盲注目标分等静态数据与原版逐项一致。
 */
class DataGoldenTest {


    /** 黄金数据来自原版中文 data.js，故这些断言固定跑在 zh_CN 下。 */
    @BeforeAll
    static void useChinese() {
        Lang.load("zh_CN", null);
    }

    @AfterAll
    static void restoreDefaultLocale() {
        Lang.reset();
    }

    @Test
    void rankNameMatches() throws IOException {
        for (String[] p : lines("RANKNAME")) {
            int r = Integer.parseInt(p[1]);
            assertEquals(p[2], Data.rankName(r), "rankName " + r);
        }
    }

    @Test
    void rankChipsMatches() throws IOException {
        for (String[] p : lines("RANKCHIPS")) {
            int r = Integer.parseInt(p[1]);
            assertEquals(Integer.parseInt(p[2]), Data.rankChips(r), "rankChips " + r);
        }
    }

    @Test
    void blindBaseMatches() throws IOException {
        for (String[] p : lines("BLINDBASE")) {
            int ante = Integer.parseInt(p[1]);
            assertEquals(Long.parseLong(p[2]), Data.blindBase(ante), "blindBase " + ante);
        }
    }

    @Test
    void handsMatch() throws IOException {
        for (String[] p : lines("HAND")) {
            // HAND <key> <name> <chips> <mult> <lchips> <lmult> <order>
            Data.HandType h = Data.HandType.byKey(p[1]);
            assertEquals(p[2], h.displayName(), "name " + p[1]);
            assertEquals(Integer.parseInt(p[3]), h.chips, "chips " + p[1]);
            assertEquals(Integer.parseInt(p[4]), h.mult, "mult " + p[1]);
            assertEquals(Integer.parseInt(p[5]), h.lchips, "lchips " + p[1]);
            assertEquals(Integer.parseInt(p[6]), h.lmult, "lmult " + p[1]);
            assertEquals(Integer.parseInt(p[7]), h.order, "order " + p[1]);
        }
    }

    @Test
    void blindsMatch() throws IOException {
        for (String[] p : lines("BLIND")) {
            Data.BlindType b = Data.BlindType.byKey(p[1]);
            assertEquals(Double.parseDouble(p[2]), b.mult, 0.0, "mult " + p[1]);
            assertEquals(Integer.parseInt(p[3]), b.reward, "reward " + p[1]);
        }
    }

    @Test
    void suitsMatch() throws IOException {
        for (String[] p : lines("SUIT")) {
            Data.Suit s = Data.Suit.byKey(p[1]);
            assertEquals(p[2], s.displayName(), "name " + p[1]);
            assertEquals(p[3], s.symbol, "symbol " + p[1]);
            assertEquals(p[4], s.color, "color " + p[1]);
        }
    }

    @Test
    void enhancementsMatch() throws IOException {
        for (String[] p : pipeLines("ENH")) {
            Data.Enhancement e = Data.Enhancement.byKey(p[1]);
            assertEquals(p[2], e.displayName(), "name " + p[1]);
            assertEquals(p[3], e.desc(), "desc " + p[1]);
        }
    }

    @Test
    void editionsMatch() throws IOException {
        for (String[] p : pipeLines("EDITION")) {
            Data.Edition e = Data.Edition.byKey(p[1]);
            assertEquals(p[2], e.displayName(), "name " + p[1]);
            assertEquals(p[3], e.desc(), "desc " + p[1]);
            assertEquals(Double.parseDouble(p[4]), e.chance, 0.0, "chance " + p[1]);
        }
    }

    @Test
    void sealsMatch() throws IOException {
        for (String[] p : pipeLines("SEAL")) {
            Data.Seal s = Data.Seal.byKey(p[1]);
            assertEquals(p[2], s.displayName(), "name " + p[1]);
            assertEquals(p[3], s.desc(), "desc " + p[1]);
        }
    }

    @Test
    void tarotMatch() throws IOException {
        for (String[] p : pipeLines("TAROT")) {
            Data.Tarot t = Data.Tarot.byKey(p[1]);
            assertEquals(p[2], t.displayName(), "name " + p[1]);
            assertEquals(p[3], t.desc(), "desc " + p[1]);
        }
    }

    @Test
    void planetsMatch() throws IOException {
        for (String[] p : pipeLines("PLANET")) {
            Data.Planet pl = Data.Planet.byKey(p[1]);
            assertEquals(p[2], pl.displayName(), "name " + p[1]);
            assertEquals(p[3], pl.hand.key, "hand " + p[1]);
            assertEquals(p[4], pl.desc(), "desc " + p[1]);
        }
    }

    @Test
    void spectralMatch() throws IOException {
        for (String[] p : pipeLines("SPECTRAL")) {
            Data.Spectral sp = Data.Spectral.byKey(p[1]);
            assertEquals(p[2], sp.displayName(), "name " + p[1]);
            assertEquals(p[3], sp.desc(), "desc " + p[1]);
        }
    }

    @Test
    void packsMatch() throws IOException {
        for (String[] p : pipeLines("PACK")) {
            Data.Pack pk = Data.packByKey(p[1]);
            assertEquals(p[2], pk.type.key, "type " + p[1]);
            assertEquals(p[3], pk.displayName(), "name " + p[1]);
            assertEquals(Integer.parseInt(p[4]), pk.size, "size " + p[1]);
            assertEquals(Integer.parseInt(p[5]), pk.choose, "choose " + p[1]);
            assertEquals(Integer.parseInt(p[6]), pk.cost, "cost " + p[1]);
        }
    }

    @Test
    void vouchersMatch() throws IOException {
        for (String[] p : pipeLines("VOUCHER")) {
            Data.Voucher v = Data.voucherByKey(p[1]);
            assertEquals(p[2], v.displayName(), "name " + p[1]);
            assertEquals(p[3], v.desc(), "desc " + p[1]);
            assertEquals(Integer.parseInt(p[4]), v.base, "base " + p[1]);
            String dep = v.pair != null ? v.pair : (v.requires != null ? v.requires : "-");
            assertEquals(p[5], dep, "pair/requires " + p[1]);
        }
    }

    @Test
    void rarityMatch() throws IOException {
        for (String[] p : pipeLines("RARITY")) {
            Data.Rarity r = Data.Rarity.byKey(p[1]);
            assertEquals(p[2], r.displayName(), "name " + p[1]);
            assertEquals(Integer.parseInt(p[3]), r.weight, "weight " + p[1]);
        }
    }

    @Test
    void decksMatch() throws IOException {
        for (String[] p : pipeLines("DECK")) {
            Data.Deck d = Data.deckByKey(p[1]);
            assertEquals(p[2], d.name(), "name " + p[1]);
            assertEquals(p[3], d.desc(), "desc " + p[1]);
        }
    }

    @Test
    void stakesMatch() throws IOException {
        for (String[] p : pipeLines("STAKE")) {
            assertEquals(p[2], stakeName(p[1]), "name " + p[1]);
        }
    }

    @Test
    void tagsMatch() throws IOException {
        for (String[] p : pipeLines("TAG")) {
            assertEquals(p[2], tagName(p[1]), "name " + p[1]);
            assertEquals(p[3], tagDesc(p[1]), "desc " + p[1]);
        }
    }

    @Test
    void challengesMatch() throws IOException {
        for (String[] p : pipeLines("CHALLENGE")) {
            assertEquals(p[2], challengeName(p[1]), "name " + p[1]);
            assertEquals(p[3], challengeDesc(p[1]), "desc " + p[1]);
        }
    }

    private static String stakeName(String key) {
        for (Data.Stake s : Data.STAKES) if (s.key().equals(key)) return s.name();
        throw new IllegalArgumentException("unknown stake: " + key);
    }

    private static String tagName(String key) {
        for (Data.Tag t : Data.TAGS) if (t.key().equals(key)) return t.name();
        throw new IllegalArgumentException("unknown tag: " + key);
    }

    private static String tagDesc(String key) {
        for (Data.Tag t : Data.TAGS) if (t.key().equals(key)) return t.desc();
        throw new IllegalArgumentException("unknown tag: " + key);
    }

    private static String challengeName(String key) {
        for (Data.Challenge c : Data.CHALLENGES) if (c.key().equals(key)) return c.name();
        throw new IllegalArgumentException("unknown challenge: " + key);
    }

    private static String challengeDesc(String key) {
        for (Data.Challenge c : Data.CHALLENGES) if (c.key().equals(key)) return c.desc();
        throw new IllegalArgumentException("unknown challenge: " + key);
    }

    // ---- 读取 data.txt，按首 token 过滤；跳过段头(HANDS/SUITS)与 END ----
    private static List<String[]> lines(String tag) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(DataGoldenTest.class.getResourceAsStream("/golden/data.txt"),
                        "missing golden/data.txt"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.equals("END") || line.equals("HANDS") || line.equals("SUITS")) continue;
                String[] p = line.split(" ");
                if (p[0].equals(tag)) out.add(p);
            }
        }
        return out;
    }

    // 读取 data.txt 中以 "prefix|" 开头的行，按 "|" 分割（用于 ENH/EDITION/SEAL，其 desc 含空格）。
    private static List<String[]> pipeLines(String prefix) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(DataGoldenTest.class.getResourceAsStream("/golden/data.txt"),
                        "missing golden/data.txt"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(prefix + "|")) {
                    out.add(line.split("\\|", -1));
                }
            }
        }
        return out;
    }
}
