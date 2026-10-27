package dev.friendrun;

import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Окно «Открытые лобби»: список (клик = войти) + создание своего. Вид — поверх стандартных виджетов. */
public final class LobbyMenuScreen extends Screen {
    private static final int ROW_H = 32, ROW_PITCH = 35;

    private final Screen parent;
    private EditBox lobbyName, dbUrl;
    private Button createBtn, refreshBtn, backBtn, settingsBtn, boardBtn;
    private Button[] rows;
    private String[] rowNames;
    private int rowCount;
    private List<Lobby.Entry> entries = List.of();

    private String status = "";
    private String listStatus = "Загрузка…";
    private long lastFetch = 0;
    private boolean fetching = false;

    // раскладка
    private int px, py, pw, ph, rowsY, urlY, setY;
    private Ui.R rCreate, rRefresh, rBack, rNameBox, rUrlBox, rSettings, rBoard;

    public LobbyMenuScreen(Screen parent) {
        super(Component.literal("FRun"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rowCount = Math.max(2, Math.min(5, (height - 198) / ROW_PITCH));
        pw = Math.min(width - 16, 440);
        px = (width - pw) / 2;
        ph = 80 + rowCount * ROW_PITCH + 4 + 14 + 6 + 22 + 10 + 26;
        py = Math.max(4, (height - ph) / 2);
        int ix = px + 10, iw = pw - 20;
        rowsY = py + 80;
        setY = rowsY + rowCount * ROW_PITCH + 20;
        urlY = setY + 26;

        rNameBox = new Ui.R(ix, py + 34, iw - 90, 22);
        rCreate = new Ui.R(px + pw - 10 - 82, py + 34, 82, 22);
        rRefresh = new Ui.R(px + pw - 10 - 70, py + 61, 70, 14);
        rBack = new Ui.R(px + pw - 10 - 56, py + 6, 56, 18);
        rUrlBox = new Ui.R(ix, urlY, iw, 22);
        int halfW = (iw - 4) / 2;
        rSettings = new Ui.R(ix, setY, halfW, 22);
        rBoard = new Ui.R(ix + halfW + 4, setY, iw - halfW - 4, 22);

        lobbyName = new EditBox(font, rNameBox.x() + 8, rNameBox.y() + 7, rNameBox.w() - 16, 12, Component.literal("Название"));
        lobbyName.setHint(Component.literal("Название нового лобби…"));
        lobbyName.setMaxLength(24);
        lobbyName.setValue(Config.lastLobby);
        Ui.noBorder(lobbyName);
        addRenderableWidget(lobbyName);

        createBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            status = "Создаём…";
            FriendRunClient.createLobby(dbUrl.getValue(), lobbyName.getValue().trim(), this::afterConnect);
        }).bounds(rCreate.x(), rCreate.y(), rCreate.w(), rCreate.h()).build());

        rows = new Button[rowCount];
        rowNames = new String[rowCount];
        for (int i = 0; i < rowCount; i++) {
            final int idx = i;
            rows[i] = Button.builder(Component.empty(), b -> {
                if (rowNames[idx] == null) return;
                status = "Подключаемся…";
                FriendRunClient.joinLobby(dbUrl.getValue(), rowNames[idx], this::afterConnect);
            }).bounds(px + 8, rowsY + i * ROW_PITCH, pw - 16, ROW_H).build();
            rows[i].visible = false;
            addRenderableWidget(rows[i]);
        }

        refreshBtn = addRenderableWidget(Button.builder(Component.empty(), b -> fetch())
            .bounds(rRefresh.x(), rRefresh.y(), rRefresh.w(), rRefresh.h()).build());
        backBtn = addRenderableWidget(Button.builder(Component.empty(), b -> onClose())
            .bounds(rBack.x(), rBack.y(), rBack.w(), rBack.h()).build());

        dbUrl = new EditBox(font, rUrlBox.x() + 62, rUrlBox.y() + 7, rUrlBox.w() - 70, 12, Component.literal("URL"));
        dbUrl.setHint(Component.literal("https://…firebasedatabase.app"));
        dbUrl.setMaxLength(200);
        dbUrl.setValue(Config.databaseUrl);
        Ui.noBorder(dbUrl);
        addRenderableWidget(dbUrl);

        settingsBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            Config.lastLobby = lobbyName.getValue().trim();   // чтобы введённое не пропало при возврате
            if (!dbUrl.getValue().isBlank()) Config.databaseUrl = dbUrl.getValue().trim();
            Mc.setScreen(new SettingsScreen(this));
        }).bounds(rSettings.x(), rSettings.y(), rSettings.w(), rSettings.h()).build());

        boardBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            Config.lastLobby = lobbyName.getValue().trim();
            if (!dbUrl.getValue().isBlank()) Config.databaseUrl = dbUrl.getValue().trim();
            Mc.setScreen(new LeaderboardScreen(this));
        }).bounds(rBoard.x(), rBoard.y(), rBoard.w(), rBoard.h()).build());

        for (Button b : new Button[] {createBtn, refreshBtn, backBtn, settingsBtn, boardBtn}) Ui.invisible(b);
        for (Button b : rows) Ui.invisible(b);

        fetch();
    }

    private void fetch() {
        String url = dbUrl == null ? Config.databaseUrl : dbUrl.getValue();
        lastFetch = System.currentTimeMillis();
        if (url.isBlank()) { listStatus = "Укажите URL базы внизу"; showEntries(List.of()); return; }
        if (fetching) return;
        fetching = true;
        Thread t = new Thread(() -> {
            List<Lobby.Entry> res = null;
            String err = null;
            try { res = Lobby.list(url); } catch (IOException e) { err = e.getMessage(); }
            List<Lobby.Entry> fr = res; String fe = err;
            Minecraft.getInstance().execute(() -> {
                fetching = false;
                if (fe != null) { listStatus = "Ошибка: " + fe; showEntries(List.of()); }
                else { listStatus = fr.isEmpty() ? "Открытых лобби нет — создайте своё" : "Нажмите на лобби, чтобы войти"; showEntries(fr); }
            });
        }, "FRun-List");
        t.setDaemon(true);
        t.start();
    }

    private void showEntries(List<Lobby.Entry> list) {
        entries = list;
        for (int i = 0; i < rowCount; i++) {
            if (i < list.size()) {
                Lobby.Entry e = list.get(i);
                rowNames[i] = e.name();
                rows[i].visible = true;
                rows[i].active = !e.launched();
            } else {
                rowNames[i] = null;
                rows[i].visible = false;
            }
        }
    }

    private void afterConnect(String err) {
        if (err != null) status = "Ошибка: " + err;
        else Mc.setScreen(new LobbyRoomScreen(parent));
    }

    @Override
    public void onClose() { Mc.setScreen(parent); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (!fetching && System.currentTimeMillis() - lastFetch > 3000) fetch();

        // --- единый фон FriendRun ---
        Ui.backdrop(g, width, height);
        // --- фон экрана ---
        Ui.panel(g, px, py, pw, ph);
        Icons.draw(g, Icons.CROWN, px + 12, py + 11, 2, Ui.GOLD);
        Ui.bold(g, "FRun", px + 34, py + 10, Ui.GOLD);
        if (FriendRunClient.inLobby()) {
            int pillX = rBack.x() - 74;
            Ui.frame(g, pillX, py + 7, 68, 16, Ui.SECTION, Ui.LINE);
            Icons.draw(g, Icons.DOT, pillX + 6, py + 12, 1, Ui.GREEN);
            Gfx.text(g, "В лобби", pillX + 15, py + 11, Ui.TEXT);
        }
        Ui.frame(g, rNameBox.x(), rNameBox.y(), rNameBox.w(), rNameBox.h(), Ui.SECTION, Ui.LINE);
        Ui.frame(g, rUrlBox.x(), rUrlBox.y(), rUrlBox.w(), rUrlBox.h(), Ui.SECTION, Ui.LINE);
        Gfx.text(g, "Открытые лобби", px + 12, py + 63, Ui.TEXT);

        // --- обычные виджеты (поля ввода и кнопки) ---
        super.extractRenderState(g, mx, my, delta);

        // --- наш вид кнопок поверх ---
        Ui.button(g, rCreate, rCreate.in(mx, my), true, Ui.Kind.GOLD);
        Icons.draw(g, Icons.PLUS, rCreate.x() + 8, rCreate.cy() - 3, 1, 0xFF1A1200);
        Gfx.text(g, "Создать", rCreate.x() + 22, rCreate.cy() - 4, 0xFF1A1200);

        Ui.button(g, rRefresh, rRefresh.in(mx, my), true, Ui.Kind.NORMAL);
        Ui.right(g, "Обновить", rRefresh.x() + rRefresh.w() - 6, rRefresh.y() + 3, Ui.MUTED);
        Ui.button(g, rBack, rBack.in(mx, my), true, Ui.Kind.NORMAL);
        Gfx.centered(g, "Назад", rBack.x() + rBack.w() / 2, rBack.y() + 5, Ui.TEXT);

        boolean wide = pw - 16 >= 380;
        for (int i = 0; i < rowCount && i < entries.size(); i++) {
            Lobby.Entry e = entries.get(i);
            int x0 = px + 8, y0 = rowsY + i * ROW_PITCH, rw = pw - 16;
            Ui.R rr = new Ui.R(x0, y0, rw, ROW_H);
            boolean open = !e.launched();
            Ui.button(g, rr, rr.in(mx, my) && open, open, Ui.Kind.NORMAL);

            Ui.avatar(g, x0 + 4, y0 + 4, 24, e.name());
            Gfx.text(g, e.name(), x0 + 34, y0 + 5, open ? Ui.TEXT : Ui.MUTED);
            Icons.draw(g, Icons.CROWN, x0 + 34, y0 + 18, 1, Ui.GOLD);
            Gfx.text(g, e.host(), x0 + 46, y0 + 17, Ui.MUTED);

            int xs = x0 + (wide ? 170 : rw - 150);
            Icons.draw(g, Icons.PERSON, xs, y0 + 12, 1, Ui.MUTED);
            Gfx.text(g, String.valueOf(e.players()), xs + 11, y0 + 12, Ui.TEXT);

            int xm = xs + 30;
            boolean shared = e.mode() == 1;
            Icons.draw(g, shared ? Icons.WORLD_ONE : Icons.WORLD_MANY, xm, y0 + 12, 1, Ui.MUTED);
            if (wide) Gfx.text(g, shared ? "Общий мир" : "Отдельные миры", xm + 11, y0 + 12, Ui.MUTED);

            int xr = x0 + rw - 22;
            if (open) {
                Ui.right(g, "Открыто", xr, y0 + 12, Ui.GREEN);
                Icons.draw(g, Icons.DOT, xr - Ui.w("Открыто") - 9, y0 + 13, 1, Ui.GREEN);
            } else {
                Ui.right(g, "Игра идёт", xr, y0 + 12, Ui.MUTED);
                Icons.draw(g, Icons.LOCK, xr - Ui.w("Игра идёт") - 10, y0 + 11, 1, Ui.MUTED);
            }
            Icons.draw(g, Icons.CHEV_R, x0 + rw - 12, y0 + 12, 1, Ui.MUTED);
        }

        int hintY = rowsY + rowCount * ROW_PITCH + 4;
        Gfx.centered(g, status.isEmpty() ? listStatus : status, px + pw / 2, hintY, status.isEmpty() ? Ui.MUTED : Ui.ORANGE);
        Gfx.text(g, "URL базы:", rUrlBox.x() + 8, rUrlBox.y() + 7, Ui.MUTED);

        // строка настроек мода
        Ui.button(g, rSettings, rSettings.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.GEAR, rSettings.x() + 8, rSettings.cy() - 3, 1, Ui.GOLD);
        Gfx.text(g, "Настройки мода", rSettings.x() + 22, rSettings.cy() - 4, Ui.TEXT);
        Icons.draw(g, Icons.CHEV_R, rSettings.x() + rSettings.w() - 12, rSettings.cy() - 3, 1, Ui.MUTED);

        Ui.button(g, rBoard, rBoard.in(mx, my), true, Ui.Kind.NORMAL);
        Icons.draw(g, Icons.CROWN, rBoard.x() + 8, rBoard.cy() - 2, 1, Ui.GOLD);
        Gfx.text(g, "Таблица лидеров", rBoard.x() + 22, rBoard.cy() - 4, Ui.TEXT);
        Icons.draw(g, Icons.CHEV_R, rBoard.x() + rBoard.w() - 12, rBoard.cy() - 3, 1, Ui.MUTED);
    }
}
