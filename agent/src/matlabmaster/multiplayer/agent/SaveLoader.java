package matlabmaster.multiplayer.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.function.Function;

/**
 * Loads a save from the title screen with no clicks, as its Load dialog does (TitleScreenState.dialogDismissed,
 * 0.98a): reset the campaign engine, hand the save's folder and the session's campaign state to the game's loader,
 * go to the campaign. The mod calls it (AutoLoadPlugin) through System.getProperties() (LOAD_KEY), so neither
 * side links to the other; the mod itself can't (the game forbids reflection to mods, and the loader's name is
 * obfuscated). The loader is found by its signature (a static String method of CampaignGameManager taking the
 * save folder and the campaign state twice), not its name, so a game update that renames it still finds it.
 *
 * apply(save folder) runs on the game thread (the title screen's frame): null once it's loading, else why not.
 */
public class SaveLoader implements Function<String, String> {
    public static final String LOAD_KEY = "multiplayer.loadSave";

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
}
