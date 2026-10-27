package dev.friendrun;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class Hud {
    private Hud() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("friendrun", "hud"), Hud::render);
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    /** Панель итога: кто победил (10 секунд после конца раунда). */
    private static void drawResult(GuiGraphicsExtractor g, Minecraft mc, RunState s) {
        if (s.resultUntilNanos == 0 || System.nanoTime() - s.resultUntilNanos >= 0) return;
        boolean mmr = !s.resultMmr.isEmpty();
        int w = Math.min(260, Gfx.guiWidth(g) - 20), h = mmr ? 88 : 64;
        int cx = Gfx.guiWidth(g) / 2, top = Gfx.guiHeight(g) / 5;
        int x1 = cx - w / 2;
        boolean pillars = s.resultMode == Game.PILLARS;
        boolean conveyor = s.resultMode == Game.CONVEYOR;
        boolean draw = conveyor && s.resultName.equals("—");
        Gfx.rect(g, x1 - 2, top - 2, x1 + w + 2, top + h + 2, 0xFFFFD700);
        Gfx.rect(g, x1, top, x1 + w, top + h, 0xEE101010);
        Gfx.centered(g, conveyor ? "КОНВЕЙЕР ЗАВЕРШЁН" : s.resultMode == Game.BINGO ? "БИНГО!" : s.resultMode == Game.WAVES ? "ВОЛНЫ ЗАВЕРШЕНЫ" : s.resultMode == Game.MANHUNT ? "МАНХАНТ ЗАВЕРШЁН" : pillars ? "СТОЛБЫ: РАУНД ЗАВЕРШЁН" : "ЗАБЕГ ЗАВЕРШЁН", cx, top + 6, 0xFFFFD700);
        Gfx.centered(g, draw ? "Ничья: никто ничего не сдал" : (s.resultMode == Game.MANHUNT || pillars ? "Победили: " : "Победитель: ") + s.resultName, cx, top + 20, 0xFFFFFFFF);
        if (pillars) {
            Gfx.centered(g, s.resultMs > 0 ? "Длительность: " + RunState.formatTime(s.resultMs) : "", cx, top + 32, 0xFFAAAAAA);
        } else if (conveyor) {
            Gfx.centered(g, draw ? "" : "Предметов сдано: " + s.resultScore, cx, top + 32, 0xFF55FF55);
        } else if (s.resultMode == Game.WAVES) {
            Gfx.centered(g, "Очков: " + s.resultScore, cx, top + 32, 0xFF55FF55);
            Gfx.centered(g, "Продержался: " + RunState.formatTime(s.resultMs), cx, top + 44, 0xFFAAAAAA);
        } else {
            Gfx.centered(g, "Время: " + RunState.formatTime(s.resultMs), cx, top + 32, 0xFF55FF55);
            Gfx.centered(g, s.resultItem.isEmpty() ? "" : "Предмет: " + s.resultItem, cx, top + 44, 0xFFAAAAAA);
        }
        if (mmr) {   // изменение рейтинга: режим и общий
            Gfx.centered(g, s.resultMmr, cx, top + 58, 0xFFFFD700);
            Gfx.centered(g, s.resultMmr2, cx, top + 70, 0xFFAAAAAA);
        }
    }

    /** Экран режима «Манхант»: роль, время, сколько беглецов осталось. */
    private static void drawManhunt(GuiGraphicsExtractor g, RunState s, int x, int y) {
        String me = FriendRunClient.myName();
        boolean runner = s.runners.contains(me);
        if (s.phase == RunState.Phase.COUNTDOWN) {
            long left = Math.max(0, (s.countdownEndNanos - System.nanoTime()) / 1_000_000L);
            Gfx.text(g, "Манхант — старт через " + (left / 1000 + 1), x, y, 0xFFFFDD55);
            Gfx.text(g, "Вы: " + (runner ? "БЕГЛЕЦ" : "ОХОТНИК"), x, y + 11, runner ? 0xFF55FF55 : 0xFFFF7777);
            return;
        }
        long el = s.elapsedMs();
        Gfx.text(g, "Вы: " + (runner ? "БЕГЛЕЦ" : "ОХОТНИК") + "  ·  осталось " + mmss(s.cfgManMin * 60_000L - el), x, y,
            runner ? 0xFF55FF55 : 0xFFFF7777);
        int ly = y + 11;
        int alive = 0, total = s.runners.size();
        for (RunState.PlayerInfo p : s.players) if (s.runners.contains(p.name()) && !p.dead()) alive++;
        Gfx.text(g, "Беглецов в живых: " + alive + "/" + total + "  (" + String.join(", ", s.runners) + ")", x, ly, 0xFFFFDD55);
        ly += 11;
        if (el < Manhunt.HEAD_MS)
            Gfx.text(g, runner ? "Бегите! Фора: " + mmss(Manhunt.HEAD_MS - el) : "Вы ослеплены, фора беглецов: " + mmss(Manhunt.HEAD_MS - el), x, ly, 0xFFFF7777);
        else {
            boolean dead = false;
            for (RunState.PlayerInfo p : s.players) if (p.name().equals(me) && p.dead()) dead = true;
            if (dead) Gfx.text(g, "Вы пойманы — наблюдаете", x, ly, 0xFF8C9AB0);
        }
        if (s.roundMod >= 0) Gfx.text(g, "Усложнение: " + Chaos.nameOf(s.roundMod), x, ly + 11, 0xFFFF7777);
    }

    /** Экран режима «Волны мобов»: волна, таймер, очки всех игроков. */
    private static void drawWaves(GuiGraphicsExtractor g, RunState s, int x, int y) {
        if (s.phase == RunState.Phase.COUNTDOWN) {
            long left = Math.max(0, (s.countdownEndNanos - System.nanoTime()) / 1_000_000L);
            Gfx.text(g, "Волны мобов — старт через " + (left / 1000 + 1), x, y, 0xFFFFDD55);
            Gfx.text(g, "Хардкор: одна жизнь", x, y + 11, 0xFFFF7777);
            return;
        }
        long el = s.elapsedMs();
        int wave = Waves.waveNo(el, s.cfgWaveSec);
        long next = Waves.untilNext(el, s.cfgWaveSec);
        Gfx.text(g, wave == 0 ? "Подготовка: " + mmss(next) : "Волна " + wave + "  ·  следующая через " + mmss(next), x, y, 0xFF55FF55);
        int ly = y + 11;
        String me = FriendRunClient.myName();
        for (RunState.PlayerInfo p : s.players) {
            String line = p.name() + ": " + p.score() + (p.dead() ? "  (погиб)" : "");
            Gfx.text(g, line, x, ly, p.dead() ? 0xFFFF7777 : p.name().equals(me) ? 0xFFFFDD55 : 0xFFFFFFFF);
            ly += 11;
        }
        for (RunState.PlayerInfo p : s.players)
            if (p.name().equals(me) && p.dead()) { Gfx.text(g, "Вы погибли — наблюдаете за живым игроком", x, ly, 0xFF8C9AB0); ly += 11; }
        if (s.roundMod >= 0) Gfx.text(g, "Усложнение: " + Chaos.nameOf(s.roundMod), x, ly, 0xFFFF7777);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        RunState s = FriendRunClient.STATE;
        if (!FriendRunClient.inLobby() || mc.player == null) return;
        // «Столбы» использует правила встроенной карты и не участвует в системе /go FriendRun.
        if (s.gameMode == Game.PILLARS) return;

        int x = 6, y = 6;
        drawResult(g, mc, s);
        if (s.phase == RunState.Phase.IDLE) {
            if (s.launched) {
                Gfx.text(g, s.myReady ? "Ожидаем остальных…" : "Напишите /go когда готовы", x, y, 0xFFFFDD55);
                Gfx.text(g, "Игроки: " + s.playersLine(), x, y + 11, 0xFFFFFFFF);
                Gfx.text(g, "Игра: " + Game.name(s.gameMode), x, y + 22, 0xFF8C9AB0);
            }
            return;
        }
        if (s.roundGame == Game.WAVES) { drawWaves(g, s, x, y); return; }
        if (s.roundGame == Game.MANHUNT) { drawManhunt(g, s, x, y); return; }
        if (s.target == null) return;

        // текущая цель
        ItemStack icon = ItemInfo.stack(s.target);
        if (icon != null) Gfx.item(g, icon, x, y);
        else Gfx.itemTexture(g, s.target, x, y, 16);
        Gfx.text(g, ItemInfo.name(s.target), x + 20, y + 4, 0xFFFFFFFF);

        int ly = y + 22;
        if (s.phase == RunState.Phase.COUNTDOWN) {
            long left = Math.max(0, (s.countdownEndNanos - System.nanoTime()) / 1_000_000L);
            Gfx.text(g, "Старт через " + (left / 1000 + 1), x, ly, 0xFFFFDD55);
            ly += 11;
        } else if (s.roundGame == Game.CONVEYOR) {
            long el = s.elapsedMs();
            Gfx.text(g, "Осталось " + mmss(s.cfgConvTotalMs - el) + "  ·  смена через "
                + mmss(s.cfgConvSlotMs - el % s.cfgConvSlotMs), x, ly, 0xFF55FF55);
            ly += 11;
            String me = FriendRunClient.myName();
            StringBuilder sb = new StringBuilder("Счёт: ");
            for (RunState.PlayerInfo p : s.players)
                sb.append(p.name()).append(' ').append(p.name().equals(me) ? s.myScore : p.score()).append("   ");
            Gfx.text(g, sb.toString(), x, ly, 0xFFFFDD55);
            ly += 11;
            if (s.slotDone) { Gfx.text(g, "Предмет сдан — ждите следующий", x, ly, 0xFF8C9AB0); ly += 11; }
        } else if (s.roundGame == Game.BINGO) {
            Gfx.text(g, RunState.formatTime(s.elapsedMs()), x, ly, 0xFF55FF55);
            ly += 12;
            int n = s.cfgBingoSize, cell = n == 3 ? 22 : 18, pad = (cell - 16) / 2;
            for (int i = 0; i < n * n && i < s.chain.size(); i++) {
                int cx0 = x + (i % n) * (cell + 2), cy0 = ly + (i / n) * (cell + 2);
                boolean done = s.bingoDone[i];
                Ui.frame(g, cx0, cy0, cell, cell, done ? Ui.GREEN_BG : 0xCC101A2B, done ? Ui.GREEN : Ui.LINE);
                ItemStack st = ItemInfo.stack(s.chain.get(i));
                if (st != null) Gfx.item(g, st, cx0 + pad, cy0 + pad);
                else Gfx.itemTexture(g, s.chain.get(i), cx0 + pad, cy0 + pad, 16);
                if (done) Icons.draw(g, Icons.CHECK, cx0 + cell - 8, cy0 + 1, 1, Ui.GREEN);
            }
            ly += n * (cell + 2) + 2;
            String me = FriendRunClient.myName();
            StringBuilder sb = new StringBuilder();
            int mine = 0;
            for (int i = 0; i < n * n; i++) if (s.bingoDone[i]) mine++;
            for (RunState.PlayerInfo p : s.players)
                sb.append(p.name()).append(' ').append(p.name().equals(me) ? mine : p.score()).append('/').append(n * n).append("   ");
            Gfx.text(g, sb.toString(), x, ly, 0xFFFFDD55);
            ly += 11;
        } else if (s.roundGame == Game.CHAIN) {
            Gfx.text(g, RunState.formatTime(s.elapsedMs()), x, ly, 0xFF55FF55);
            ly += 11;
            for (int i = 0; i < s.chain.size(); i++) {
                boolean done = i < s.chainIdx, cur = i == s.chainIdx;
                if (done) Icons.draw(g, Icons.CHECK, x, ly + 1, 1, Ui.GREEN);
                else if (cur) Icons.draw(g, Icons.PLAY, x, ly + 1, 1, Ui.GOLD);
                else Icons.draw(g, Icons.DOT, x, ly + 2, 1, Ui.MUTED);
                Gfx.text(g, (i + 1) + ". " + ItemInfo.name(s.chain.get(i)), x + 10, ly,
                    cur ? 0xFFFFFFFF : done ? 0xFF55FF55 : 0xFF8C9AB0);
                ly += 11;
            }
            String me = FriendRunClient.myName();
            StringBuilder sb = new StringBuilder();
            for (RunState.PlayerInfo p : s.players)
                sb.append(p.name()).append(' ').append(p.name().equals(me) ? s.chainIdx : p.score()).append('/').append(s.chain.size()).append("   ");
            Gfx.text(g, sb.toString(), x, ly, 0xFFFFDD55);
            ly += 11;
        } else {
            Gfx.text(g, RunState.formatTime(s.elapsedMs()), x, ly, 0xFF55FF55);
            ly += 11;
        }

        if (s.roundMod >= 0) Gfx.text(g, "Усложнение: " + Chaos.nameOf(s.roundMod), x, ly, 0xFFFF7777);
    }
}
