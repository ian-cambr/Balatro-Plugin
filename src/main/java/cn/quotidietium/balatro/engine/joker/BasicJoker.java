package cn.quotidietium.balatro.engine.joker;

import cn.quotidietium.balatro.engine.Card;
import cn.quotidietium.balatro.engine.Consumable;
import cn.quotidietium.balatro.engine.Data;
import cn.quotidietium.balatro.engine.HandEval;
import cn.quotidietium.balatro.engine.Joker;
import cn.quotidietium.balatro.engine.JokerInstance;
import cn.quotidietium.balatro.engine.Phase;
import cn.quotidietium.balatro.engine.PlayHandInfo;
import cn.quotidietium.balatro.engine.RunState;
import cn.quotidietium.balatro.engine.ScoreContext;
import cn.quotidietium.balatro.i18n.Lang;
import java.util.List;
import java.util.Map;

/**
 * 0.1.0 基础小丑（15 个，移植自 {@code jokers.js} 的普通档）。
 * 用枚举的常量特化方法（constant-specific implementation）覆写各自钩子，覆盖：
 * onScore(addMult/addChips/handIs/rngInt/playedCards/discardsLeft)、onScoreCard(isSuit)、heldCards 等。
 *
 * <p>其余 135+ 小丑随 0.4.0 补齐。
 */
