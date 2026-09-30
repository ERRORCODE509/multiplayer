package matlabmaster.multiplayer.launcher;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The server instance's own saves folder (saves-server next to the game's saves): the world lives there, as a
 * copy of one of the player's saves, so the server's autosaves never overwrite the player's own campaign and
 * the player's game (a client) never overwrites the world.
 */
public class ServerWorld {
    public final Path playerSaves;
    public final Path serverSaves;
    public final Path serverLogs;

    public ServerWorld(GameInstall install) {
        this.playerSaves = install.savesDir();
        this.serverSaves = playerSaves.resolveSibling("saves-server");
        this.serverLogs = install.root.resolve("logs-server");
    }

    /** The player's saves (folders holding a campaign.xml), newest first. */
    public List<String> playerSaveNames() throws IOException {
        return saveNames(playerSaves);
    }

    public boolean serverHas(String save) {
        return Files.isRegularFile(serverSaves.resolve(save).resolve("campaign.xml"));
    }

    /**
     * Makes sure the server's folder holds this world. An existing server copy has the server's own progress,
     * so it's only replaced when asked. Returns what was done, for the log.
     */
    public String prepare(String save, boolean replace) throws IOException {
        Files.createDirectories(serverSaves);
        Files.createDirectories(serverLogs);
        Path target = serverSaves.resolve(save);
        if (serverHas(save) && !replace) {
            return "Using the server's own copy of " + save + " (it has the world's progress from earlier sessions)";
        }
        if (Files.exists(target)) deleteTree(target);
        copyTree(playerSaves.resolve(save), target);
        return "Copied " + save + " from your saves into the server's saves (" + serverSaves + ")";
    }

    static List<String> saveNames(Path dir) throws IOException {
        List<Path> saves = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) if (Files.isRegularFile(p.resolve("campaign.xml"))) saves.add(p);
        }
        saves.sort(Comparator.comparing((Path p) -> p.resolve("campaign.xml").toFile().lastModified()).reversed());
        List<String> names = new ArrayList<>();
        for (Path p : saves) names.add(p.getFileName().toString());
        return names;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> all = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            all.forEach(paths::add);
            paths.sort(Comparator.reverseOrder()); //children before their folder
            for (Path p : paths) Files.delete(p);
        }
    }
}
