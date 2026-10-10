package matlabmaster.multiplayer.agent;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Makes a new game from the title screen with no new-game screens, as the title screen does once its new-game
 * dialog is done (TitleScreenState.dialogDismissed, 0.98a): a new CharacterCreationData, filled in, then reset the
 * campaign engine, hand the data and the session's campaign state to the game's new-game maker, go to the campaign.
 * The mod fills the data (TitleJoin: the server's seed, the player's name, a start) through the API, as the
 * dialog's rule commands do; the agent does the rest, which the mod can't (the game forbids reflection to mods,
 * and the classes are obfuscated). Both are found by their signatures, not their names: the data is
 * CampaignGameManager's static String m(data, campaign state)'s first parameter, which implements
 * CharacterCreationData and has a public no-argument constructor.
 *
 * apply(filler) runs on the game thread (the title screen's frame): the filler gets the data and a dialog
 * stand-in (an InteractionDialogAPI whose methods do nothing and return null, for vanilla's new-game rule
 * commands, which refuse a null dialog); null once the new game is made, else why not.
 */
public class NewGameMaker implements Function<BiConsumer<Object, Object>, String> {
    public static final String KEY = "multiplayer.newGame";

    @Override
    public String apply(BiConsumer<Object, Object> filler) {
        try {
            Class<?> driverClass = Class.forName("com.fs.state.AppDriver");
            Object driver = driverClass.getMethod("getInstance").invoke(null);
            Map<?, ?> session = (Map<?, ?>) driverClass.getMethod("getSession").invoke(driver);
            Object campaignState = session.get("campaign state in session");
            if (campaignState == null) return "no campaign state in the game's session (not at the title screen yet?)";

            Method create = findCreator(campaignState.getClass());
            if (create == null) return "couldn't find the game's new-game maker (another game version?)";
            Object data = create.getParameterTypes()[0].getConstructor().newInstance();
            filler.accept(data, dialogStandIn());

            Class.forName("com.fs.starfarer.campaign.CampaignEngine").getMethod("resetInstance").invoke(null);
            create.setAccessible(true);
            Object error = create.invoke(null, data, campaignState);
            if (error != null) return String.valueOf(error);
            driverClass.getMethod("goToState", String.class).invoke(driver, "Campaign State");
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** CampaignGameManager's static String m(A, B): A a CharacterCreationData, B taking the campaign state. */
    private static Method findCreator(Class<?> campaignState) throws ClassNotFoundException {
        Class<?> data = Class.forName("com.fs.starfarer.api.characters.CharacterCreationData");
        Class<?> manager = Class.forName("com.fs.starfarer.campaign.save.CampaignGameManager");
        for (Method m : manager.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != String.class || p.length != 2) continue;
            if (data.isAssignableFrom(p[0]) && !p[0].isInterface() && p[1].isAssignableFrom(campaignState)) return m;
        }
        return null;
    }

    /** An InteractionDialogAPI that does nothing: null, false or 0 from every method. */
    private static Object dialogStandIn() throws ClassNotFoundException {
        Class<?> dialog = Class.forName("com.fs.starfarer.api.campaign.InteractionDialogAPI");
        return Proxy.newProxyInstance(dialog.getClassLoader(), new Class<?>[]{dialog}, new DoNothing());
    }

    static class DoNothing implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    default: return "multiplayer new-game dialog stand-in";
                }
            }
            Class<?> type = method.getReturnType();
            //a primitive's default value (false, 0): an array's first element
            return type.isPrimitive() && type != void.class ? Array.get(Array.newInstance(type, 1), 0) : null;
        }
    }
}
