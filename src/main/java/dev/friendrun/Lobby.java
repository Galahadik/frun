package dev.friendrun;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Лобби по названию поверх Firebase Realtime Database (REST, без аккаунтов у игроков).
 * Документ: /friendrun/<лобби>.json
 *   host, hostSeen, mode(0/1), itemSeed(str), offItems/skipHard/famMode/offChaos (настройки пула, см. Settings), launched, worldSeed(str), address, roundId,
 *   roundSeed(str), startAt(epoch ms), winner{name,ms,round}, players{<name>{ready,lastSeen,win{round,ms}}}
 * Хост — «арбитр»: решает, когда старт, и кто победил. Остальные только читают и пишут своё.
 */
public final class Lobby {
    public interface Listener {
        void onDoc(JsonObject doc);   // вызывается в потоке опроса!
        void onClosed(String reason);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Random RNG = new Random();

    public final String name;
    public final String me;
    public final boolean host;
    private final String base;
    private final Listener listener;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FriendRun-Lobby");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> poller;
    private long lastBeat = 0;
    private long nextDelay = 1000;
    private int failures = 0;
    private volatile boolean closed = false;

    private Lobby(String dbUrl, String name, String me, boolean host, Listener l) {
        String u = dbUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.base = u + "/friendrun/" + name;
        this.name = name; this.me = me; this.host = host; this.listener = l;
    }

    public static boolean validName(String n) { return n.matches("[A-Za-z0-9_-]{1,24}") && !n.startsWith("_"); }   // "_..." — служебные узлы (рейтинг)
    public static String newSeed() { return Long.toString(RNG.nextLong()); }


    private static String rootOf(String dbUrl) {
        String u = dbUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/friendrun";
    }

    // ---------- рейтинг ----------
    /** Вся таблица рейтинга: ник -> профиль (общий рейтинг + рейтинг по режимам). */
    public static java.util.Map<String, Ranking.Profile> fetchRatings(String dbUrl) throws IOException {
        return Ranking.parse(send("GET", rootOf(dbUrl) + "/_mmr", null));
    }

    /** Читает таблицу, считает раунд, записывает обратно. Возвращает строку изменений для документа лобби или "" (сбой сети). */
    private String settle(java.util.function.Function<java.util.Map<String, Ranking.Profile>, Ranking.Outcome> round) {
        try {
            String url = base.substring(0, base.lastIndexOf('/')) + "/_mmr";
            Ranking.Outcome out = round.apply(Ranking.parse(send("GET", url, null)));
            send("PATCH", url, Ranking.toJson(out.updated()).toString());
            return Ranking.encodeDeltas(out.deltas());
        } catch (IOException | RuntimeException e) {
            FriendRunClient.LOG.warn("FRun: рейтинг не записан: {}", e.toString());
            return "";
        }
    }

    /** Победитель одного (первый закончивший: спидран, цепочка, бинго): он против всех остальных. */
    private String settleWinner(JsonObject players, int game, String winner, long ms) {
        if (players.size() < 2 || !players.has(winner)) return "";
        java.util.List<String> others = new java.util.ArrayList<>(players.keySet());
        others.remove(winner);
        java.util.Collections.sort(others);
        java.util.List<java.util.List<String>> tiers = java.util.List.of(java.util.List.of(winner), others);
        return settle(cur -> Ranking.settleTiers(cur, game, tiers, java.util.Map.of(winner, ms)));
    }

    /** Режимы с очками у каждого игрока (конвейер, волны): места по очкам. */
    private String settleScores(JsonObject players, int game, boolean laterBetter, boolean zeroTied) {
        if (players.size() < 2) return "";
        java.util.Map<String, long[]> data = new java.util.HashMap<>();
        java.util.Map<String, Long> bests = new java.util.HashMap<>();
        for (String p : players.keySet()) {
            long sc = num(obj(players, p), "score");
            data.put(p, new long[] {sc, num(obj(players, p), "lastMs")});
            bests.put(p, sc);
        }
        java.util.List<java.util.List<String>> tiers = Ranking.tiersByScore(data, laterBetter, zeroTied);
        if (tiers.size() < 2) return "";   // все поделили первое место — рейтинг не меняется
        return settle(cur -> Ranking.settleTiers(cur, game, tiers, bests));
    }

