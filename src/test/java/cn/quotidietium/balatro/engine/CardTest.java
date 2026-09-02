package cn.quotidietium.balatro.engine;

import cn.quotidietium.balatro.i18n.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardTest {


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
    void standardDeckHas52CardsSequentialIds() {
        List<Card> deck = Decks.standard52(1);
        assertEquals(52, deck.size());
        for (int i = 0; i < 52; i++) {
            assertEquals(i + 1, deck.get(i).id(), "id at " + i);
        }
    }

    @Test
    void standardDeckOrderMatchesJsBuildFullDeck() {
        // 原版顺序：s=0(spade) r=2..14, s=1(heart) r=2..14, ...
        List<Card> deck = Decks.standard52(1);
        int idx = 0;
        for (int s = 0; s < 4; s++) {
            for (int r = 2; r <= 14; r++) {
                Card c = deck.get(idx++);
                assertEquals(s, c.suit());
                assertEquals(r, c.rank());
            }
        }
        assertEquals(52, idx);
    }

    @Test
    void equalityByIdOnly() {
        Card a = new Card(1, 5, 0);   // 黑桃 5
        Card b = new Card(1, 7, 1);   // 同 id 不同点数花色
        Card c = new Card(2, 5, 0);   // 不同 id
        assertEquals(a, b, "same id ⇒ equal");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void uniqueIdsInHashSet() {
        List<Card> deck = Decks.standard52(1);
        Set<Card> set = new HashSet<>(deck);
        assertEquals(52, set.size());
    }

    @Test
    void stoneAndFaceFlags() {
        Card stone = new Card(1, 0, -1);
        stone.setEnh(Data.Enhancement.STONE);
        assertTrue(stone.isStone());
        assertTrue(stone.isFace() == false);

        Card jack = new Card(2, 11, 0);
        assertTrue(jack.isFace());
        assertFalse(jack.isStone());

        Card ten = new Card(3, 10, 0);
        assertFalse(ten.isFace());
        Card ace = new Card(4, 14, 0);
        assertFalse(ace.isFace());
    }

    @Test
    void toStringShowsSuitAndRank() {
        Card spadeA = new Card(1, 14, 0);
        assertEquals("♠A", spadeA.toString());
        Card heart10 = new Card(2, 10, 1);
        assertEquals("♥10", heart10.toString());
        Card stone = new Card(3, 0, -1);
        assertEquals("石头", stone.toString());
    }
}
