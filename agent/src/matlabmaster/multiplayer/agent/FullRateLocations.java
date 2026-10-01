package matlabmaster.multiplayer.agent;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;

/**
 * What the patched CampaignEngine.advance calls instead of the locations' own advanceEvenIfPaused, advance and
 * setActiveThisFrame. Vanilla runs only the current location (where this game's own player fleet is) every frame;
 * hyperspace and every other star system get one big step every 60 frames, so everything there moves and decides in
 * one-second jumps. A location a player is in is run every frame instead, like the current one, and skips its big
 * step. Any other location, and every location while fast-advancing (when vanilla runs them all every frame), is
 * exactly vanilla.
 *
 * The multiplayer mod publishes the locations players are in as a Set under LOCATIONS_KEY in System.getProperties()
 * (a JVM-wide object both sides can reach, so the mod never links to this class); without it nothing changes.
 *
 * The engine calls these in this order each frame: advanceEvenIfPaused of the current location (which gives this
 * frame's amount), then setActiveThisFrame of every other location (true on its turn for a big step, else false),
 * with that big step's advanceEvenIfPaused / advance right after a true. A location a player is in gets its ordinary
 * frame from setActiveThisFrame, and its big-step calls do nothing.
 */
public class FullRateLocations {
    public static final String LOCATIONS_KEY = "multiplayer.fullRate.locations";

    private static final MethodHandle ADVANCE_EVEN_IF_PAUSED;
    private static final MethodHandle ADVANCE;
    private static final MethodHandle SET_ACTIVE_THIS_FRAME;

    static {
        try {
            ClassLoader loader = FullRateLocations.class.getClassLoader();
            Class<?> location = Class.forName("com.fs.starfarer.campaign.BaseLocation", false, loader);
            Class<?> profile = Class.forName("com.fs.starfarer.util.A.new", false, loader); //an obfuscated name
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType advanceType = MethodType.methodType(void.class, float.class, profile);
            MethodType generic = MethodType.methodType(void.class, Object.class, float.class, Object.class);
            ADVANCE_EVEN_IF_PAUSED = lookup.findVirtual(location, "advanceEvenIfPaused", advanceType).asType(generic);
            ADVANCE = lookup.findVirtual(location, "advance", advanceType).asType(generic);
            SET_ACTIVE_THIS_FRAME = lookup.findVirtual(location, "setActiveThisFrame", MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** This frame's amount and the engine's argument, from the current location's call (the first of each frame). */
    private static float frameAmount;
    private static Object frameArg;

    public static void advanceEvenIfPaused(Object location, float amount, Object arg) throws Throwable {
        SectorAPI sector = Global.getSector();
        if (!sector.isInFastAdvance()) {
            if (location == sector.getCurrentLocation()) {
                frameAmount = amount;
                frameArg = arg;
            } else if (hasPlayer(location)) {
                return; //its big step: it already had this frame from setActiveThisFrame
            }
        }
        ADVANCE_EVEN_IF_PAUSED.invokeExact(location, amount, arg);
    }

    public static void advance(Object location, float amount, Object arg) throws Throwable {
        SectorAPI sector = Global.getSector();
        if (!sector.isInFastAdvance() && location != sector.getCurrentLocation() && hasPlayer(location)) {
            return; //its big step: it already had this frame from setActiveThisFrame
        }
        ADVANCE.invokeExact(location, amount, arg);
    }

    public static void setActiveThisFrame(Object location, boolean active) throws Throwable {
        SectorAPI sector = Global.getSector();
        if (sector.isInFastAdvance() || location == sector.getCurrentLocation() || !hasPlayer(location)) {
            SET_ACTIVE_THIS_FRAME.invokeExact(location, active);
            return;
        }
        //a player is here: an ordinary frame, as the current location gets
        SET_ACTIVE_THIS_FRAME.invokeExact(location, true);
        ADVANCE_EVEN_IF_PAUSED.invokeExact(location, frameAmount, frameArg);
        if (!sector.isPaused()) ADVANCE.invokeExact(location, frameAmount, (Object) null);
    }

    private static boolean hasPlayer(Object location) {
        Object locations = System.getProperties().get(LOCATIONS_KEY);
        return locations instanceof Set && ((Set<?>) locations).contains(location);
    }
}
