package matlabmaster.multiplayer.agent;

import com.fs.starfarer.api.Global;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.util.function.Function;

/**
 * A portrait's picture (a java.awt.Image) for the multiplayer window's character choice (the mod's UI): the mod
 * can't read the file or decode it itself (the game forbids mods file access and some of the JDK). Read the way the
 * game finds its files (the mods' own over the base game's). apply(portrait path) gives null if it can't be read.
 */
public class PortraitImages implements Function<String, Object> {
    public static final String KEY = "multiplayer.portraitImage";

    @Override
    public Object apply(String portrait) {
        try (InputStream in = Global.getSettings().openStream(portrait)) {
            return ImageIO.read(in);
        } catch (Throwable t) {
            System.out.println("[multiplayer agent] couldn't read the portrait " + portrait + ": " + t);
            return null;
        }
    }
}
