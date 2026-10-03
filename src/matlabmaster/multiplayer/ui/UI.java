package matlabmaster.multiplayer.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.UserError;
import matlabmaster.multiplayer.server.Server;
import matlabmaster.multiplayer.client.Client;

public class UI extends JFrame {
    private JTextArea logArea;
    private JButton actionButton; // Utilisé pour Join
    private JButton hostDedicatedButton;
    private JButton hostCurrentButton;

    private JTextField ipField;
    private JTextField portField;
    private JTextField userField;
    private JPasswordField passwordField;
    private JComboBox<String> modeSelector;
    private JLabel serverTimeLabel;
    private JLabel playersLabel;
    /** The server time last shown (see setServerTime); null after disconnecting, so it's shown again. */
    private volatile String shownTime;
    private final DefaultListModel<String> playersModel = new DefaultListModel<>();

    private final Server server;
    private final Client client;
    private boolean isRunning = false;


    public UI(Server serverInstance, Client clientInstance) {
        this.server = serverInstance;
        this.client = clientInstance;

        // Existing client listener
        client.addListener(new Client.ClientListener() {
            @Override
            public void onDisconnected() {
                MultiplayerLog.log().debug("UI onDisconnected() callback triggered!");
                serverTimeLabel.setText("Disconnected");
                shownTime = null;
                try {
                    isRunning = false;
                    updateButtonStyle();
                    MultiplayerLog.log().debug("updateButtonStyle() completed");

                } catch (Exception e) {
                    MultiplayerLog.log().error("Exception in onDisconnected: " + e.getMessage(), e);
                }
            }
            @Override
            public void onMessageReceived(String msg) {}
        });

        // ADD THIS: Server listener
        server.setListener(new Server.ServerListener() {
            @Override
            public void onServerStopped() {
                shownTime = null;
                isRunning = false;
                updateButtonStyle();
            }
        });

        setTitle("Starsector Multiplayer");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(900, 550);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        mainPanel.setBackground(new Color(40, 42, 54));

        // --- BARRE DE CONFIGURATION ---
        JPanel configPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        modeSelector = new JComboBox<>(new String[]{"HOST MODE", "JOIN MODE"});
        ipField = new JTextField("127.0.0.1", 10);
        ipField.setEnabled(false);
        portField = new JTextField("20603", 6);

        modeSelector.addActionListener(e -> toggleMode());

        configPanel.add(new JLabel("Mode: ")); configPanel.add(modeSelector);
        configPanel.add(new JLabel(" IP: ")); configPanel.add(ipField);
        configPanel.add(new JLabel(" Port: ")); configPanel.add(portField);
        //the server's account (joining): a new username makes one
        userField = new JTextField(10);
        passwordField = new JPasswordField(10);
        userField.setEnabled(false);
        passwordField.setEnabled(false);
        configPanel.add(new JLabel(" User: ")); configPanel.add(userField);
        configPanel.add(new JLabel(" Password: ")); configPanel.add(passwordField);
        
        // --- SERVER TIME CLOCK ---
        serverTimeLabel = new JLabel("Disconnected");
        serverTimeLabel.setForeground(Color.BLACK);
        serverTimeLabel.setFont(new Font(serverTimeLabel.getFont().getName(), Font.BOLD, 12));
        configPanel.add(new JLabel(" | "));
        configPanel.add(serverTimeLabel);

        // --- CONSOLE ---
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(20, 20, 25));
        logArea.setForeground(new Color(139, 233, 253));
        JScrollPane scroll = new JScrollPane(logArea);

        // --- WHO'S CONNECTED ---
        JPanel playersPanel = new JPanel(new BorderLayout(0, 5));
        playersPanel.setOpaque(false);
        playersPanel.setPreferredSize(new Dimension(170, 0));
        playersLabel = new JLabel("Players: -");
        playersLabel.setForeground(new Color(139, 233, 253));
        JList<String> playersList = new JList<>(playersModel);
        playersList.setBackground(new Color(20, 20, 25));
        playersList.setForeground(new Color(139, 233, 253));
        playersPanel.add(playersLabel, BorderLayout.NORTH);
        playersPanel.add(new JScrollPane(playersList), BorderLayout.CENTER);