    /**
     * «Столбы»: хост сообщает итог раунда встроенной карты. Победители — те, кого карта объявила победителями
     * (один игрок или команда), проигравшие — все остальные игроки лобби. Пустой список — «Никто не победил»
     * (рейтинг не меняется). Результат (и изменения MMR) пишется в документ лобби, как у остальных режимов.
     */
    public void reportPillars(java.util.Collection<String> winners) {
        if (!host || closed) return;
        final java.util.List<String> won = new java.util.ArrayList<>(winners);
        try {
            ex.execute(() -> {
                try {
                    JsonElement e = request("GET", "", null);
                    if (e == null || !e.isJsonObject()) return;
                    JsonObject doc = e.getAsJsonObject();
                    JsonObject players = obj(doc, "players");
                    java.util.List<String> w = new java.util.ArrayList<>(), l = new java.util.ArrayList<>();
                    for (String p : players.keySet()) (won.contains(p) ? w : l).add(p);
                    java.util.Collections.sort(w);
                    java.util.Collections.sort(l);
                    String deltas = "";
                    if (!w.isEmpty() && !l.isEmpty()) {   // рейтинг только при 2+ игроках и наличии победителя
                        java.util.List<java.util.List<String>> tiers = java.util.List.of(w, l);
                        deltas = settle(cur -> Ranking.settleTiers(cur, Game.PILLARS, tiers, java.util.Map.of()));
                    }
                    long next = num(doc, "roundId") + 1;
                    JsonObject u = new JsonObject();
                    u.addProperty("roundId", next);
                    u.addProperty("winner/name", w.isEmpty() ? "—" : String.join(", ", w));
                    u.addProperty("winner/ms", 0);
                    u.addProperty("winner/score", 0);
                    u.addProperty("winner/mode", Game.PILLARS);
                    u.addProperty("winner/deltas", deltas);
                    u.addProperty("winner/round", next);
                    request("PATCH", "", u.toString());
                } catch (IOException | RuntimeException ex2) {
                    FriendRunClient.LOG.warn("FRun: итог «Столбов» не записан: {}", ex2.toString());
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /** Манхант: команды (беглецы против охотников). */
    private String settleTeams(JsonObject players, String[] runnerNames, boolean huntersWon) {
        java.util.List<String> runners = new java.util.ArrayList<>(), hunters = new java.util.ArrayList<>();
        java.util.Set<String> rs = new java.util.HashSet<>(java.util.Arrays.asList(runnerNames));
        for (String p : players.keySet()) (rs.contains(p) ? runners : hunters).add(p);
        if (runners.isEmpty() || hunters.isEmpty()) return "";
        return settle(cur -> huntersWon
            ? Ranking.settleTeams(cur, Game.MANHUNT, hunters, runners, java.util.Map.of())
            : Ranking.settleTeams(cur, Game.MANHUNT, runners, hunters, java.util.Map.of()));
    }

    // ---------- список открытых лобби ----------
    public record Entry(String name, String host, int players, int mode, boolean launched) {}

    public static java.util.List<Entry> list(String dbUrl) throws IOException {
        String u = dbUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        try {
            HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create(u + "/friendrun.json")).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401 || r.statusCode() == 403)
                throw new IOException("База отклонила запрос (проверьте правила Firebase)");
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
            java.util.List<Entry> out = new java.util.ArrayList<>();
            String t = r.body();
            if (t == null || t.isBlank() || t.equals("null")) return out;
            JsonElement root = JsonParser.parseString(t);
            if (!root.isJsonObject()) return out;
            long now = System.currentTimeMillis();
            for (String name : root.getAsJsonObject().keySet()) {
                JsonObject d = obj(root.getAsJsonObject(), name);
                if (now - num(d, "hostSeen") > 20_000) continue;   // хост офлайн
                out.add(new Entry(name, str(d, "host"), obj(d, "players").size(),
                    (int) num(d, "mode"), bool(d, "launched")));
            }
            out.sort(java.util.Comparator.comparing(Entry::name));
            return out;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        } catch (IllegalArgumentException iae) {
            throw new IOException("Неверный URL базы данных");
        }
    }

    // ---------- создание / вход ----------
    public static Lobby create(String dbUrl, String name, String me, JsonObject settings, boolean chaosOn, Listener l) throws IOException {
        Lobby lb = new Lobby(dbUrl, name, me, true, l);
        JsonElement existing = lb.request("GET", "", null);
        if (existing != null && existing.isJsonObject()) {
            long seen = num(existing.getAsJsonObject(), "hostSeen");
            if (System.currentTimeMillis() - seen < 20_000) throw new IOException("Лобби с таким названием уже существует");
        }
        JsonObject doc = new JsonObject();
        doc.addProperty("host", me);
        doc.addProperty("hostSeen", System.currentTimeMillis());
        doc.addProperty("mode", 0);
        doc.addProperty("itemSeed", newSeed());
        doc.addProperty("chaos", chaosOn);
        for (String k : settings.keySet()) doc.add(k, settings.get(k));   // настройки пула предметов и усложнений хоста
        doc.addProperty("gameMode", 0);
        doc.addProperty("convTotal", 20);
        doc.addProperty("convSlot", 3);
        doc.addProperty("chainLen", 5);
        doc.addProperty("bingoSize", 3);
        doc.addProperty("bingoFull", false);
        doc.addProperty("waveSec", 45);
        doc.addProperty("waveDiff", 1);
        doc.addProperty("manRunners", 1);
        doc.addProperty("manMin", 15);
        doc.addProperty("roundGame", 0);
        doc.addProperty("chaosMod", Chaos.randomIndex(RNG, Settings.localOffChaos()));
        doc.addProperty("rollAt", 0);
        doc.addProperty("chaosRollAt", 0);
        doc.addProperty("launched", false);
        doc.addProperty("worldSeed", "0");
        doc.addProperty("address", "");
        doc.addProperty("lanAddress", "");
        doc.addProperty("roundId", 0);
        doc.addProperty("roundSeed", "0");
        doc.addProperty("startAt", 0);
        JsonObject players = new JsonObject();
        players.add(me, player());
        doc.add("players", players);
        lb.cleanupStale(dbUrl);
        lb.request("PUT", "", doc.toString());
        lb.startPolling();
        return lb;
    }

    public static Lobby join(String dbUrl, String name, String me, Listener l) throws IOException {
        Lobby lb0 = new Lobby(dbUrl, name, me, false, l);
        JsonElement e = lb0.request("GET", "", null);
        if (e == null || !e.isJsonObject()) throw new IOException("Лобби не найдено");
        JsonObject doc = e.getAsJsonObject();
        if (System.currentTimeMillis() - num(doc, "hostSeen") > 20_000) throw new IOException("Лобби не найдено (хост офлайн)");
        if (bool(doc, "launched")) throw new IOException("Игра в этом лобби уже запущена");
        String nick = me;
        JsonObject players = obj(doc, "players");
        int i = 2;
        while (players.has(nick)) nick = me + i++;
        Lobby lb = new Lobby(dbUrl, name, nick, false, l);
        lb.request("PUT", "/players/" + nick, player().toString());
        lb.startPolling();
        return lb;
    }

    private static JsonObject player() {
        JsonObject p = new JsonObject();
        p.addProperty("ready", false);
        p.addProperty("lastSeen", System.currentTimeMillis());
        return p;
    }

    // ---------- действия ----------
    public void setSettings(int mode, String itemSeed, boolean roll) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode); o.addProperty("itemSeed", itemSeed);
        if (roll) o.addProperty("rollAt", System.currentTimeMillis());
        patch("", o);
    }
    public void patchPlayer(String playerName, JsonObject o) { patch("/players/" + playerName, o); }
    public static long orDef(long v, long def) { return v > 0 ? v : def; }
    public void setNum(String key, long v) { JsonObject o = new JsonObject(); o.addProperty(key, v); patch("", o); }
    public void setFlag(String key, boolean v) { JsonObject o = new JsonObject(); o.addProperty(key, v); patch("", o); }
    public void setGameMode(int g) { JsonObject o = new JsonObject(); o.addProperty("gameMode", g); patch("", o); }
    public void reportScore(long score, long ms) {
        JsonObject o = new JsonObject();
        o.addProperty("score", score); o.addProperty("lastMs", ms);
        patch("/players/" + me, o);
    }
    /** Хост изменил пул предметов: новые настройки + новый seed (без анимации), чтобы выпавший предмет был из нового пула. */
    public void setPool(String offItems, boolean skipHard, boolean famMode) {
        JsonObject o = new JsonObject();
        o.addProperty("offItems", offItems);
        o.addProperty("skipHard", skipHard);
        o.addProperty("famMode", famMode);
        o.addProperty("itemSeed", newSeed());
        patch("", o);
    }

    /** Хост изменил список включённых усложнений. */
    public void setOffChaos(String offChaos) {
        JsonObject o = new JsonObject();
        o.addProperty("offChaos", offChaos);
        patch("", o);
    }

    public void setChaos(boolean on) {
        JsonObject o = new JsonObject();
        o.addProperty("chaos", on);
        if (on) {
            o.addProperty("chaosMod", Chaos.randomIndex(RNG, Settings.localOffChaos()));
            o.addProperty("chaosRollAt", System.currentTimeMillis());
        }
        patch("", o);
    }
    public void rollChaos() {
        JsonObject o = new JsonObject();
        o.addProperty("chaosMod", Chaos.randomIndex(RNG, Settings.localOffChaos()));
        o.addProperty("chaosRollAt", System.currentTimeMillis());
        patch("", o);
    }
    public void launch() {
        JsonObject o = new JsonObject();
        o.addProperty("launched", true); o.addProperty("worldSeed", newSeed());
        patch("", o);
    }
    public void setLanAddress(String addr) { JsonObject o = new JsonObject(); o.addProperty("lanAddress", addr); patch("", o); }
    public void setAddress(String addr) { JsonObject o = new JsonObject(); o.addProperty("address", addr); patch("", o); }
    public void setReady() { JsonObject o = new JsonObject(); o.addProperty("ready", true); patch("/players/" + me, o); }
    public void reportWin(long roundId, long ms) {
        JsonObject w = new JsonObject();
        w.addProperty("round", roundId); w.addProperty("ms", ms);
        JsonObject o = new JsonObject();
        o.add("win", w);
        patch("/players/" + me, o);
    }

    private void patch(String path, JsonObject body) {
        ex.execute(() -> { try { request("PATCH", path, body.toString()); } catch (IOException e) { fail(e); } });
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (poller != null) poller.cancel(false);
        ex.execute(() -> {
            try { request("DELETE", host ? "" : "/players/" + me, null); } catch (IOException ignored) {}
        });
        ex.shutdown();
    }

    // ---------- опрос ----------
    private void startPolling() { schedule(0); }

    private void schedule(long delayMs) {
        if (closed) return;
        try { poller = ex.schedule(this::pollTask, delayMs, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException ignored) {}
    }

    private void pollTask() {
        try { poll(); } finally { schedule(nextDelay); }
    }

    private void poll() {
        if (closed) return;
        try {
            JsonElement e = request("GET", "", null);
            failures = 0;
            if (e == null || !e.isJsonObject()) { shutdown("Лобби закрыто"); return; }
            JsonObject doc = e.getAsJsonObject();
            if (!host && !obj(doc, "players").has(me)) { shutdown("Вас удалили из лобби"); return; }
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastBeat > 5000) { lastBeat = nowMs; heartbeat(); }
            if (host) hostLogic(doc);
            // реже опрашиваем, пока все просто ждут /go; чаще — при настройке лобби и во время раунда
            nextDelay = (bool(doc, "launched") && num(doc, "startAt") == 0) ? 2000 : 1000;
            listener.onDoc(doc);
        } catch (IOException ex2) {
            if (++failures >= 10) shutdown("Нет связи с базой данных");
        } catch (RuntimeException re) {
            FriendRunClient.LOG.warn("poll error", re);
        }
    }

    /** Удаляет лобби, чей хост не отвечает больше минуты (вылетел, не закрыв лобби). */
    private void cleanupStale(String dbUrl) {
        try {
            String u = dbUrl.trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            String root = u + "/friendrun";
            JsonElement all = send("GET", root, null);
            if (all == null || !all.isJsonObject()) return;
            long now = System.currentTimeMillis();
            for (String n : all.getAsJsonObject().keySet()) {
                if (n.equals(name) || n.startsWith("_")) continue;   // "_mmr" и другие служебные узлы не удаляем
                if (now - num(obj(all.getAsJsonObject(), n), "hostSeen") > 60_000) send("DELETE", root + "/" + n, null);
            }
        } catch (IOException | RuntimeException e) {
            FriendRunClient.LOG.warn("FRun: очистка старых лобби не удалась: {}", e.toString());
        }
    }

    private void heartbeat() throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("lastSeen", System.currentTimeMillis());
        request("PATCH", "/players/" + me, o.toString());
        if (host) {
            JsonObject h = new JsonObject();
            h.addProperty("hostSeen", System.currentTimeMillis());
            request("PATCH", "", h.toString());
        }
    }

