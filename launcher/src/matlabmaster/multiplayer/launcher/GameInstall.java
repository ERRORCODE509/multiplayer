package matlabmaster.multiplayer.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Starsector installation and the exact way it starts the game, read from the game's own files, so a server
 * instance starts the same way the player's game does (same Java, memory flags, classpath, working folder).
 *
 * Windows: the install folder has vmparams ("java.exe" followed by every JVM argument) and starsector.exe
 * runs jre\bin\java.exe with them from starsector-core\. Linux: starsector.sh in the install folder runs
 * ./jre_linux/bin/java with them from the install folder.
 */
public class GameInstall {
    public enum Os { WINDOWS, LINUX }

    public final Path root;
    public final Os os;
    /** The folder the game runs from (where its jars are). */
    public final Path workDir;
    public final Path java;
    /** Every JVM argument, in order, without the main class. */
    public final List<String> jvmArgs;
    public final String mainClass;

    private GameInstall(Path root, Os os, Path workDir, Path java, List<String> jvmArgs, String mainClass) {
        this.root = root;
        this.os = os;
        this.workDir = workDir;
        this.java = java;
        this.jvmArgs = jvmArgs;
        this.mainClass = mainClass;
    }

    /** The install at this folder, or one above it (the launcher lives in mods/&lt;mod&gt;/launcher). */
    public static GameInstall find(Path start) throws IOException {
        for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("vmparams")) && Files.isRegularFile(p.resolve("starsector-core/starfarer.api.jar"))) {
                return windows(p);
            }
            if (Files.isRegularFile(p.resolve("starsector.sh")) && Files.isRegularFile(p.resolve("starfarer.api.jar"))) {
                return linux(p);
            }
        }
        throw new IOException("No Starsector installation found at or above " + start.toAbsolutePath()
                + " (looked for vmparams + starsector-core, or starsector.sh). macOS isn't supported yet.");
    }

    static GameInstall windows(Path root) throws IOException {
        List<String> tokens = new ArrayList<>(Arrays.asList(read(root.resolve("vmparams")).trim().split("\\s+")));
        tokens.remove(0); //"java.exe"
        String main = tokens.remove(tokens.size() - 1);
        return new GameInstall(root, Os.WINDOWS, root.resolve("starsector-core"), root.resolve("jre/bin/java.exe"), tokens, main);
    }

    static GameInstall linux(Path root) throws IOException {
        //one command split over lines ending in "\"
        String script = read(root.resolve("starsector.sh")).replaceAll("\\\\\\r?\\n", " ");
        Matcher m = Pattern.compile("(\\S*/bin/java)\\s+(.*)").matcher(script);
        if (!m.find()) throw new IOException("Couldn't find the java command in " + root.resolve("starsector.sh"));
        List<String> tokens = new ArrayList<>();
        for (String t : m.group(2).trim().split("\\s+")) {
            if (t.startsWith("\"$") || t.startsWith("$")) continue; //"$@" and the like: arguments passed to the script
            tokens.add(t);
        }
        String main = tokens.remove(tokens.size() - 1);
        return new GameInstall(root, Os.LINUX, root, root.resolve(m.group(1)).normalize(), tokens, main);
    }

    /** Where the player's saves are, as the game is told (-Dcom.fs.starfarer.settings.paths.saves). */
    public Path savesDir() {
        for (String a : jvmArgs) {
            if (a.startsWith("-Dcom.fs.starfarer.settings.paths.saves=")) {
                return workDir.resolve(a.substring(a.indexOf('=') + 1).replace("\\\\", "/").replace('\\', '/')).normalize();
            }
        }
        return root.resolve("saves");
    }

    /** The heap the game is given (-Xmx), in MB; 2048 if not found. */
    public int memoryMb() {
        for (String a : jvmArgs) {
            Matcher m = Pattern.compile("-Xmx(\\d+)([mMgG])").matcher(a);
            if (m.matches()) return Integer.parseInt(m.group(1)) * (m.group(2).equalsIgnoreCase("g") ? 1024 : 1);
        }
        return 2048;
    }

    /**
     * The command for a server instance: the game's own command, with its own saves and logs folders and heap
     * size, started straight into the game (no launcher window: -DlaunchDirect, read by StarfarerLauncher) in a
     * small window without sound, told to host (-Dmultiplayer.serverMode, read by the mod) and to load the world's
     * save from the title screen by itself (-Dmultiplayer.autoLoadSave, the mod's AutoLoadPlugin; null: no).
     */
    public List<String> serverCommand(Path serverSaves, Path serverLogs, int memoryMb, int port, String resolution, Path agentJar, String save) {
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        if (agentJar != null) cmd.add("-javaagent:" + agentJar.toAbsolutePath());
        for (String a : jvmArgs) {
            if (a.startsWith("-Xmx")) a = "-Xmx" + memoryMb + "m";
            else if (a.startsWith("-Xms")) a = "-Xms" + memoryMb + "m";
            else if (a.startsWith("-Dcom.fs.starfarer.settings.paths.saves=")) a = "-Dcom.fs.starfarer.settings.paths.saves=" + serverSaves.toAbsolutePath();
            else if (a.startsWith("-Dcom.fs.starfarer.settings.paths.logs=")) a = "-Dcom.fs.starfarer.settings.paths.logs=" + serverLogs.toAbsolutePath();
            cmd.add(a);
        }
        cmd.add("-DlaunchDirect=true");
        cmd.add("-DstartRes=" + resolution);
        cmd.add("-DstartFS=false");
        cmd.add("-DstartSound=false");
        cmd.add("-Dmultiplayer.serverMode=true");
        cmd.add("-Dmultiplayer.port=" + port);
        if (save != null) cmd.add("-Dmultiplayer.autoLoadSave=" + serverSaves.resolve(save).toAbsolutePath());
        cmd.add(mainClass);
        return cmd;
    }

    /**
     * The player's own game, started exactly as the game starts itself (its launcher window included), plus the
     * multiplayer agent, so that hosting from it ("host current game") also spawns AI fleets around every player.
     */
    public List<String> playerCommand(Path agentJar) {
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        if (agentJar != null) cmd.add("-javaagent:" + agentJar.toAbsolutePath());
        cmd.addAll(jvmArgs);
        cmd.add(mainClass);
        return cmd;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
