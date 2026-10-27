package dev.friendrun;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/** Комната лобби: рулетка предмета, усложнение, игроки, настройки. Вид рисуется поверх стандартных кнопок. */
public final class LobbyRoomScreen extends Screen {
    private static final int PLAYER_ROW = 17;

    private final Screen parent;
    private Button bGame, bMode, bRollItem, bRollChaos, bToggle, bStart, bLeave, bSetA, bSetB, bSettings, bBoard;
    private Ui.R rGame, rMode, rRollItem, rRollChaos, rToggle, rStart, rLeave, rSetA, rSetB, rSettings, rBoard;
    private int px, py, pw, ph, pr, by;

    public LobbyRoomScreen(Screen parent) {
        super(Component.literal("FRun"));
        this.parent = parent;
    }

    private Button make(Ui.R r, Runnable action) {
        Button b = addRenderableWidget(Button.builder(Component.empty(), x -> action.run())
            .bounds(r.x(), r.y(), r.w(), r.h()).build());
        Ui.invisible(b);
        return b;
    }

    @Override
    protected void init() {
        RunState s = FriendRunClient.STATE;
        pw = Math.min(width - 16, 420);
        px = (width - pw) / 2;
        pr = Math.max(1, Math.min(6, (height - 8 - 6 - 153 - 8 - 120 - 14) / PLAYER_ROW));
        int footerY0 = 153 + pr * PLAYER_ROW + 8 + 120 + 2;
        ph = footerY0 + 12;
        py = Math.max(4, (height - ph) / 2);
        by = py + 153 + pr * PLAYER_ROW + 8;

        int ix = px + 10, bw = pw - 20, half = (bw - 4) / 2;
        rSettings = new Ui.R(px + pw - 10 - 88, py + 5, 88, 17);
        rBoard = new Ui.R(rSettings.x() - 4 - 44, py + 5, 44, 17);
        rSetA = new Ui.R(ix, py + 46, bw, 20);
        rSetB = new Ui.R(ix, py + 70, bw, 20);
        rGame = new Ui.R(ix, by, bw, 20);
        rMode = new Ui.R(ix, by + 24, bw, 20);
        rRollItem = new Ui.R(ix, by + 48, half, 20);
        rRollChaos = new Ui.R(ix + half + 4, by + 48, half, 20);
        rToggle = new Ui.R(ix, by + 72, bw, 20);
        int startW = (int) (bw * 0.6);
        rStart = new Ui.R(ix, by + 96, startW, 22);
        rLeave = new Ui.R(ix + startW + 4, by + 96, bw - startW - 4, 22);

        bSettings = make(rSettings, () -> Mc.setScreen(new SettingsScreen(this)));
        bSetA = make(rSetA, FriendRunClient::settingA);
        bSetB = make(rSetB, FriendRunClient::settingB);
        bBoard = make(rBoard, () -> Mc.setScreen(new LeaderboardScreen(this)));
        bGame = make(rGame, () -> Mc.setScreen(new GameModeScreen(this)));
        bMode = make(rMode, () -> FriendRunClient.setMode(s.mode == RunState.Mode.SEPARATE_WORLDS
            ? RunState.Mode.HOST_WORLD : RunState.Mode.SEPARATE_WORLDS));
        bRollItem = make(rRollItem, FriendRunClient::rollItem);
        bRollChaos = make(rRollChaos, FriendRunClient::rollChaos);
        bToggle = make(rToggle, FriendRunClient::toggleChaos);
        bStart = make(rStart, FriendRunClient::launch);
        bLeave = make(rLeave, () -> { FriendRunClient.leave(); Mc.setScreen(parent); });
    }

    @Override
    public void onClose() { Mc.setScreen(parent); }

