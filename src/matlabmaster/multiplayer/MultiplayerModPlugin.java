package matlabmaster.multiplayer;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import matlabmaster.multiplayer.client.Client;
import matlabmaster.multiplayer.client.ClientScripts;
import matlabmaster.multiplayer.listeners.MultiplayerWatchdog;
import matlabmaster.multiplayer.server.Server;
import matlabmaster.multiplayer.server.ServerScripts;
import matlabmaster.multiplayer.ui.UI;

import java.util.Objects;


public class MultiplayerModPlugin extends BaseModPlugin {

    // Unique Client and Server Instance
    private static Server serverInstance;
    private static Client clientInstance;
    private static UI uiInstance;
    private static ClientScripts clientScriptsInstance;
    private static ServerScripts serverScriptsInstance;

    /**
     * Started by the multiplayer launcher as a server instance (-Dmultiplayer.serverMode=true): a game that only
     * holds the world. It hosts as dedicated, on -Dmultiplayer.port, as soon as a save is loaded.
     */
    public static final boolean SERVER_MODE = Boolean.getBoolean("multiplayer.serverMode");
    private static final int SERVER_MODE_PORT = Integer.getInteger("multiplayer.port", 20603);

    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        // Unique instantiation of client / server
        if (serverInstance == null) {
            serverInstance = new Server(20603);
        }
        if(clientInstance == null){
            clientInstance = new Client();
        }

        // CREATE UI ONLY ONCE
        if (uiInstance == null) {
            uiInstance = new UI(serverInstance, clientInstance);
            uiInstance.showUI();
            clientInstance.ui = uiInstance;
            new MultiplayerWatchdog(clientInstance, serverInstance).start();}

        MultiplayerLog.log().info("Multiplayer mod UI initialized");
        if (SERVER_MODE) {
            uiInstance.setTitle("Starsector Multiplayer - server instance (port " + SERVER_MODE_PORT + ")");
            MultiplayerLog.log().info("SERVER INSTANCE: load the world save (Load Game) and hosting starts by itself on port " + SERVER_MODE_PORT);
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        super.onGameLoad(newGame);

        if (clientScriptsInstance == null) { //ensure only one client script exist at any time
            clientScriptsInstance = new ClientScripts(clientInstance);
        }
        clientScriptsInstance.onGameLoad();
        Global.getSector().addTransientScript(clientScriptsInstance);

        if (serverScriptsInstance == null) {
            serverScriptsInstance = new ServerScripts(serverInstance);
        }
        Global.getSector().addTransientScript(serverScriptsInstance);
        MultiplayerLog.log().info("registered scripts");

        if (SERVER_MODE && !serverInstance.isRunning) {
            startServerInstance();
        }
    }

    /** Server mode: host the loaded save as dedicated, and keep running while the window isn't focused. */
    private void startServerInstance() {
        try {
            Global.getSettings().setBoolean("idleWhileWindowNotVisible", false);
            serverInstance.setPort(SERVER_MODE_PORT);
            serverInstance.setDedicated(true);
            serverInstance.start();
            uiInstance.showServerRunning();
            org.lwjgl.opengl.Display.setTitle("Starsector - multiplayer server (port " + SERVER_MODE_PORT + ")");
            MultiplayerLog.log().info("SERVER INSTANCE: hosting this save as dedicated on port " + SERVER_MODE_PORT);
        } catch (Exception e) {
            MultiplayerLog.log().error("SERVER INSTANCE: couldn't start hosting", e);
        }
    }

    @Override
    public void beforeGameSave() {
        super.beforeGameSave();
        //non authority clients have the sector scripts taken out, never let a save (autosaves included) lose them
        if (clientScriptsInstance != null) {
            clientScriptsInstance.restoreSectorScripts();
        }
        //a dedicated server hides its own player fleet from its world while hosting; not in the save
        if (serverScriptsInstance != null) {
            serverScriptsInstance.beforeGameSave();
        }
    }

    public static Server getServer() {
        return serverInstance;
    }

    public static Client getClient(){
        return clientInstance;
    }

    public static UI getUI(){
        return uiInstance;
    }
}