        // --- ZONE DES BOUTONS (SUD) ---
        JPanel buttonPanel = new JPanel(new GridLayout(1, 0, 10, 0));
        buttonPanel.setOpaque(false);

        hostDedicatedButton = new JButton("HOST AS DEDICATED");
        hostCurrentButton = new JButton("HOST CURRENT GAME");
        // Remplace tes couleurs par des teintes un peu moins saturées qui font ressortir le blanc
        actionButton = new JButton("CONNECT"); // Pour le mode Join
        actionButton.setVisible(false);

        // Actions
        hostDedicatedButton.addActionListener(e -> startHost(false));
        hostCurrentButton.addActionListener(e -> startHost(true));
        actionButton.addActionListener(e -> startJoin());

        buttonPanel.add(hostDedicatedButton);
        buttonPanel.add(hostCurrentButton);
        buttonPanel.add(actionButton);

        mainPanel.add(configPanel, BorderLayout.NORTH);
        mainPanel.add(scroll, BorderLayout.CENTER);
        mainPanel.add(playersPanel, BorderLayout.EAST);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel);
        updateButtonStyle();
        MultiplayerLog.setUILogSink(this::updateLogArea);
    }

    private void toggleMode() {
        boolean isJoin = modeSelector.getSelectedItem().equals("JOIN MODE");
        ipField.setEnabled(isJoin);
        userField.setEnabled(isJoin);
        passwordField.setEnabled(isJoin);
        hostDedicatedButton.setVisible(!isJoin);
        hostCurrentButton.setVisible(!isJoin);
        actionButton.setVisible(isJoin);
    }

    private void startHost(boolean asCurrentGame) {
        try{
            if (!isRunning) {
                int port = parsePort();
                if (port <= 0) return;
                server.setPort(port);
                //"host as dedicated": nobody plays in this game, it only holds the world
                server.setDedicated(!asCurrentGame);
                server.start();
                isRunning = true;
                final int connectPort = port;
                MultiplayerLog.log().info("SERVER STARTED AS " + (asCurrentGame ? "HOSTED GAME" : "DEDICATED"));
                if (asCurrentGame) {
                    // On lance la connexion client dans un thread séparé avec un petit délai
                    new Thread(() -> {
                        try {
                            Thread.sleep(200); // Pause de 200ms pour laisser le port s'ouvrir
                            client.isSelfHosted = true;
                            client.localServer = server;
                            client.connect("127.0.0.1", connectPort);
                            updateButtonStyle();
                        } catch (Exception ex) {
                            MultiplayerLog.log().error("FAILURE TO AUTO CONNECT : " + ex.getMessage());
                            client.isSelfHosted = false;
                            // Si l'auto-connexion échoue, on peut choisir d'arrêter le serveur ou pas
                        }
                    }).start();
                }
                updateButtonStyle();
            } else {
                stopAll();
            }
        }catch (UserError e){
            MultiplayerLog.log().warn(e.getMessage());
        }catch (Exception e){
            MultiplayerLog.log().error("server crashed", e);
        }

    }

    private void startJoin() {
        if (!isRunning) {
            int port = parsePort();
            if (port <= 0) return;
            final int connectPort = port;
            new Thread(() -> {
                try {
                    client.user = userField.getText().trim();
                    client.password = new String(passwordField.getPassword());
                    client.connect(ipField.getText(), connectPort);
                    isRunning = true;
                    updateButtonStyle();
                }catch (UserError e){
                    MultiplayerLog.log().warn(e.getMessage());
                }
                catch (Exception ex) {
                    MultiplayerLog.log().error("UNABLE TO CONNECT. " + ex.toString(), ex);
                    client.disconnect();
                }
            }).start();
        } else {
            stopAll();
        }

    }

    private void stopAll() {
        if (server.isRunning) server.stop();
        if (client.isConnected()) client.disconnect();
        isRunning = false;
        updateButtonStyle();
    }

    /** The server was started without the buttons (a server instance hosts by itself): show it as running. */
    public void showServerRunning() {
        isRunning = true;
        SwingUtilities.invokeLater(() -> modeSelector.setSelectedItem("HOST MODE"));
        updateButtonStyle();
    }

    private void updateButtonStyle() {
        SwingUtilities.invokeLater(() -> {
            boolean serverUp = server.isRunning;
            boolean clientUp = client.isConnected();

            if (!serverUp && !clientUp) {
                // Tout est arrêté
                hostDedicatedButton.setText("HOST AS DEDICATED");
                hostDedicatedButton.setBackground(new Color(80, 250, 123));
                hostCurrentButton.setText("HOST CURRENT GAME");
                hostCurrentButton.setBackground(new Color(80, 250, 123));
                actionButton.setText("CONNECT");
                actionButton.setBackground(new Color(80, 250, 123));
                modeSelector.setEnabled(true);
                portField.setEnabled(true);
                isRunning = false;
            } else {
                // Quelque chose tourne
                hostDedicatedButton.setText("STOP SERVER");
                hostDedicatedButton.setBackground(new Color(255, 85, 85));
                hostCurrentButton.setText("STOP HOST");
                hostCurrentButton.setBackground(new Color(255, 85, 85));
                actionButton.setText("DISCONNECT");
                actionButton.setBackground(new Color(255, 85, 85));
                modeSelector.setEnabled(false);
                portField.setEnabled(false);
                isRunning = true;
            }
        });
    }

    private void updateLogArea(String t) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(t);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /** @return the port (1-65535) or 0 if invalid */
    private int parsePort() {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                MultiplayerLog.log().error("Port must be between 1 and 65535.");
                return 0;
            }
            return port;
        } catch (NumberFormatException e) {
            MultiplayerLog.log().error("Invalid port: " + portField.getText());
            return 0;
        }
    }

    /** The server's world stopped (host in a dialog or menu, "host current game" mode) or runs again. */
    public void setWorldPaused(boolean paused) {
        SwingUtilities.invokeLater(() -> {
            String text = serverTimeLabel.getText().replace(" (paused by the host)", "");
            serverTimeLabel.setText(paused ? text + " (paused by the host)" : text);
        });
    }

    /** Who's connected, by name (any thread); null when connected to nothing. */
    public void setPlayers(java.util.Collection<String> names) {
        java.util.List<String> sorted = names == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        SwingUtilities.invokeLater(() -> {
            playersModel.clear();
            for (String name : sorted) playersModel.addElement(name);
            playersLabel.setText(names == null ? "Players: -" : "Players: " + sorted.size());
        });
    }

    public void showUI() { SwingUtilities.invokeLater(() -> setVisible(true)); }

    /**
     * Sets the server time clock display.
     * Format: cYYY MM DD (e.g., c207 12 18)
     * @param timestamp long
     */
    public void setServerTime(long timestamp) {
        CampaignClockAPI clock = Global.getSector().getClock().createClock(timestamp);

        int year  = clock.getCycle();
        int month = clock.getMonth();
        int day   = clock.getDay();
        String shortMonth = clock.getShortMonthString();
        int hour = clock.getHour();
        String formattedTime = String.format("%02d %02d %s %03d : %02d:00", day, month, shortMonth, year, hour);
        //sent 20 times a second, and it only changes every game hour
        if (formattedTime.equals(shownTime)) return;
        shownTime = formattedTime;

        SwingUtilities.invokeLater(() -> {
            boolean paused = serverTimeLabel.getText().endsWith(" (paused by the host)");
            serverTimeLabel.setText("Server Time: " + formattedTime + (paused ? " (paused by the host)" : ""));
        });
    }
}