    private void hostLogic(JsonObject doc) throws IOException {
        long now = System.currentTimeMillis();
        JsonObject players = obj(doc, "players");
        JsonObject updates = new JsonObject();

        // выкинуть пропавших игроков
        for (String p : players.keySet()) {
            if (!p.equals(me) && now - num(obj(players, p), "lastSeen") > 20_000) {
                updates.add("players/" + p, JsonNull.INSTANCE);
            }
        }
        boolean launched = bool(doc, "launched");
        long startAt = num(doc, "startAt");
        long roundId = num(doc, "roundId");
        int roundGame = (int) num(doc, "roundGame");

        if (launched && startAt == 0 && !players.isEmpty()) {
            boolean all = true;
            for (String p : players.keySet()) if (!bool(obj(players, p), "ready")) { all = false; break; }
            int gmSel = (int) num(doc, "gameMode");
            if (all && !(gmSel == Game.MANHUNT && players.size() < 2)) {
                updates.addProperty("startAt", now + 5000);
                updates.addProperty("roundSeed", str(doc, "itemSeed"));
                updates.addProperty("roundId", roundId + 1);
                updates.addProperty("roundGame", (int) num(doc, "gameMode"));
                updates.addProperty("rConvTotal", orDef(num(doc, "convTotal"), 20));
                updates.addProperty("rConvSlot", orDef(num(doc, "convSlot"), 3));
                updates.addProperty("rChainLen", orDef(num(doc, "chainLen"), 5));
                updates.addProperty("rBingoSize", orDef(num(doc, "bingoSize"), 3));
                updates.addProperty("rBingoFull", bool(doc, "bingoFull"));
                updates.addProperty("rWaveSec", orDef(num(doc, "waveSec"), 45));
                updates.addProperty("rWaveDiff", num(doc, "waveDiff"));
                if (gmSel == Game.MANHUNT) {
                    updates.addProperty("rManMin", orDef(num(doc, "manMin"), 15));
                    updates.addProperty("runners", pickRunners(players, doc));
                }
                updates.addProperty("roundMod", bool(doc, "chaos") ? (int) num(doc, "chaosMod") : -1);
            }
        } else if (startAt > 0) {
            if (roundGame == Game.CONVEYOR) {
                // конвейер: раунд кончается по времени, побеждает больший счёт (при равенстве — кто раньше набрал)
                if (now >= startAt + orDef(num(doc, "rConvTotal"), 20) * 60_000L + 3000) {
                    String best = null; long bestScore = 0, bestMs = Long.MAX_VALUE;
                    for (String p : players.keySet()) {
                        long sc = num(obj(players, p), "score"), ms = num(obj(players, p), "lastMs");
                        if (sc > 0 && (sc > bestScore || (sc == bestScore && ms < bestMs))) { best = p; bestScore = sc; bestMs = ms; }
                    }
                    finishRound(updates, doc, players, best == null ? "—" : best, best == null ? 0 : bestMs, bestScore, roundGame, roundId);
                    if (best != null) updates.addProperty("winner/deltas", settleScores(players, roundGame, false, true));
                }
            } else if (roundGame == Game.MANHUNT) {
                // манхант: охотники побеждают, если пойманы все беглецы; беглецы — если продержались до конца времени
                String[] rs = str(doc, "runners").split(",");
                boolean allCaught = rs.length > 0 && !rs[0].isEmpty();
                for (String r : rs) if (!r.isEmpty() && players.has(r) && !bool(obj(players, r), "dead")) { allCaught = false; break; }
                long dur = orDef(num(doc, "rManMin"), 15) * 60_000L;
                if (allCaught && now > startAt + 10_000) {
                    finishRound(updates, doc, players, "Охотники", now - startAt, 0, roundGame, roundId);
                    updates.addProperty("winner/deltas", settleTeams(players, rs, true));
                } else if (now >= startAt + dur) {
                    finishRound(updates, doc, players, "Беглецы", now - startAt, 0, roundGame, roundId);
                    updates.addProperty("winner/deltas", settleTeams(players, rs, false));
                }
            } else if (roundGame == Game.WAVES) {
                // волны: раунд кончается, когда погибли все (или по лимиту времени); побеждает больше очков, при равенстве — дольше продержался
                boolean allDead = !players.isEmpty();
                for (String p : players.keySet()) if (!bool(obj(players, p), "dead")) { allDead = false; break; }
                boolean cap = now >= startAt + Game.WAVES_CAP_MS;
                if ((allDead && now > startAt + 15_000) || cap) {
                    String best = null; long bestScore = -1, bestMs = -1;
                    for (String p : players.keySet()) {
                        long sc = num(obj(players, p), "score"), ms = num(obj(players, p), "lastMs");
                        if (sc > bestScore || (sc == bestScore && ms > bestMs)) { best = p; bestScore = sc; bestMs = ms; }
                    }
                    finishRound(updates, doc, players, best == null ? "—" : best, Math.max(0, bestMs), Math.max(0, bestScore), roundGame, roundId);
                    if (best != null) updates.addProperty("winner/deltas", settleScores(players, roundGame, true, false));
                }
            } else {
                // спидран / цепочка: побеждает первый (по времени у себя) закончивший
                String best = null; long bestMs = Long.MAX_VALUE;
                for (String p : players.keySet()) {
                    JsonObject w = obj(obj(players, p), "win");
                    if (w.size() > 0 && num(w, "round") == roundId && num(w, "ms") < bestMs) { best = p; bestMs = num(w, "ms"); }
                }
                if (best != null) {
                    finishRound(updates, doc, players, best, bestMs, num(obj(players, best), "score"), roundGame, roundId);
                    updates.addProperty("winner/deltas", settleWinner(players, roundGame, best, bestMs));
                }
            }
        }
        if (updates.size() > 0) request("PATCH", "", updates.toString());
    }