    private void label(GuiGraphicsExtractor g, Ui.R r, String[] icon, int iconColor, String text, int textColor) {
        Icons.draw(g, icon, r.x() + 8, r.cy() - Icons.h(icon) / 2, 1, iconColor);
        Gfx.text(g, text, r.x() + 8 + Icons.w(icon) + 6, r.cy() - 4, textColor);
    }

    /** Настройки выбранного режима вместо рулетки: у каждого режима свои. */
    private void drawSettings(GuiGraphicsExtractor g, RunState s, int mx, int my, boolean canSet, int cx) {
        if (s.gameMode == Game.PILLARS) {
            Ui.frame(g, rSetA.x(), rSetA.y(), rSetA.w(), rSetA.h(), Ui.SECTION, Ui.LINE);
            Icons.draw(g, Icons.WORLD_ONE, rSetA.x() + 8, rSetA.cy() - 3, 1, Ui.GOLD);
            Gfx.text(g, "Готовая карта «Столбики» — без настроек FriendRun", rSetA.x() + 24, rSetA.cy() - 4, Ui.TEXT);
            return;
        }
        String a, b = null;
        switch (s.gameMode) {
            case Game.CONVEYOR -> { a = "Длительность раунда: " + s.convTotalMin + " мин"; b = "Смена предмета: каждые " + s.convSlotMin + " мин"; }
            case Game.CHAIN -> a = "Длина цепочки: " + s.chainLen + " предметов";
            case Game.MANHUNT -> { a = "Беглецов: " + s.manRunners + " (остальные — охотники)"; b = "Время выживания: " + s.manMin + " мин"; }
            case Game.PILLARS -> a = "Используется готовая карта «Столбики»";
            case Game.WAVES -> { a = "Интервал волн: " + s.waveSec + " сек"; b = "Сложность: " + RunState.diffName(s.waveDiff); }
            case Game.BINGO -> { a = "Размер карточки: " + s.bingoSize + "×" + s.bingoSize; b = "Победа: " + (s.bingoFull ? "вся карточка" : "линия"); }
            default -> a = "";
        }
        Ui.button(g, rSetA, rSetA.in(mx, my), canSet, Ui.Kind.NORMAL);
        label(g, rSetA, Icons.GEAR, canSet ? Ui.GOLD : Ui.MUTED, a, canSet ? Ui.TEXT : Ui.MUTED);
        Icons.draw(g, Icons.CHEV_R, rSetA.x() + rSetA.w() - 12, rSetA.cy() - 3, 1, Ui.MUTED);
        if (b != null) {
            Ui.button(g, rSetB, rSetB.in(mx, my), canSet, Ui.Kind.NORMAL);
            label(g, rSetB, Icons.GEAR, canSet ? Ui.GOLD : Ui.MUTED, b, canSet ? Ui.TEXT : Ui.MUTED);
            Icons.draw(g, Icons.CHEV_R, rSetB.x() + rSetB.w() - 12, rSetB.cy() - 3, 1, Ui.MUTED);
        } else {
            Gfx.centered(g, "Предметы идут строго по порядку, без пропусков", cx, py + 74, Ui.MUTED);
        }
        Gfx.centered(g, "Предметы определятся при старте раунда", cx, py + 95, Ui.MUTED);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        RunState s = FriendRunClient.STATE;

        // лобби закрылось (хост вышел / обрыв) — вернуться в меню
        if (!FriendRunClient.inLobby()) { Mc.setScreen(parent); return; }

        boolean host = s.amHost, open = host && !s.launched;
        boolean speed = s.gameMode == Game.SPEEDRUN;
        boolean canRollItem = open && speed && !Roulette.running();
        boolean canSet = host && s.phase == RunState.Phase.IDLE;
        boolean canStart = open && (s.gameMode != Game.MANHUNT || (s.mode == RunState.Mode.HOST_WORLD && s.players.size() >= 2))
            && (s.gameMode != Game.PILLARS || (s.mode == RunState.Mode.HOST_WORLD && s.players.size() >= 2));
        bSetA.visible = !speed && s.gameMode != Game.PILLARS;
        bSetB.visible = !speed && s.gameMode != Game.CHAIN && s.gameMode != Game.PILLARS;
        bSetA.active = canSet;
        bSetB.active = canSet;
        boolean pillars = s.gameMode == Game.PILLARS;
        boolean canRollChaos = host && !pillars && s.chaos && !ChaosRoll.running();
        bGame.active = host;
        bMode.active = open && s.gameMode != Game.MANHUNT && s.gameMode != Game.PILLARS;
        bRollItem.active = canRollItem && !pillars;
        bRollChaos.active = canRollChaos;
        bToggle.active = host && !pillars;
        bStart.active = canStart;

        int cx = px + pw / 2, ix = px + 10, bw = pw - 20;

        // ---------- единый фон FriendRun ----------
        Ui.backdrop(g, width, height);
        // ---------- фон экрана ----------
        Ui.panel(g, px, py, pw, ph);
        Icons.draw(g, Icons.CROWN, px + 12, py + 8, 2, Ui.GOLD);
        Ui.bold(g, "Лобби", px + 34, py + 8, Ui.GOLD);
        if (host) Gfx.text(g, "(вы хост)", px + 34 + Ui.w("Лобби") + 8, py + 8, Ui.MUTED);
        if (!s.status.isEmpty()) {
            String t = s.status;
            int max = pw - 170 - 94 - 48;
            while (t.length() > 4 && Ui.w(t) > max) t = t.substring(0, t.length() - 1);
            if (!t.equals(s.status)) t = t.substring(0, t.length() - 1) + "…";
            Ui.right(g, t, px + pw - 10 - 94 - 48, py + 9, Ui.ORANGE);
        }

        // рулетка: заголовок секции и таймер
        Ui.frame(g, ix, py + 28, bw, 13, Ui.SECTION, Ui.LINE);
        Gfx.text(g, speed ? "Рулетка предмета" : "Настройки режима: " + Game.name(s.gameMode), ix + 6, py + 31, Ui.MUTED);
        if (!pillars && !Roulette.running()) Ui.right(g, "предметов в пуле: " + ItemPicker.poolSize(), ix + bw - 6, py + 31, Ui.MUTED);
        if (speed && Roulette.running()) Ui.right(g, String.format("%.1fс", Roulette.remainingSec()), ix + bw - 6, py + 31, Ui.GOLD);

        // блок усложнения
        Ui.R rChaos = new Ui.R(ix, py + 106, bw, 28);
        if (s.chaos) Ui.frame(g, rChaos.x(), rChaos.y(), rChaos.w(), rChaos.h(), Ui.RED_BG, Ui.RED_LINE);
        else Ui.frame(g, rChaos.x(), rChaos.y(), rChaos.w(), rChaos.h(), Ui.SECTION, Ui.LINE);

        // игроки
        int plY = py + 138;
        Ui.frame(g, ix, plY, bw, 15 + pr * PLAYER_ROW + 2, Ui.SECTION, Ui.LINE);

        // ---------- стандартные виджеты ----------
        super.extractRenderState(g, mx, my, delta);

        // ---------- рулетка ----------
        if (speed) {
            Roulette.draw(g, cx, py + 48, bw - 4);
            Item item = ItemPicker.pick(s.itemSeed);
            if (Roulette.running()) Gfx.centered(g, "Крутим…", cx, py + 95, Ui.GOLD);
            else Gfx.centered(g, "Предмет: " + ItemInfo.name(item), cx, py + 95, Ui.GREEN);
        } else {
            drawSettings(g, s, mx, my, canSet, cx);
        }

        // ---------- усложнение ----------
        if (pillars) {
            Icons.draw(g, Icons.GEAR, rChaos.x() + 8, rChaos.y() + 10, 1, Ui.GOLD);
            Gfx.text(g, "Правила и запуск определяются картой", rChaos.x() + 22, rChaos.y() + 10, Ui.TEXT);
        } else if (s.chaos) {
            int shown = ChaosRoll.update();
            boolean spin = ChaosRoll.running();
            Icons.draw(g, Icons.GEAR, rChaos.x() + 8, rChaos.y() + 6, 1, Ui.RED);
            Gfx.text(g, "Усложнение:", rChaos.x() + 22, rChaos.y() + 4, 0xFFFF9A9A);
            Gfx.text(g, Chaos.nameOf(shown), rChaos.x() + 22 + Ui.w("Усложнение:") + 5, rChaos.y() + 4, spin ? Ui.GOLD : Ui.TEXT);
            Gfx.text(g, spin ? "…" : Chaos.descOf(s.chaosMod), rChaos.x() + 22, rChaos.y() + 16, Ui.MUTED);
            Ui.right(g, "в пуле: " + Chaos.enabledIndices(Settings.effOffChaos()).length + "/" + Chaos.COUNT, rChaos.x() + rChaos.w() - 8, rChaos.y() + 4, Ui.MUTED);
        } else {
            Icons.draw(g, Icons.GEAR, rChaos.x() + 8, rChaos.y() + 10, 1, Ui.MUTED);
            Gfx.text(g, "Усложнение: выключено", rChaos.x() + 22, rChaos.y() + 10, Ui.MUTED);
        }

        // ---------- игроки ----------
        Ui.bold(g, "Игроки (" + s.players.size() + ")", ix + 6, plY + 4, Ui.TEXT);
        int y = plY + 15;
        int shownPlayers = Math.min(pr, s.players.size());
        for (int i = 0; i < shownPlayers; i++) {
            RunState.PlayerInfo p = s.players.get(i);
            Ui.head(g, ix + 6, y + 2, 12, p.name());
            int nx = ix + 24;
            if (p.host()) { Icons.draw(g, Icons.CROWN, nx, y + 5, 1, Ui.GOLD); nx += 12; }
            String label = p.name() + (p.host() ? " (хост)" : "");
            Gfx.text(g, label, nx, y + 4, Ui.TEXT);
            Ranking.Rec rec = Ranking.recFor(Ranking.cached(p.name()), s.gameMode + 1);   // рейтинг игрока в выбранном режиме
            int mmr = rec == null ? Ranking.START : rec.mmr();
            Gfx.text(g, mmr + " " + Ranking.tier(mmr).name(), nx + Ui.w(label) + 8, y + 4, rec == null ? Ui.MUTED : Ranking.tier(mmr).color());
            int xr = ix + bw - 8;
            if (p.ready()) {
                Ui.right(g, "Готов", xr, y + 4, Ui.GREEN);
                Icons.draw(g, Icons.CHECK, xr - Ui.w("Готов") - 11, y + 5, 1, Ui.GREEN);
            } else {
                Ui.right(g, "Не готов", xr, y + 4, Ui.MUTED);
                Icons.draw(g, Icons.DOT, xr - Ui.w("Не готов") - 9, y + 6, 1, Ui.MUTED);
            }
            y += PLAYER_ROW;
        }
        if (s.players.size() > shownPlayers)
            Ui.right(g, "+" + (s.players.size() - shownPlayers), ix + bw - 8, plY + 4, Ui.MUTED);

        // ---------- кнопки (наш вид поверх стандартных) ----------
        Ui.button(g, rSettings, rSettings.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.GEAR, rSettings.x() + 6, rSettings.cy() - 3, 1, Ui.GOLD);
        Gfx.text(g, "Настройки", rSettings.x() + 18, rSettings.cy() - 4, Ui.TEXT);
        Ui.button(g, rBoard, rBoard.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.CROWN, rBoard.x() + 6, rBoard.cy() - 2, 1, Ui.GOLD);
        Gfx.text(g, "Топ", rBoard.x() + 20, rBoard.cy() - 4, Ui.TEXT);

        Ui.button(g, rGame, rGame.in(mx, my), host, Ui.Kind.NORMAL);
        label(g, rGame, Icons.CROWN, Ui.GOLD, "Игра: " + Game.name(s.gameMode) + " (" + s.gameSummary() + ")", host ? Ui.TEXT : Ui.MUTED);
        Icons.draw(g, Icons.CHEV_R, rGame.x() + rGame.w() - 12, rGame.cy() - 3, 1, Ui.MUTED);

        Ui.button(g, rMode, rMode.in(mx, my), open, Ui.Kind.NORMAL);
        label(g, rMode, s.mode == RunState.Mode.SEPARATE_WORLDS ? Icons.WORLD_MANY : Icons.WORLD_ONE, Ui.MUTED,
            "Режим: " + (s.mode == RunState.Mode.SEPARATE_WORLDS ? "отдельные миры" : "мир хоста"), open ? Ui.TEXT : Ui.MUTED);
        Icons.draw(g, Icons.CHEV_R, rMode.x() + rMode.w() - 12, rMode.cy() - 3, 1, Ui.MUTED);

        Ui.button(g, rRollItem, rRollItem.in(mx, my), canRollItem, Ui.Kind.NORMAL);
        label(g, rRollItem, Icons.DICE, canRollItem ? Ui.TEXT : Ui.MUTED, "Ролл предмета", canRollItem ? Ui.TEXT : Ui.MUTED);

        Ui.button(g, rRollChaos, rRollChaos.in(mx, my), canRollChaos, Ui.Kind.NORMAL);
        label(g, rRollChaos, Icons.GEAR, canRollChaos ? Ui.RED : Ui.MUTED, "Ролл усложнения", canRollChaos ? Ui.TEXT : Ui.MUTED);

        Ui.button(g, rToggle, rToggle.in(mx, my), host, s.chaos ? Ui.Kind.GREEN : Ui.Kind.NORMAL);
        label(g, rToggle, Icons.DICE, Ui.TEXT, "Случайное усложнение: " + (s.chaos ? "ВКЛ" : "выкл"), Ui.TEXT);
        // переключатель
        int tx = rToggle.x() + rToggle.w() - 30, ty = rToggle.cy() - 5;
        Gfx.rect(g, tx, ty, tx + 22, ty + 10, s.chaos ? Ui.GREEN : 0xFF3A4658);
        Gfx.rect(g, s.chaos ? tx + 13 : tx + 1, ty + 1, s.chaos ? tx + 21 : tx + 9, ty + 9, 0xFFFFFFFF);

        Ui.button(g, rStart, rStart.in(mx, my), canStart, Ui.Kind.GOLD);
        int sc = canStart ? 0xFF1A1200 : 0xFF8A7A50;
        Icons.draw(g, Icons.PLAY, rStart.x() + 8, rStart.cy() - 3, 1, sc);
        Gfx.text(g, s.launched ? "Игра запущена" : host ? (canStart ? "Старт (создать мир)" : "Манхант: нужно 2+ игрока") : "Ждём старт от хоста…", rStart.x() + 20, rStart.cy() - 4, sc);

        Ui.button(g, rLeave, rLeave.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.CROSS, rLeave.x() + 8, rLeave.cy() - 2, 1, Ui.MUTED);
        Gfx.text(g, "Выйти из лобби", rLeave.x() + 18, rLeave.cy() - 4, Ui.TEXT);

        // ---------- подвал ----------
        int fy = by + 120 + 2;
        Gfx.text(g, "Название лобби: " + s.lobbyName, ix + 2, fy, Ui.MUTED);
        if (s.mode == RunState.Mode.HOST_WORLD)
            Ui.right(g, host && Net.isAndroid() ? "Android: только друзья из вашей Wi-Fi"
                : "Нужен мод e4mc (для общего мира)", ix + bw - 2, fy, Ui.ORANGE);
    }
}
