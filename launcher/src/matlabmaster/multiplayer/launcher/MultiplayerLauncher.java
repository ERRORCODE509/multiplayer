package matlabmaster.multiplayer.launcher;

import javax.swing.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Starts a Starsector server instance for the multiplayer mod: a second copy of the game, started straight into
 * the game in a small window, that holds the world and hosts it. It runs outside the game (no mod sandbox), so it
 * can do what the mod can't: build the game's command line, copy a save into the server's own folder, and start
 * and stop the instance.
 *
 * Usage: MultiplayerLauncher                        the launcher window
 *        MultiplayerLauncher --dry-run [--game DIR] [--save NAME] [--port N] [--memory MB]
 *                                                   print what would be done and the command, start nothing
 */
public class MultiplayerLauncher {
    public static final int DEFAULT_PORT = 20603;
    public static final String DEFAULT_RESOLUTION = "1024x576";

    /** MultiplayerAgent.jar next to the launcher's own jar, or null if it isn't there (then fleets spawn as in vanilla). */
    public static Path agentJar() {
        try {
            Path self = Paths.get(MultiplayerLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path agent = (java.nio.file.Files.isDirectory(self) ? self : self.getParent()).resolve("MultiplayerAgent.jar");
            return java.nio.file.Files.isRegularFile(agent) ? agent : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Path game = Paths.get(System.getProperty("user.dir"));
        String save = null;
        int port = DEFAULT_PORT, memory = -1;
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run": dryRun = true; break;
                case "--game": game = Paths.get(args[++i]); break;
                case "--save": save = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--memory": memory = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (!dryRun) {
            Path start = game;
            SwingUtilities.invokeLater(() -> new LauncherWindow(start).setVisible(true));
            return;
        }
        GameInstall install = GameInstall.find(game);
        ServerWorld world = new ServerWorld(install);
        System.out.println("Game: " + install.root + " (" + install.os + ")");
        System.out.println("Runs from: " + install.workDir);
        System.out.println("Java: " + install.java);
        System.out.println("Player saves: " + world.playerSaves + " " + world.playerSaveNames());
        System.out.println("Server saves: " + world.serverSaves);
        System.out.println("Server logs: " + world.serverLogs);
        if (save != null) {
            System.out.println(world.serverHas(save) ? "Would use the server's existing copy of " + save : "Would copy " + save + " into the server's saves");
        }
        System.out.println("Agent: " + (agentJar() == null ? "not found (AI fleets will only spawn near the server's own fleet)" : agentJar()));
        List<String> cmd = install.serverCommand(world.serverSaves, world.serverLogs, memory > 0 ? memory : install.memoryMb(), port, DEFAULT_RESOLUTION, agentJar());
        System.out.println("Command (" + cmd.size() + " parts):");
        for (String c : cmd) System.out.println("  " + c);
    }
}