    /** Случайно выбирает беглецов (остальные — охотники): минимум один беглец и один охотник. */
    private static String pickRunners(JsonObject players, JsonObject doc) {
        java.util.List<String> names = new java.util.ArrayList<>(players.keySet());
        java.util.Collections.sort(names);
        long seed;
        try { seed = Long.parseLong(str(doc, "itemSeed")); } catch (NumberFormatException e) { seed = RNG.nextLong(); }
        java.util.Collections.shuffle(names, new Random(seed));
        int n = (int) Math.max(1, Math.min(num(doc, "manRunners"), names.size() - 1));
        return String.join(",", names.subList(0, n));
    }

    /** Записывает итог раунда и готовит следующий: сброс готовности, счёта, новый предмет и усложнение. */
    private void finishRound(JsonObject updates, JsonObject doc, JsonObject players, String name, long ms, long score, int mode, long roundId) {
        updates.addProperty("winner/name", name);
        updates.addProperty("winner/ms", ms);
        updates.addProperty("winner/score", score);
        updates.addProperty("winner/mode", mode);
        updates.addProperty("winner/deltas", "");   // изменения MMR за раунд (заполняет хост, если раунд рейтинговый)
        updates.addProperty("winner/round", roundId);
        updates.addProperty("startAt", 0);
        updates.addProperty("itemSeed", newSeed());
        if (bool(doc, "chaos")) updates.addProperty("chaosMod", Chaos.randomIndex(RNG, Settings.localOffChaos()));
        for (String p : players.keySet()) {
            updates.addProperty("players/" + p + "/ready", false);
            updates.add("players/" + p + "/win", JsonNull.INSTANCE);
            updates.add("players/" + p + "/score", JsonNull.INSTANCE);
            updates.add("players/" + p + "/lastMs", JsonNull.INSTANCE);
            updates.add("players/" + p + "/dead", JsonNull.INSTANCE);
        }
    }

