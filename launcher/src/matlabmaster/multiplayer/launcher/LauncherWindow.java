package matlabmaster.multiplayer.launcher;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** The launcher's window: pick the world save, start and stop the server instance, watch its output. */
public class LauncherWindow extends JFrame {
    private final JTextArea log = new JTextArea();
    private final JComboBox<String> saves = new JComboBox<>();
    private final JCheckBox replace = new JCheckBox("Replace the server's copy with this save");
    private final JTextField port = new JTextField(String.valueOf(MultiplayerLauncher.DEFAULT_PORT), 6);
    private final JTextField memory = new JTextField(6);
    private final JButton start = new JButton("START SERVER");
    private final JButton stop = new JButton("FORCE STOP");
    private final JButton startGame = new JButton("START MY GAME");
    private GameInstall install;
    private ServerWorld world;
    private volatile Process server;

    public LauncherWindow(Path gameFolder) {
        super("Starsector Multiplayer - server launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(820, 560);
        setLocationRelativeTo(null);

        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT));
        settings.add(new JLabel("World save:")); settings.add(saves);
        settings.add(new JLabel(" Port:")); settings.add(port);
        settings.add(new JLabel(" Memory (MB):")); settings.add(memory);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT));
        options.add(replace);
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(settings); top.add(options);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel buttons = new JPanel(new GridLayout(1, 0, 10, 0));
        buttons.add(start); buttons.add(stop); buttons.add(startGame);
        stop.setEnabled(false);

        JPanel main = new JPanel(new BorderLayout(10, 10));
        main.setBorder(new EmptyBorder(10, 10, 10, 10));
        main.add(top, BorderLayout.NORTH);
        main.add(new JScrollPane(log), BorderLayout.CENTER);
        main.add(buttons, BorderLayout.SOUTH);
        add(main);

        start.addActionListener(e -> startServer());
        stop.addActionListener(e -> stopServer());
        startGame.addActionListener(e -> startPlayerGame());
        saves.addActionListener(e -> updateReplaceDefault());

        try {
            install = GameInstall.find(gameFolder);
            world = new ServerWorld(install);
            memory.setText(String.valueOf(install.memoryMb()));
            for (String s : world.playerSaveNames()) saves.addItem(s);
            updateReplaceDefault();
            say("Game: " + install.root + " (" + install.os + ")");
            say("The server keeps its world in " + world.serverSaves + ", apart from your own saves.");
            say("How it works: START SERVER opens a second Starsector in a small window. Load the world save there");
            say("(Load Game); it starts hosting by itself. Then play in your own game and join 127.0.0.1 on the port.");
            say("Friends join your IP on the same port (forward it on your router for internet play).");
            if (saves.getItemCount() == 0) say("No saves found in " + world.playerSaves + ": start a campaign first.");
            say(MultiplayerLauncher.agentJar() != null
                    ? "Multiplayer agent found: AI fleets will spawn around every player, not only near the server."
                    : "MultiplayerAgent.jar is missing from this folder: AI fleets will only spawn near the server's own fleet.");
        } catch (Exception ex) {
            say("ERROR: " + ex.getMessage());
            start.setEnabled(false);
            startGame.setEnabled(false);
        }
    }

    private void updateReplaceDefault() {
        String save = (String) saves.getSelectedItem();
        boolean has = save != null && world != null && world.serverHas(save);
        replace.setEnabled(has);
        replace.setSelected(false);
        replace.setToolTipText(has ? "The server already has this world, with its own progress; tick to start over from your save" : null);
    }

    private void startServer() {
        String save = (String) saves.getSelectedItem();
        if (save == null) { say("Pick a world save first."); return; }
        int p, mem;
        try {
            p = Integer.parseInt(port.getText().trim());
            mem = Integer.parseInt(memory.getText().trim());
        } catch (NumberFormatException ex) {
            say("Port and memory must be numbers.");
            return;
        }
        start.setEnabled(false);
        new Thread(() -> {
            try {
                say(world.prepare(save, replace.isSelected()));
                List<String> cmd = install.serverCommand(world.serverSaves, world.serverLogs, mem, p, MultiplayerLauncher.DEFAULT_RESOLUTION, MultiplayerLauncher.agentJar());
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(install.workDir.toFile()).redirectErrorStream(true);
                server = pb.start();
                SwingUtilities.invokeLater(() -> stop.setEnabled(true));
                say("Server instance started. In its window: Load Game, pick " + save + ".");
                try (BufferedReader r = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("multiplayer") || line.contains("ERROR") || line.contains("Exception") || line.contains("SERVER")) say("[server] " + line);
                    }
                }
                say("Server instance closed (exit code " + server.waitFor() + "). Its log: " + world.serverLogs);
            } catch (Exception ex) {
                say("ERROR starting the server: " + ex.getMessage());
            } finally {
                server = null;
                SwingUtilities.invokeLater(() -> { start.setEnabled(true); stop.setEnabled(false); });
            }
        }, "server-instance").start();
    }

    private void stopServer() {
        Process p = server;
        if (p == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Force-stopping skips saving: the world loses anything since its last save.\n"
                + "To keep it, save in the server's window first (or quit from its menu).\n\nForce-stop now?",
                "Force stop", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            p.destroy();
            say("Server instance stopped.");
        }
    }

    private void startPlayerGame() {
        try {
            //the game's own command (as starsector.exe / .sh would run it) plus the agent, from the game's folder
            new ProcessBuilder(install.playerCommand(MultiplayerLauncher.agentJar())).directory(install.workDir.toFile()).start();
            say("Started your own game. Once in the campaign, JOIN 127.0.0.1 on port " + port.getText().trim() + " in the multiplayer window.");
        } catch (Exception ex) {
            say("ERROR starting your game: " + ex.getMessage());
        }
    }

    private void say(String line) {
        SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }
}
