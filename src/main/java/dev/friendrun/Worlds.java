package dev.friendrun;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Одноразовые миры FRun (saves/FRun-<число>) удаляются после выхода из лобби —
 * как только игрок вышел из мира в главное меню (открытый мир удалять нельзя).
 * Удаляются ТОЛЬКО папки с точным именем FRun-<число>[-<число>].
 */
public final class Worlds {
    private static final Pattern NAME = Pattern.compile("^FRun-\\d+(-\\d+)?$");

    private static boolean pending;
    private static int idleTicks;

    private Worlds() {}

    /** Пометить: удалить миры FRun, когда это станет безопасно. */
    public static void markForDelete() { pending = true; idleTicks = 0; }

    public static void tick(Minecraft mc) {
        if (!pending) return;
        // ждём, пока игрок не в мире, мир не грузится и сервер остановлен (иначе удалим живой мир)
        var scr = Mc.currentScreen();
        String sn = scr == null ? "" : scr.getClass().getSimpleName();
        boolean safeScreen = sn.equals("TitleScreen") || sn.startsWith("Lobby");
        if (mc.player != null || mc.getSingleplayerServer() != null || !safeScreen) { idleTicks = 0; return; }
        if (++idleTicks < 60) return;   // ~3 секунды после выхода из мира: сохранение уже завершено
        pending = false;
        idleTicks = 0;
        Thread t = new Thread(Worlds::deleteAll, "FRun-WorldCleanup");
        t.setDaemon(true);
        t.start();
    }

    private static void deleteAll() {
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves");
        if (!Files.isDirectory(saves)) return;
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(saves)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p) || Files.isSymbolicLink(p)) continue;
                if (!NAME.matcher(p.getFileName().toString()).matches()) continue;
                if (deleteRecursively(p)) n++;
            }
        } catch (IOException e) {
            FriendRunClient.LOG.warn("FRun: не удалось просмотреть saves: {}", e.toString());
        }
        if (n > 0) FriendRunClient.LOG.info("FRun: удалено одноразовых миров: {}", n);
    }

    private static boolean deleteRecursively(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            return false;
        }
        return !Files.exists(dir);
    }
}