    private void shutdown(String reason) {
        if (closed) return;
        closed = true;
        if (poller != null) poller.cancel(false);
        ex.shutdown();
        listener.onClosed(reason);
    }

    private void fail(IOException e) { FriendRunClient.LOG.warn("lobby write failed", e); }

    // ---------- HTTP ----------
    private JsonElement request(String method, String path, String body) throws IOException {
        return send(method, base + path, body);
    }

    private static JsonElement send(String method, String urlBase, String body) throws IOException {
        try {
            // записи с print=silent: Firebase не присылает обратно то, что мы сами отправили
            String suffix = method.equals("GET") ? "" : "?print=silent";
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(urlBase + ".json" + suffix))
                .timeout(Duration.ofSeconds(8));
            HttpRequest.BodyPublisher bp = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<String> r = HTTP.send(b.method(method, bp).header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401 || r.statusCode() == 403)
                throw new IOException("База отклонила запрос (проверьте правила Firebase, см. README)");
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
            String t = r.body();
            if (t == null || t.isBlank() || t.equals("null")) return null;
            return JsonParser.parseString(t);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        } catch (IllegalArgumentException iae) {
            throw new IOException("Неверный URL базы данных");
        }
    }

    // ---------- JSON-хелперы ----------
    public static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }
    public static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    public static long num(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0; } catch (Exception e) { return 0; }
    }
    public static boolean bool(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean(); }
}
