package matlabmaster.multiplayer.agent;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loads a save from the title screen with no clicks, as its Load dialog does (TitleScreenState.dialogDismissed,
 * 0.98a): reset the campaign engine, hand the save's folder and the session's campaign state to the game's loader,
 * go to the campaign. The mod calls it (AutoLoadPlugin) through System.getProperties() (LOAD_KEY), so neither
 * side links to the other; the mod itself can't (the game forbids reflection to mods, and the loader's name is
 * obfuscated). The loader is found by its signature (a static String method of CampaignGameManager taking the
 * save folder and the campaign state twice), not its name, so a game update that renames it still finds it.
 *
 * apply(save folder) runs on the game thread (the title screen's frame): null once it's loading, else why not.
 *
 * Also, for a player's game logging in from the title screen (the mod's TitleJoin): FIND_KEY, a Function<String,
 * String> from a save folder's name to its full path (null if there's no such save), and CURRENT_KEY, a
 * Supplier<String> of the loaded game's save folder name (the game picks it when the game is made).
 */
public class SaveLoader implements Function<String, String> {
    public static final String LOAD_KEY = "multiplayer.loadSave";
    public static final String FIND_KEY = "multiplayer.findSave";
    public static final String CURRENT_KEY = "multiplayer.saveDirName";

    static void install() {
        System.getProperties().put(LOAD_KEY, new SaveLoader());
        System.getProperties().put(FIND_KEY, (Function<String, String>) SaveLoader::find);
        System.getProperties().put(CURRENT_KEY, (Supplier<String>) SaveLoader::currentDirName);
    }

    @Override
    public String apply(String saveDir) {
        try {
            Class<?> driverClass = Class.forName("com.fs.state.AppDriver");
            Object driver = driverClass.getMethod("getInstance").invoke(null);
            Map<?, ?> session = (Map<?, ?>) driverClass.getMethod("getSession").invoke(driver);
            Object campaignState = session.get("campaign state in session");
            if (campaignState == null) return "no campaign state in the game's session (not at the title screen yet?)";

            Method load = findLoader(campaignState.getClass());
            if (load == null) return "couldn't find the game's save loader (another game version?)";

            Class.forName("com.fs.starfarer.campaign.CampaignEngine").getMethod("resetInstance").invoke(null);
            load.setAccessible(true);
            Object error = load.invoke(null, saveDir, campaignState, campaignState);
            if (error != null) return String.valueOf(error);
            driverClass.getMethod("goToState", String.class).invoke(driver, "Campaign State");
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** CampaignGameManager's static String m(String, A, B) with A and B both taking the campaign state. */
    private static Method findLoader(Class<?> campaignState) throws ClassNotFoundException {
        Class<?> manager = Class.forName("com.fs.starfarer.campaign.save.CampaignGameManager");
        for (Method m : manager.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != String.class || p.length != 3) continue;
            if (p[0] == String.class && p[1].isAssignableFrom(campaignState) && p[2].isAssignableFrom(campaignState)) return m;
        }
        return null;
    }

    /** The full path of the save in the folder of this name, from the game's own list of saves; null if none. */
    static String find(String dirName) {
        try {
            Class<?> manager = Class.forName("com.fs.starfarer.campaign.save.CampaignGameManager");
            Class<?> saveData = Class.forName("com.fs.starfarer.campaign.save.SaveGameData");
            for (Method m : manager.getDeclaredMethods()) {
                //CampaignGameManager's static List<SaveGameData> m(): every save, as the Load dialog lists them
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != List.class || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                for (Object save : (List<?>) m.invoke(null)) {
                    if (!saveData.isInstance(save)) continue;
                    File dir = (File) saveData.getMethod("getSaveDir").invoke(save);
                    if (dir != null && dir.getName().equals(dirName)) return dir.getAbsolutePath();
                }
            }
        } catch (Throwable t) {
            System.out.println("[multiplayer agent] couldn't list the saves: " + t);
        }
        return null;
    }

    /** The loaded game's save folder name (CampaignEngine.getSaveDirName), or null. */
    static String currentDirName() {
        try {
            Class<?> engine = Class.forName("com.fs.starfarer.campaign.CampaignEngine");
            Object instance = engine.getMethod("getInstance").invoke(null);
            return instance == null ? null : (String) engine.getMethod("getSaveDirName").invoke(instance);
        } catch (Throwable t) {
            return null;
        }
    }
}