public enum BasicJoker implements Joker {
    JOKER("joker", 2) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(4);
        }
    },
    GREEDY("greedy", 5) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isSuit(card, 3)) ctx.addMult(3);
        }
    },
    LUSTY("lusty", 5) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isSuit(card, 1)) ctx.addMult(3);
        }
    },
    WRATHFUL("wrathful", 5) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isSuit(card, 0)) ctx.addMult(3);
        }
    },
    GLUTTONOUS("gluttonous", 5) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isSuit(card, 2)) ctx.addMult(3);
        }
    },
    JOLLY("jolly", 3) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("pair")) ctx.addMult(8);
        }
    },
    ZANY("zany", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("three")) ctx.addMult(12);
        }
    },
    SLY("sly", 3) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("pair")) ctx.addChips(50);
        }
    },
    WILY("wily", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("three")) ctx.addChips(100);
        }
    },
    HALF("half", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.playedCards.size() <= 3) ctx.addMult(20);
        }
    },
    BANNER("banner", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(30L * ctx.state.discardsLeft); // R130 真版 +30
        }
    },
    SUMMIT("summit", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.state.discardsLeft == 0) ctx.addMult(15);
        }
    },
    MISPRINT("misprint", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(ctx.rngInt(0, 23));
        }
    },
    RAISEDFIST("raisedfist", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            // R130 真版：×牌面筹码值（人头=10、A=11；Raised Fist Wiki），石头永不参与；
            // 全手牌中最低者 debuff 时给 +0（不回落到次低牌）
            Card lowest = null;
            for (Card c : ctx.heldCards) {
                if (c.isStone()) continue;
                if (lowest == null || c.rank() <= lowest.rank()) lowest = c;
            }
            if (lowest == null) return;
            if (lowest.debuff()) return; // 最低牌 debuffed → +0
            int nominal = Data.rankChips(lowest.rank()); // 2-10=rank / JQK=10 / A=11
            ctx.addMult(nominal * 2);
        }
    },
    CRAFTY("crafty", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("flush")) ctx.addChips(80);
        }
    },
    FIBONACCI("fibonacci", 8) {
        private final List<Integer> ranks = List.of(14, 2, 3, 5, 8);
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ranks.contains(card.rank())) ctx.addMult(8);
        }
    },
    SCARYFACE("scaryface", 4) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isFace(card)) ctx.addChips(30);
        }
    },
    ABSTRACT("abstract", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(3L * ctx.state.jokers.size());
        }
    },
    DELAYED("delayed", 4) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            return state.usedDiscardThisRound ? 0 : 2L * state.discardsLeft;
        }
    },
    GROSSMICHEL("grossmichel", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(15);
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            if (state.stream("grossmichel").chance(1.0 / 6)) { state.destroyJoker(self, Lang.t("joker.msg.grossmichel_destroyed")); }
            return 0;
        }
    },
    EVENSTEVEN("evensteven", 4) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (card.rank() <= 10 && card.rank() % 2 == 0) ctx.addMult(4);
        }
    },
    ODDTODD("oddtodd", 4) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            int r = card.rank();
            if (r == 14 || (r <= 9 && r % 2 == 1)) ctx.addChips(31); // R130 真版 +31
        }
    },
    SCHOLAR("scholar", 4) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (card.rank() == 14) { ctx.addChips(20); ctx.addMult(4); }
        }
    },
    BUSINESS("business", 4) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isFace(card) && ctx.prob(0.5)) ctx.dollars(2);
        }
    },
    SUPERNOVA("supernova", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            // 真实规则：含当前这次出牌。handPlayedCount 在计分后才增量，故 +1。
            ctx.addMult(ctx.state.handPlayedCount.getOrDefault(ctx.handType, 0) + 1);
        }
    },
    RIDEBUS("ridebus", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(gi(ctx.joker.extra, "mult", 0));
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            // R130 真版：仅【计分的】人头牌重置（Ride the Bus Wiki：debuff/未计分人头牌不重置）
            if (info.hasScoringFace) { self.extra.put("mult", 0); return; }
            int m = gi(self.extra, "mult", 0) + 1;
            self.extra.put("mult", m);
            state.msg(Lang.t("joker.msg.ridebus", m));
        }
    },
    ICECREAM("icecream", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(gi(ctx.joker.extra, "chips", 100));
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            int c = gi(self.extra, "chips", 100) - 5;
            self.extra.put("chips", c);
            if (c <= 0) state.destroyJoker(self, Lang.t("joker.msg.icecream_melted"));
        }
    },
    SPLASH("splash", 3) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("splash", true);
        }
    },
    BLUE_JOKER("blue", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(2L * ctx.state.drawPile.size());
        }
    },
    RUNNER("runner", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(gi(ctx.joker.extra, "chips", 0));
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            // R130 真版：contains a Straight（含同花顺/皇家，Runner Wiki 明示）
            if (!info.handContains(Data.HandType.STRAIGHT)) return;
            int c = gi(self.extra, "chips", 0) + 15;
            self.extra.put("chips", c);
            state.msg(Lang.t("joker.msg.runner", c));
        }
    },
    GREEN_JOKER("green", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(gi(ctx.joker.extra, "mult", 0));
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            self.extra.put("mult", gi(self.extra, "mult", 0) + 1);
        }
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            self.extra.put("mult", Math.max(0, gi(self.extra, "mult", 0) - 1));
        }
    },
    TODO_JOKER("todo", 4) {
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            Object h = self.extra.get("hand");
            Data.HandType target = h instanceof Data.HandType ? (Data.HandType) h : Data.HandType.PAIR;
            if (info.handType == target) state.gainMoney(4);
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            self.extra.put("hand", state.stream("todo").pick(Data.HAND_TYPES));
            return 0;
        }
    },
    CAVENDISH("cavendish", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(3);
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            if (state.stream("cavendish").chance(1.0 / 1000)) state.destroyJoker(self, Lang.t("joker.msg.cavendish_destroyed"));
            return 0;
        }
    },
    SQUARE("square", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(gi(ctx.joker.extra, "chips", 0));
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            if (info.playedCards.size() != 4) return;
            int c = gi(self.extra, "chips", 0) + 4;
            self.extra.put("chips", c);
            state.msg(Lang.t("joker.msg.square", c));
        }
    },
    FACELESS("faceless", 4) {
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            int faces = 0;
            for (Card c : cards) if (state.isFace(c)) faces++;
            if (faces >= 3) state.gainMoney(5);
        }
    },
    GOLDEN_JOKER("golden", 6) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            return 4;
        }
    },
    BULL("bull", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(2L * Math.max(0, ctx.state.money));
        }
    },
    POPCORN("popcorn", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(gi(ctx.joker.extra, "mult", 20));
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            int m = gi(self.extra, "mult", 20) - 4;
            self.extra.put("mult", m);
            if (m <= 0) state.destroyJoker(self, Lang.t("joker.msg.popcorn_eaten"));
            return 0;
        }
    },
    WALKIE("walkie", 4) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (card.rank() == 10 || card.rank() == 4) { ctx.addChips(10); ctx.addMult(4); }
        }
    },
    SMILEY("smiley", 4) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isFace(card)) ctx.addMult(5); // R130 真版 +5（1.0.1f）
        }
    },
    JUGGLER("juggler", 4) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("handSize", 1);
        }
    },
    DRUNKARD("drunkard", 4) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("discards", 1);
        }
    },
    CHAOS("chaos", 4) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("freeRerolls", 1);
        }
    },
    TICKET("ticket", 5) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (card.enh() == Data.Enhancement.GOLD) ctx.dollars(4);
        }
    },
    SWASHBUCKLER("swashbuckler", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            int sum = 0;
            for (JokerInstance j : ctx.state.jokers) {
                if (j != ctx.joker) sum += ctx.state.sellValue(j);
            }
            ctx.addMult(sum);
        }
    },
    CHAD("chad", 4) {
        @Override
        public int retrigger(Card card, ScoreContext ctx) {
            return ctx.scoreIndex == 0 ? 2 : 0;
        }
    },
    MOON("moon", 5) {
        @Override
        public void onHeld(ScoreContext ctx, Card card) {
            if (card.rank() == 12) ctx.addMult(13);
        }
    },
    STUNTMAN("stuntman", 7) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("handSize", -2);
        }
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(250);
        }
    },
    SEEINGDOUBLE("seeingdouble", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            // R130 真版：只看计分牌（wiki 明示 scoring hand）
            boolean club = false, other = false;
            for (Card c : ctx.scoredCards) {
                if (c.isStone() || c.debuff()) continue; // R134 真版：debuffed 不计（Seeing Double Wiki）
                if (ctx.isSuit(c, 2)) club = true;
                else other = true;
            }
            if (club && other) ctx.xMult(2);
        }
    },
    STENCIL("stencil", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            // R130 真版：自身槽计入空槽（单持 5 槽 = X5；公式 = 空槽 + 模板数）
            int empty = ctx.state.jokerSlots - (ctx.state.jokers.size() - 1);
            if (empty > 0) ctx.xMult(empty);
        }
    },
    FOURFINGERS("fourfingers", 7) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("fourFingers", true);
        }
    },
    MIME("mime", 5) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("mimeRetrigger", true);
        }
    },
    DAGGER("dagger", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(gi(ctx.joker.extra, "mult", 0));
        }
        @Override
        public void onBlindSelect(RunState state, JokerInstance self, Data.BlindType blindType) {
            int idx = state.jokers.indexOf(self);
            if (idx < 0 || idx >= state.jokers.size() - 1) return;
            JokerInstance victim = state.jokers.get(idx + 1);
            if (victim.eternal) return;
            int add = 2 * state.sellValue(victim);
            self.extra.put("mult", gi(self.extra, "mult", 0) + add);
            state.destroyJoker(victim, Lang.t("joker.msg.dagger", victim.def.displayName()));
        }
    },
    LOYALTY("loyalty", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            int c = gi(ctx.joker.extra, "count", 0) + 1;
            ctx.joker.extra.put("count", c);
            if (c >= 6) { ctx.joker.extra.put("count", 0); ctx.xMult(4); }
        }
    },
    DUSK("dusk", 5) {
        @Override
        public int retrigger(Card card, ScoreContext ctx) {
            // 真实规则：回合"最后一手"重触发。计分发生在 handsLeft-- 之前，
            // 故最后一手计分时 handsLeft==1（原版网页误写 ==0，永不触发，按审计惯例修正）。
            return ctx.state.handsLeft == 1 ? 1 : 0;
        }
    },
    HACK("hack", 6) {
        @Override
        public int retrigger(Card card, ScoreContext ctx) {
            return (card.rank() >= 2 && card.rank() <= 5) ? 1 : 0;
        }
    },
    PAREIDOLIA("pareidolia", 5) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("allFace", true);
        }
    },
    STEEL_JOKER("steel", 7) {
        @Override
        public void onScore(ScoreContext ctx) {
            int n = 0;
            for (Card c : ctx.state.fullDeck) if (c.enh() == Data.Enhancement.STEEL) n++;
            if (n > 0) ctx.xMult(1 + 0.2 * n);
        }
    },
    SPACE("space", 5) {
        // R130 真版：升级发生在【计分前】——由 Engine 计分前阶段驱动（当手即吃升级后等级）
    },
    BURGLAR("burglar", 6) {
        @Override
        public void onBlindSelect(RunState state, JokerInstance self, Data.BlindType blindType) {
            state.handsLeft += 3;
            state.discardsLeft = 0;
            state.msg(Lang.t("joker.msg.burglar"));
        }
    },
    BLACKBOARD("blackboard", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            // R130 真版：手中石头牌【阻止】触发（无花色）；wild 视作黑桃/梅花不阻止
            for (Card c : ctx.heldCards) {
                if (c.isStone()) return;
                if (c.enh() == Data.Enhancement.WILD) continue;
                if (c.suit() == 1 || c.suit() == 3) return;
            }
            ctx.xMult(3);
        }
    },
    DNA("dna", 8) {
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info) {
            if (info.findJoker("dna") == null) return;
            if (state.handsPlayedThisRound == 1 && info.playedCards.size() == 1) {
                Card src = info.playedCards.get(0);
                // R130 真版：永久复制（入牌组；经 addCardToDeck 统一触发 onCardAdded）
                Card copy = state.cloneCard(src);
                state.addCardToDeck(copy);
                state.hand.add(copy);
                state.msg(Lang.t("joker.msg.dna", state.cardName(src)));
            }
        }
    },
    CONSTELLATION("constellation", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onUsePlanet(RunState state, JokerInstance self) {
            self.extra.put("x", gd(self.extra, "x") + 0.1);
        }
    },
    HIKER("hiker", 5) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            card.addChipBonus(5);
        }
    },
    CARDSHARP("cardsharp", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            // 同一回合内再次打出该牌型（playedTypesThisRound 在计分后才加入当前牌型）
            if (ctx.state.playedTypesThisRound.contains(ctx.handType)) ctx.xMult(3);
        }
    },
    MADNESS("madness", 7) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onBlindSelect(RunState state, JokerInstance self, Data.BlindType blindType) {
            if (blindType == Data.BlindType.BOSS) return;
            List<JokerInstance> others = new java.util.ArrayList<>();
            for (JokerInstance x : state.jokers) if (x != self && !x.eternal) others.add(x);
            // R130 真版：累积不依赖销毁成功（"not dependent on the destruction"）
            self.extra.put("x", gd(self.extra, "x") + 0.25);
            if (!others.isEmpty()) {
                JokerInstance victim = state.stream("madness").pick(others);
                state.destroyJoker(victim, Lang.t("joker.msg.madness", victim.def.displayName()));
            }
        }
    },
    SEANCE("seance", 6) {
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info) {
            // R130 真版：Straight Flush（含皇家同花顺）
            boolean sf = info.handType == Data.HandType.ROYAL || info.handType == Data.HandType.SFLUSH;
            if (sf && info.findJoker("seance") != null) {
                state.gainConsumable("spectral");
            }
        }
    },
    VAMPIRE("vampire", 7) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            for (Card c : info.scoredCards) {
                if (c.enh() != null && !c.debuff()) {
                    // 移除增强：石头牌(enh==STONE)满足条件也会被移除——用 applyEnhancement(null)
                    // 正确恢复 rank/suit，否则石头牌 enh=null 但 rank=0/suit<1 致状态矛盾（对齐真版）。
                    c.applyEnhancement(null);
                    self.extra.put("x", gd(self.extra, "x") + 0.1);
                    state.msg(Lang.t("joker.msg.vampire"));
                }
            }
        }
    },
    SHORTCUT("shortcut", 7) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("shortcut", true);
        }
    },
    HOLOGRAM("hologram", 7) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onCardAdded(RunState state, Card card, JokerInstance self) {
            self.extra.put("x", gd(self.extra, "x") + 0.25);
        }
    },
    VAGABOND("vagabond", 8) {
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info) {
            if (info.findJoker("vagabond") != null && state.money <= 4) state.gainConsumable("tarot");
        }
    },
    BARON("baron", 8) {
        @Override
        public void onHeld(ScoreContext ctx, Card card) {
            if (card.rank() == 13) ctx.xMult(1.5);
        }
    },
    CLOUD9("cloud9", 7) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            int n = 0;
            for (Card c : state.fullDeck) if (c.rank() == 9) n++;
            return n;
        }
    },
    ROCKET("rocket", 6) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            return gi(self.extra, "pay", 1);
        }
        @Override
        public void onBossDefeated(RunState state, JokerInstance self) {
            self.extra.put("pay", gi(self.extra, "pay", 1) + 2);
        }
    },
    OBELISK("obelisk", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            // R130 真版：重置在【计分前】由 Engine 驱动（严格唯一最常用才重置，并列安全）；
            // R133 真版：重置手【不获得增量】（"consecutive ... without playing your most played"）
            if (Boolean.TRUE.equals(self.extra.get("obNoGain"))) {
                self.extra.remove("obNoGain");
                return;
            }
            self.extra.put("x", gd(self.extra, "x") + 0.2);
        }
    },
    MIDAS("midas", 7) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            // Pareidolia(allFace) 下石头牌也视为人头牌会触发——用 applyEnhancement 正确恢复
            // rank/suit，否则石头牌 enh=GOLD 但 rank=0/suit<1 致状态矛盾（对齐真版）。
            if (ctx.isFace(card) && !card.debuff()) card.applyEnhancement(Data.Enhancement.GOLD);
        }
    },
    SIXTHSENSE("sixthsense", 6) {
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info) {
            if (info.findJoker("sixthsense") == null) return;
            if (state.handsPlayedThisRound == 1 && info.playedCards.size() == 1 && info.playedCards.get(0).rank() == 6) {
                state.removeCardFromDeck(info.playedCards.get(0));
                state.gainConsumable("spectral");
                state.msg(Lang.t("joker.msg.sixthsense"));
            }
        }
    },
    PHOTOGRAPH("photograph", 5) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isFace(card) && !ctx.photoUsed) { ctx.photoUsed = true; ctx.xMult(2); }
        }
    },
    GIFTCARD("giftcard", 6) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            for (JokerInstance j : state.jokers) j.sellBonus += 1;
            // 对齐原版：消耗品售价同样 +$1（此前移植遗漏，只加了小丑）
            for (cn.quotidietium.balatro.engine.Consumable c : state.consumables) c.sellBonus += 1;
            return 0;
        }
    },
    TURTLE("turtle", 6) {
        @Override
        public Map<String, Object> flagsFn(RunState state, JokerInstance self) {
            return Map.of("handSize", gi(self.extra, "size", 5)); // R130 真版 +5
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            int s = gi(self.extra, "size", 3) - 1;
            self.extra.put("size", s);
            if (s <= 0) state.destroyJoker(self, Lang.t("joker.msg.turtle_eaten"));
            return 0;
        }
    },
    EROSION("erosion", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(4L * Math.max(0, 52 - ctx.state.fullDeck.size()));
        }
    },
    PARKING("parking", 6) {
        @Override
        public void onHeld(ScoreContext ctx, Card card) {
            if (ctx.isFace(card) && ctx.prob(0.5)) ctx.dollars(1);
        }
    },
    MAILIN("mailin", 4) {
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            int target = gi(self.extra, "rank", 14);
            int n = 0;
            for (Card c : cards) if (c.rank() == target) n++;
            if (n > 0) state.gainMoney(5L * n);
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            self.extra.put("rank", state.stream("mailin").range(2, 14));
            return 0;
        }
    },
    TOTHEMOON("tothemoon", 5) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            // 真实规则：额外利息 = floor(money/5)（在基础利息之上，不受 $5 上限）
            return state.money / 5;
        }
    },
    FORTUNE("fortune", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(gi(ctx.joker.extra, "mult", 0));
        }
        @Override
        public void onUseTarot(RunState state, JokerInstance self) {
            self.extra.put("mult", gi(self.extra, "mult", 0) + 1);
        }
    },
    STONE_JOKER("stone", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            int n = 0;
            for (Card c : ctx.state.fullDeck) if (c.enh() == Data.Enhancement.STONE) n++;
            ctx.addChips(25L * n);
        }
    },
    LUCKYCAT("luckycat", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onLucky(RunState state, JokerInstance self) {
            self.extra.put("x", gd(self.extra, "x") + 0.25);
        }
    },
    TRADING("trading", 6) {
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            if (state.discardsUsedThisRound == 1 && cards.size() == 1) {
                state.removeCardFromDeck(cards.get(0));
                state.gainMoney(3);
                state.msg(Lang.t("joker.msg.trading", state.cardName(cards.get(0))));
            }
        }
    },
    FLASH("flash", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(gi(ctx.joker.extra, "mult", 0));
        }
        @Override
        public void onReroll(RunState state, JokerInstance self) {
            self.extra.put("mult", gi(self.extra, "mult", 0) + 2);
        }
    },
    TROUSERS("trousers", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(gi(ctx.joker.extra, "mult", 0));
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            // R130 真版：contains a Two Pair（葫芦也触发，Wiki 举例明确）
            if (!info.handContains(Data.HandType.TWOPAIR)) return;
            int m = gi(self.extra, "mult", 0) + 2;
            self.extra.put("mult", m);
            state.msg(Lang.t("joker.msg.trousers", m));
        }
    },
    ANCIENT("ancient", 8) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            int suit = gi(ctx.joker.extra, "suit", 1);
            if (ctx.isSuit(card, suit)) ctx.xMult(1.5);
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            self.extra.put("suit", state.stream("ancient").range(0, 3));
            return 0;
        }
    },
    RAMEN("ramen", 6) {
        private double x(JokerInstance j) {
            Object v = j.extra.get("x");
            return v instanceof Number ? ((Number) v).doubleValue() : 2.0;
        }
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(x(ctx.joker));
        }
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            double nx = x(self) - 0.01 * cards.size();
            self.extra.put("x", nx);
            if (nx <= 1) state.destroyJoker(self, Lang.t("joker.msg.ramen_eaten"));
        }
    },
    SELTZER("seltzer", 6) {
        @Override
        public int retrigger(Card card, ScoreContext ctx) {
            return 1;
        }
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info, JokerInstance self) {
            int u = gi(self.extra, "uses", 10) - 1;
            self.extra.put("uses", u);
            if (u <= 0) state.destroyJoker(self, Lang.t("joker.msg.seltzer_drunk"));
        }
    },
    CASTLE("castle", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(gi(ctx.joker.extra, "chips", 0));
        }
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            int suit = gi(self.extra, "suit", 0);
            for (Card c : cards) if (state.isSuit(c, suit)) self.extra.put("chips", gi(self.extra, "chips", 0) + 3);
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            self.extra.put("suit", state.stream("castle").range(0, 3));
            return 0;
        }
    },
    ACROBAT("acrobat", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            // 同 dusk：计分时 handsLeft 尚未自减，最后一手为 ==1（原版 ==0 为永不触发的 bug）
            if (ctx.state.handsLeft == 1) ctx.xMult(3);
        }
    },
    SOCK("sock", 6) {
        @Override
        public int retrigger(Card card, ScoreContext ctx) {
            return ctx.isFace(card) ? 1 : 0;
        }
    },
    TROUBADOUR("troubadour", 6) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("handSize", 2, "hands", -1);
        }
    },
    LUCHADOR("luchador", 5) {
        @Override
        public void onSell(RunState state, JokerInstance self) {
            if (state.phase == Phase.ROUND && state.blindType == Data.BlindType.BOSS) {
                state.disableBoss();
                state.msg(Lang.t("joker.msg.luchador"));
            }
        }
    },
    COLA("cola", 6) {
        @Override
        public void onSell(RunState state, JokerInstance self) {
            state.gainTag("double");
        }
    },
    CAMPFIRE("campfire", 9) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onAnySell(RunState state, JokerInstance self) {
            self.extra.put("x", gd(self.extra, "x") + 0.5);
        }
        @Override
        public void onBossDefeated(RunState state, JokerInstance self) {
            self.extra.put("x", 0.0);
        }
    },
    SMEARED("smeared", 7) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("smeared", true);
        }
    },
    THROWBACK("throwback", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onSkip(RunState state, JokerInstance self) {
            self.extra.put("x", gd(self.extra, "x") + 0.25);
        }
    },
    GEM("gem", 7) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isSuit(card, 3)) ctx.dollars(1);
        }
    },
    BLOODSTONE("bloodstone", 7) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isSuit(card, 1) && ctx.prob(0.5)) ctx.xMult(1.5);
        }
    },
    ARROWHEAD("arrowhead", 7) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isSuit(card, 0)) ctx.addChips(50);
        }
    },
    ONYX("onyx", 7) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (ctx.isSuit(card, 2)) ctx.addMult(7);
        }
    },
    GLASS_JOKER("glass", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onGlassBreak(RunState state, JokerInstance self) {
            self.extra.put("x", gd(self.extra, "x") + 0.75);
        }
    },
    SHOWMAN("showman", 5) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("allowDupes", true);
        }
    },
    FLOWERPOT("flowerpot", 6) {
        @Override
        public void onScore(ScoreContext ctx) {
            // R134 真版：只看【计分且非 debuff】的牌（R130 只改了 desc、循环漏改）；
            // 石头无花色跳过；wild 计作全花色（Flower Pot Wiki 口径）
            boolean[] seen = new boolean[4];
            for (Card c : ctx.scoredCards) {
                if (c.isStone() || c.debuff()) continue;
                if (c.enh() == Data.Enhancement.WILD) { seen[0] = seen[1] = seen[2] = seen[3] = true; break; }
                seen[c.suit()] = true;
            }
            if (seen[0] && seen[1] && seen[2] && seen[3]) ctx.xMult(3);
        }
    },
    WEE("wee", 8) {
        private long chips(JokerInstance j) {
            Object v = j.extra.get("chips");
            return v instanceof Number ? ((Number) v).longValue() : 0; // R130 真版初始 +0
        }
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addChips(chips(ctx.joker));
        }
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (card.rank() == 2) ctx.joker.extra.put("chips", chips(ctx.joker) + 8);
        }
    },
    MERRY("merry", 7) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("discards", 3, "handSize", -1);
        }
    },
    OOPS("oops", 4) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("doubleProb", true);
        }
    },
    SATELLITE("satellite", 6) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            int n = 0;
            for (Object v : state.usedPlanets.values()) if (Boolean.TRUE.equals(v)) n++;
            return n;
        }
    },
    LICENSE("license", 7) {
        @Override
        public void onScore(ScoreContext ctx) {
            int n = 0;
            for (Card c : ctx.state.fullDeck) if (c.enh() != null) n++;
            if (n >= 16) ctx.xMult(3);
        }
    },
    CARTOMANCER("cartomancer", 6) {
        @Override
        public void onBlindStart(RunState state, JokerInstance self) {
            // R130 真版：选盲时创建（须有空位；gainConsumable 满槽静默不产）
            state.gainConsumable("tarot");
        }
    },
    ASTRONOMER("astronomer", 8) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("freePlanets", true);
        }
    },
    BURNT("burnt", 8) {
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            // R130 真版：每回合仅第一次弃牌触发（Burnt Wiki）
            if (state.discardsUsedThisRound > 1) return;
            HandEval.Result res = state.evaluateHand(cards);
            if (res != null) {
                state.levelUpHand(res.type, 1);
                state.msg(Lang.t("joker.msg.burnt", res.type.displayName()));
            }
        }
    },
    BOOTSTRAPS("bootstraps", 7) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(2L * (Math.max(0, ctx.state.money) / 5));
        }
    },
    MATADOR("matador", 7) {
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info) {
            if (info.findJoker("matador") != null && state.bossTriggeredThisHand) state.gainMoney(8);
        }
    },
    IDOL("idol", 6) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            int rank = gi(ctx.joker.extra, "rank", 14);
            int suit = gi(ctx.joker.extra, "suit", 0);
            if (card.rank() == rank && ctx.isSuit(card, suit)) ctx.xMult(2);
        }
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            self.extra.put("rank", state.stream("idol").range(2, 14));
            self.extra.put("suit", state.stream("idol").range(0, 3));
            return 0;
        }
    },
    HITTHEROAD("hittheroad", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            for (Card c : cards) if (c.rank() == 11) self.extra.put("x", gd(self.extra, "x") + 0.5);
        }
        @Override
        public void onBlindStart(RunState state, JokerInstance self) {
            self.extra.put("x", 0.0); // R130 真版：每回合重置（"this round"）
        }
    },
    DUO("duo", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("pair")) ctx.xMult(2);
        }
    },
    TRIO("trio", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("three")) ctx.xMult(3);
        }
    },
    FAMILY("family", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("four")) ctx.xMult(4);
        }
    },
    ORDER("order", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("straight")) ctx.xMult(3);
        }
    },
    TRIBE("tribe", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("flush")) ctx.xMult(2);
        }
    },
    BLUEPRINT("blueprint", 10) {
        @Override
        public boolean blueprint() {
            return true;
        }
    },
    BRAINSTORM("brainstorm", 10) {
        @Override
        public boolean brainstorm() {
            return true;
        }
    },
    CANIO("canio", 20) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onFaceDestroyed(RunState state, JokerInstance self) {
            self.extra.put("x", gd(self.extra, "x") + 1);
        }
    },
    TRIBOULET("triboulet", 20) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (card.rank() == 13 || card.rank() == 12) ctx.xMult(2);
        }
    },
    YORICK("yorick", 20) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.xMult(1 + gd(ctx.joker.extra, "x"));
        }
        @Override
        public void onDiscard(RunState state, List<Card> cards, JokerInstance self) {
            int count = gi(self.extra, "count", 0) + cards.size();
            while (count >= 23) {
                count -= 23;
                self.extra.put("x", gd(self.extra, "x") + 1);
                state.msg(Lang.t("joker.msg.yorick"));
            }
            self.extra.put("count", count);
        }
    },
    CHICOT("chicot", 20) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("chicot", true);
        }
    },
    PERKEO("perkeo", 20) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            // 对齐 jokers.js perkeo：随机复制一张消耗品为负片（直接 push，可超槽位上限）
            if (state.consumables.isEmpty()) return 0;
            Consumable src = state.stream("perkeo").pick(state.consumables);
            Consumable copy = new Consumable(src.kind, src.key);
            copy.edition = Data.Edition.NEGATIVE;
            copy.sellBonus = 0;
            state.consumables.add(copy);
            state.msg(Lang.t("joker.msg.perkeo", copy.name()));
            return 0;
        }
    },
    CLEVER("clever", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("twopair")) ctx.addChips(80);
        }
    },
    CRAZY("crazy", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("straight")) ctx.addMult(12);
        }
    },
    DROLL("droll", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("flush")) ctx.addMult(10);
        }
    },
    MAD_JOKER("mad", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handIs("twopair")) ctx.addMult(10);
        }
    },
    EGG("egg", 4) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            self.sellBonus += 3;
            return 0;
        }
    },
    CREDITCARD("creditcard", 1) {
        @Override
        public Map<String, Object> flags() {
            return Map.of("credit", 20);
        }
    },
    EIGHTBALL("eightball", 5) {
        @Override
        public void onScoreCard(ScoreContext ctx, Card card) {
            if (card.rank() == 8 && ctx.prob(0.25)) ctx.gainConsumable("tarot");
        }
    },
    HALLUCINATION("hallucination", 4) {
        @Override
        public void onPackOpen(RunState state, JokerInstance self) {
            if (state.stream("hallucination").chance(0.5)) state.gainConsumable("tarot");
        }
    },
    REDCARD("redcard", 5) {
        @Override
        public void onScore(ScoreContext ctx) {
            ctx.addMult(gi(ctx.joker.extra, "mult", 0));
        }
        @Override
        public void onPackSkip(RunState state, JokerInstance self) {
            self.extra.put("mult", gi(self.extra, "mult", 0) + 3);
            state.msg(Lang.t("joker.msg.redcard", gi(self.extra, "mult", 0)));
        }
    },
    SUPERPOSITION("superposition", 4) {
        @Override
        public void onPlayHand(RunState state, PlayHandInfo info) {
            // R130 真版：contains Straight（含同花顺/皇家）且 A 必须【计分】
            if (info.handContains(Data.HandType.STRAIGHT) && info.scoredHasRank(14)) {
                if (info.findJoker("superposition") != null) state.gainConsumable("tarot");
            }
        }
    },
    RIFFRAFF("riffraff", 6) {
        @Override
        public void onBlindStart(RunState state, JokerInstance self) {
            state.gainRandomJoker(0);
            state.gainRandomJoker(0);
        }
    },
    BASEBALL("baseball", 8) {
        @Override
        public void onScore(ScoreContext ctx) {
            for (JokerInstance j : ctx.state.jokers) {
                if (JokerRegistry.rarityOf(j.def.key()) == 1) ctx.xMult(1.5);
            }
        }
    },
    MARBLE("marble", 6) {
        @Override
        public void onBlindStart(RunState state, JokerInstance self) {
            // 对齐 jokers.js marble + engine.js makeCard 默认壳：rank=2/suit=0（黑桃 2 壳）+STONE。
            // 增强在场时行为与石头无异；壳点数只在增强被移除（如吸血鬼）或纯点数匹配
            // （如邮寄返利目标为 2）时暴露，须与原版一致。牌组构建的石头牌仍为 0/-1 壳（同原版）。
            Card c = state.makeCard(2, 0);
            c.setEnh(Data.Enhancement.STONE);
            state.addCardToDeck(c);
            state.msg(Lang.t("joker.msg.marble"));
        }
    },
    CERTIFICATE("certificate", 6) {
        private final List<Data.Seal> seals = List.of(Data.Seal.GOLD, Data.Seal.RED, Data.Seal.BLUE, Data.Seal.PURPLE);
        @Override
        public void onRoundStart(RunState state, JokerInstance self) {
            Card c = state.randomPlayingCard();
            c.setSeal(state.stream("certificate").pick(seals));
            // R130 真版：加入牌组统一走 addCardToDeck（触发全息海报等 onCardAdded）
            state.addCardToDeck(c);
            state.hand.add(c);
            state.msg(Lang.t("joker.msg.certificate", state.cardName(c)));
        }
    },
    DEVIOUS("devious", 4) {
        @Override
        public void onScore(ScoreContext ctx) {
            if (ctx.handContains("straight")) ctx.addChips(100);
        }
    },
    INVISIBLE("invisible", 8) {
        @Override
        public long onRoundEnd(RunState state, JokerInstance self) {
            self.extra.put("rounds", gi(self.extra, "rounds", 2) - 1); // R130 真版 2 回合
            return 0;
        }
        @Override
        public void onSell(RunState state, JokerInstance self) {
            if (gi(self.extra, "rounds", 2) > 0) {
                state.msg(Lang.t("joker.msg.invisible_not_ready"));
                return;
            }
            List<JokerInstance> others = new java.util.ArrayList<>();
            for (JokerInstance x : state.jokers) if (x != self) others.add(x);
            if (!others.isEmpty() && state.jokerSpace() > 0) {
                JokerInstance src = state.stream("invisible").pick(others);
                // R114 对齐真版：复制保留贴纸；R130 真版：副本【去除负片】
                // （Invisible Wiki："Removes Negative from copy"，其余版本保留）
                Data.Edition ed = src.edition == Data.Edition.NEGATIVE ? null : src.edition;
                state.duplicateJoker(src, ed);
                state.msg(Lang.t("joker.msg.invisible_copied", src.def.displayName()));
            }
        }
    },
    BONES("bones", 5) {
        // 免死特判在 Engine.playHand 内（handsLeft<=0 分支）
    };

    private final String key;
    private final int cost;

    BasicJoker(String key, int cost) {
        this.key = key;
        this.cost = cost;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String displayName() {
        return Lang.t("joker." + key + ".name");
    }

    @Override
    public String desc() {
        return Lang.t("joker." + key + ".desc");
    }

    @Override
    public int cost() {
        return cost;
    }

    /** 从小丑 extra 中读整数（缺失用默认值）。 */
    private static int gi(Map<String, Object> extra, String key, int def) {
        Object v = extra.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    /** 从小丑 extra 中读 double（缺失为 0）。 */
    private static double gd(Map<String, Object> extra, String key) {
        Object v = extra.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }
}
