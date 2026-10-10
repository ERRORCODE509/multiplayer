package matlabmaster.multiplayer.client;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.SharedSettings;
import com.fs.starfarer.api.impl.campaign.NewGameDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.rulecmd.NGCAddStandardStartingScript;
import matlabmaster.multiplayer.MultiplayerLog;
import matlabmaster.multiplayer.UserError;
import matlabmaster.multiplayer.ui.UI;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logging in to a server from the title screen, with no new-game screens and no Load dialog:
 * 1. The window's CONNECT at the title screen (login, the window's thread): Client.checkLoginFromTitle checks the
 *    game version and mods and that the account would be let in, and gives the host's seed.
 * 2. The title screen's next frame (titleFrame, from AutoLoadPlugin: the game thread): this game's save for that
 *    server and account loads (remembered when it last joined, see joined), if it's still there and has the
 *    host's seed; else the multiplayer agent makes a new game (agent NewGameMaker), filled in here (fill): the
 *    host's seed, the username as the character's name, a default portrait and a stand-in start.
 * 3. That game's first campaign frame (gameLoaded): it joins, as CONNECT does in a loaded game.
 * The agent does the loading and making (the game forbids mods the reflection it takes): the launcher's START MY
 * GAME starts it. Without it, a game has to be loaded first, as before.
 */
public class TitleJoin {
    private static final String NEW_GAME_KEY = "multiplayer.newGame";
    private static final String LOAD_KEY = "multiplayer.loadSave";
    private static final String FIND_KEY = "multiplayer.findSave";
    private static final String CURRENT_KEY = "multiplayer.saveDirName";
    /** In the game's common folder: "<user>@<host>:<port>" -> {"save": save folder name, "seed"}. */
    private static final String LOGINS_FILE = "multiplayer_logins.json";
    /**
     * The stand-in start until the server keeps each player's fleet, cargo and character (STATUS.md plan, step 4):
     * vanilla's "mercenary" start with the tutorial skipped (rules.csv: onNewGameCreationStart, ngcMercPicked,
     * ngcNormalPicked, ngcSkipTutorialPicked). The tutorial is the Galatia story of one player's game.
     */
    private static final String[] START_SHIPS = {"hammerhead_Balanced", "drover_Starting", "centurion_Starting", "lasher_CS", "dram_Light"};
    private static final float START_CREDITS = 2000f + 18000f;

    /** A login whose game is still to be made or loaded. */
    private static class Login {
        final String host, user, password, seed;
        final int port;

