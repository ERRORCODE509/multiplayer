package matlabmaster.multiplayer.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.characters.FullName;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.UserError;
import matlabmaster.multiplayer.server.Server;
import matlabmaster.multiplayer.client.Client;
import matlabmaster.multiplayer.client.TitleJoin;

public class UI extends JFrame {
    private JTextArea logArea;
    private JButton actionButton; // Utilisé pour Join
    private JButton hostDedicatedButton;
    private JButton hostCurrentButton;

    private JTextField ipField;
    private JTextField portField;
    private JTextField userField;
    private JPasswordField passwordField;
    private JButton characterButton;
    /** The character for a new game made by a login from the title screen; a null portrait is the gender's first. */
    private volatile FullName.Gender characterGender = FullName.Gender.MALE;
    private volatile String characterPortrait;
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
        //a new game made for a first login from the title screen (TitleJoin): the character's gender and portrait
        characterButton = new JButton("CHARACTER");
        characterButton.setEnabled(false);
        characterButton.setToolTipText("Your character's gender and portrait, if this login makes you a new game (your first login to a server from the title screen)");
        characterButton.addActionListener(e -> pickCharacter());
        configPanel.add(characterButton);

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
        characterButton.setEnabled(isJoin);
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
                            //until the port is open: the first time, making the server's certificate takes a while
                            for (int i = 0; i < 100 && server.isRunning && !server.isListening(); i++) Thread.sleep(100);
                            if (!server.isListening()) { //it couldn't host (the log says why), or it's very slow
                                if (server.isRunning) MultiplayerLog.log().warn("The server didn't open its port within 10 seconds: this game didn't join it. Stop it and host again");
                                return;
                            }
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
            String ip = ipField.getText();
            String user = userField.getText().trim();
            String password = new String(passwordField.getPassword());
            if (Global.getCurrentState() == GameState.TITLE) {
                //no game yet: log in, and the game for this server is loaded or made, then joins (TitleJoin)
                new Thread(() -> {
                    try {
                        TitleJoin.login(client, ip, connectPort, user, password, characterGender, characterPortrait);
                    } catch (UserError e) {
                        MultiplayerLog.log().warn(e.getMessage());
                    } catch (Exception ex) {
                        MultiplayerLog.log().error("UNABLE TO LOG IN. " + ex.toString(), ex);
                    }
                }).start();
                return;
            }
            join(ip, connectPort, user, password);
        } else {
            stopAll();
        }

    }

    /** Joins a server from the loaded game: CONNECT, or a login from the title screen once its game runs (TitleJoin). */
    public void join(String ip, int port, String user, String password) {
        new Thread(() -> {
            try {
                client.user = user;
                client.password = password;
                client.connect(ip, port);
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
    }

    /**
     * The character a login from the title screen makes, if it makes a new game: gender and portrait, as the
     * new-game screen offers them (the player faction's portraits). The pictures need the multiplayer agent (the
     * game forbids mods reading the files); without it, their names.
     */
    private void pickCharacter() {
        JDialog dialog = new JDialog(this, "Character for a new game", true);
        JComboBox<String> gender = new JComboBox<>(new String[]{"Male", "Female"});
        gender.setSelectedIndex(characterGender == FullName.Gender.FEMALE ? 1 : 0);
        JLabel picture = new JLabel("", SwingConstants.CENTER);
        picture.setPreferredSize(new Dimension(160, 160));
        JLabel counter = new JLabel("", SwingConstants.CENTER);
        java.util.List<String> portraits = new java.util.ArrayList<>();
        int[] index = {0};
        java.util.Map<String, Icon> icons = new java.util.HashMap<>();
        Runnable show = () -> {
            if (portraits.isEmpty()) {
                picture.setIcon(null);
                picture.setText("No portraits");
                counter.setText("");
                return;
            }
            String portrait = portraits.get(index[0]);
            Icon icon = icons.computeIfAbsent(portrait, p -> {
                Object image = TitleJoin.portraitImage(p);
                return image instanceof Image ? new ImageIcon(((Image) image).getScaledInstance(160, 160, Image.SCALE_SMOOTH)) : null;
            });
            picture.setIcon(icon);
            picture.setText(icon == null ? portrait.substring(portrait.lastIndexOf('/') + 1) : "");
            counter.setText((index[0] + 1) + " / " + portraits.size());
        };
        Runnable list = () -> {
            portraits.clear();
            try {
                portraits.addAll(TitleJoin.portraits(gender.getSelectedIndex() == 1 ? FullName.Gender.FEMALE : FullName.Gender.MALE));
            } catch (RuntimeException e) {
                MultiplayerLog.log().error("Couldn't list the portraits", e);
            }
            index[0] = Math.max(0, portraits.indexOf(characterPortrait));
            show.run();
        };
        gender.addActionListener(e -> list.run());
        JButton previous = new JButton("<");
        JButton next = new JButton(">");
        previous.addActionListener(e -> {
            if (portraits.isEmpty()) return;
            index[0] = (index[0] + portraits.size() - 1) % portraits.size();
            show.run();
        });
        next.addActionListener(e -> {
            if (portraits.isEmpty()) return;
            index[0] = (index[0] + 1) % portraits.size();
            show.run();
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            characterGender = gender.getSelectedIndex() == 1 ? FullName.Gender.FEMALE : FullName.Gender.MALE;
            characterPortrait = portraits.isEmpty() ? null : portraits.get(index[0]);
            MultiplayerLog.log().info("New-game character: " + characterGender.name().toLowerCase() + ", portrait " + characterPortrait);
            dialog.dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dialog.dispose());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Gender: "));
        top.add(gender);
        JPanel middle = new JPanel(new BorderLayout(5, 5));
        middle.add(previous, BorderLayout.WEST);
        middle.add(picture, BorderLayout.CENTER);
        middle.add(next, BorderLayout.EAST);
        middle.add(counter, BorderLayout.SOUTH);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(ok);
        bottom.add(cancel);
        JPanel content = new JPanel(new BorderLayout(5, 5));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));
        content.add(top, BorderLayout.NORTH);
        content.add(middle, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        list.run();
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void stopAll() {
        if (server.isRunning) server.stop();
        if (client.isConnected() && !client.isSelfHosted) {
            //our record's last state goes to the server first (ClientScripts sends it, then disconnects on the game
            //thread). That thread doesn't run during a battle: after 2 seconds we leave without it
            client.requestDisconnectAfterRecord();
            new Thread(() -> {
                try {
                    for (int i = 0; i < 20 && client.isConnected(); i++) Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                if (client.isConnected()) {
                    MultiplayerLog.log().warn("The game didn't send our record within 2 seconds (in a battle?): disconnected without it");
                    client.disconnect();
                }
            }, "Client-Disconnect").start();
        } else if (client.isConnected()) {
            client.disconnect();
        }
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