        Login(String host, int port, String user, String password, String seed) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
            this.seed = seed;
        }
    }

    /** Checked, waiting for the title screen's next frame. */
    private static volatile Login waiting;
    /** Its game is being made or loaded: it joins once that game runs. */
    private static volatile Login joining;

    /** Whether the agent can load and make games here. */
    public static boolean available() {
        return System.getProperties().get(NEW_GAME_KEY) instanceof Function && System.getProperties().get(LOAD_KEY) instanceof Function
                && System.getProperties().get(FIND_KEY) instanceof Function && System.getProperties().get(CURRENT_KEY) instanceof Supplier;
    }

    /** The window's CONNECT at the title screen (not the game thread): the login is checked here, the game comes next frame. */
    public static void login(Client client, String host, int port) throws IOException {
        if (!available()) {
            throw new UserError("Logging in from the title screen needs the multiplayer agent: start the game with the multiplayer launcher (START MY GAME), or load a game first and connect from it");
        }
        if (waiting != null || joining != null) throw new UserError("Already logging in: wait for the game to load");
        String seed = client.checkLoginFromTitle(host, port);
        MultiplayerLog.log().info("Logged in to " + host + ":" + port + " as " + client.user + ": getting your game ready");
        waiting = new Login(host, port, client.user, client.password, seed);
    }

    /** Every title screen frame (AutoLoadPlugin, the game thread): loads or makes the game for a login that's waiting. */
    @SuppressWarnings("unchecked")
    public static void titleFrame() {
        Login login = waiting;
        if (login == null) return;
        waiting = null;
        //before the game loads: the game's onGameLoad comes while it does, and that's where it's picked up
        joining = login;
        String save = savedGame(login);
        String error;
        if (save != null) {
            MultiplayerLog.log().info("Loading your game for " + login.host + ":" + login.port + " (" + save + ")");
            error = ((Function<String, String>) System.getProperties().get(LOAD_KEY)).apply(save);
        } else {
            MultiplayerLog.log().info("No game of yours for " + login.host + ":" + login.port + " here: making one from the server's seed " + login.seed);
            if (Global.getSettings().isDevMode()) MultiplayerLog.log().warn("Devmode is on: the sector may not match the server's (devmode ignores the seed)");
            if (Global.getSettings().getModManager().isModEnabled("nexerelin")) {
                MultiplayerLog.log().warn("Nexerelin's new-game options are left at its defaults: the server's world must have been made with them too");
            }
            BiConsumer<Object, Object> filler = (data, dialog) -> fill((CharacterCreationData) data, (InteractionDialogAPI) dialog, login);
            error = ((Function<BiConsumer<Object, Object>, String>) System.getProperties().get(NEW_GAME_KEY)).apply(filler);
        }
        if (error != null) {
            joining = null;
            MultiplayerLog.log().error("Couldn't " + (save != null ? "load " + save : "make a new game") + " for " + login.host + ": " + error);
        }
    }

    /** onGameLoad: the game a login was waiting for runs; it joins on its first campaign frame (the window's CONNECT). */
    public static void gameLoaded(UI ui) {
        Login login = joining;
        if (login == null) return;
        joining = null;
        Global.getSector().addTransientScript(new EveryFrameScript() {
            private boolean done;

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public boolean runWhilePaused() {
                return true;
            }

            @Override
            public void advance(float amount) {
                if (done || Global.getCurrentState() != GameState.CAMPAIGN) return;
                done = true;
                ui.join(login.host, login.port, login.user, login.password);
            }
        });
    }

    /**
     * The server let us in (its reply to our hello, the game thread): this game's save is this account's game for
     * that server from now on, for the next login from the title screen.
     */
    @SuppressWarnings("unchecked")
    public static void joined(Client client) {
        if (client.isSelfHosted || client.host == null || client.user == null || client.user.trim().isEmpty() || !available()) return;
        String save = ((Supplier<String>) System.getProperties().get(CURRENT_KEY)).get();
        if (save == null) return;
        try {
            JSONObject logins = readLogins();
            String key = key(client.host, client.port, client.user);
            JSONObject entry = new JSONObject().put("save", save).put("seed", Global.getSector().getSeedString());
            JSONObject before = logins.optJSONObject(key);
            if (before != null && before.toString().equals(entry.toString())) return;
            logins.put(key, entry);
            Global.getSettings().writeTextFileToCommon(LOGINS_FILE, logins.toString(2));
            MultiplayerLog.log().info("This game (" + save + ") is " + client.user + "'s for " + client.host + ":" + client.port + ": logging in from the title screen loads it");
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't remember this game for " + client.host + ":" + client.port, e);
        }
    }

    /** The full path of this login's remembered save, if it's still there and was made from the host's seed; else null. */
    @SuppressWarnings("unchecked")
    private static String savedGame(Login login) {
        try {
            JSONObject entry = readLogins().optJSONObject(key(login.host, login.port, login.user));
            if (entry == null) return null;
            if (!login.seed.equals(entry.optString("seed"))) {
                MultiplayerLog.log().info("Your last game for " + login.host + " (" + entry.optString("save") + ") is from another world (seed "
                        + entry.optString("seed") + ", the server's is " + login.seed + ")");
                return null;
            }
            String path = ((Function<String, String>) System.getProperties().get(FIND_KEY)).apply(entry.getString("save"));
            if (path == null) MultiplayerLog.log().info("Your last game for " + login.host + " (" + entry.optString("save") + ") isn't in the saves any more");
            return path;
        } catch (Exception e) {
            MultiplayerLog.log().error("Couldn't read which game is yours for " + login.host, e);
            return null;
        }
    }

    private static JSONObject readLogins() throws Exception {
        if (!Global.getSettings().fileExistsInCommon(LOGINS_FILE)) return new JSONObject();
        String text = Global.getSettings().readTextFileFromCommon(LOGINS_FILE);
        return text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String key(String host, int port, String user) {
        return user.trim().toLowerCase() + "@" + host.trim() + ":" + port;
    }

    /**
     * What the new-game screens would set (the agent's NewGameMaker, the game thread): the options panel's part
     * (name, portrait, the sector's seed, age and size), then what vanilla's rule commands do for the stand-in start.
     */
    private static void fill(CharacterCreationData data, InteractionDialogAPI dialog, Login login) {
        data.getCharacterData().setName(login.user.trim(), FullName.Gender.MALE);
        List<String> portraits = Global.getSettings().getFactionSpec(Factions.PLAYER).getAllPortraits(FullName.Gender.MALE);
        if (!portraits.isEmpty()) data.getCharacterData().setPortraitName(portraits.get(0));
        data.setCampaignHelpEnabled(SharedSettings.optBoolean(NewGameDialogPluginImpl.CAMPAIGN_HELP_POPUPS_OPTION_CHECKED, true));
        applySeed(data, login.seed);

        //the rule commands read the data and their choices from the dialog's local memory
        MemoryAPI memory = Global.getFactory().createMemory();
        memory.set("$characterData", data);
        Map<String, MemoryAPI> memoryMap = new HashMap<>();
        memoryMap.put(MemKeys.LOCAL, memory);
        memoryMap.put(MemKeys.GLOBAL, Global.getFactory().createMemory());
        data.getStartingCargo().getCredits().add(START_CREDITS);
        for (String variant : START_SHIPS) data.addStartingFleetMember(variant, FleetMemberType.SHIP);
        memory.set("$ngcMercSelected", true);
        memory.set("$ngcAddOfficer", true);
        memory.set("$ngcSkipTutorial", true);
        data.setDifficulty("normal");
        data.getPerson().getStats().addPoints(1);
        data.setStartingLocationName("Corvus");
        data.getStartingCoordinates().set(-2500, 3000);
        //vanilla's own: crew, supplies, fuel, the officer, the skipped tutorial's contacts and reputation
        new NGCAddStandardStartingScript().execute(null, dialog, new ArrayList<>(), memoryMap);
        data.setDone(true);
    }

    /**
     * The host's seed, which every player's sector is generated from. Vanilla's new-game options make it as the
     * sector's age (Y, A, O, or M for any), its size (S, N), "-" and the seed (NewGameOptionsPanel.getSeedText).
     */
    private static void applySeed(CharacterCreationData data, String seed) {
        data.setSeedString(seed); //what joining compares (CompatibilityUtility): exactly the host's
        Matcher m = Pattern.compile("([YAOM])([SN])-(-?\\d+)").matcher(seed);
        if (!m.matches()) {
            MultiplayerLog.log().warn("The server's seed " + seed + " isn't in vanilla's form: this sector is generated with a random seed, and may not match the server's");
            return;
        }
        switch (m.group(1)) {
            case "Y": data.setSectorAge(StarAge.YOUNG); break;
            case "A": data.setSectorAge(StarAge.AVERAGE); break;
            case "O": data.setSectorAge(StarAge.OLD); break;
            default: data.setSectorAge(StarAge.ANY);
        }
        data.setSectorSize("S".equals(m.group(2)) ? "small" : "normal");
        data.setSeed(Long.parseLong(m.group(3)));
    }
}
