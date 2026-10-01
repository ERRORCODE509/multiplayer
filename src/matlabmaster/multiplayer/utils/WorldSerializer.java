package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import org.json.JSONException;
import org.json.JSONObject;

public class WorldSerializer {
    public static JSONObject serializeOrbit(SectorEntityToken entityToken) throws JSONException {
        JSONObject serializedOrbit = new JSONObject();
        serializedOrbit.put("id", entityToken.getId());
        serializedOrbit.put("type",entityToken.getName());
        serializedOrbit.put("orbitFocusId",entityToken.getOrbitFocus().getId());
        serializedOrbit.put("circularOrbitAngle",entityToken.getCircularOrbitAngle()); //what angle is it currently at
        serializedOrbit.put("circularOrbitRadius",entityToken.getCircularOrbitRadius()); //how far away is it orbiting
        serializedOrbit.put("circularOrbitPeriod",entityToken.getCircularOrbitPeriod());//how fast does it go around the focus
        return serializedOrbit;
    }

    public static void unSerializeOrbit(JSONObject orbit) throws JSONException {
        SectorEntityToken entity = Global.getSector().getEntityById(orbit.getString("id"));
        SectorEntityToken orbitFocus = Global.getSector().getEntityById(orbit.getString("orbitFocusId"));
        if (entity == null || orbitFocus == null) return; //not in this game (yet)
        if (entity.getOrbit() != null && entity.getOrbitFocus() == orbitFocus) {
            //same orbit as the server's, only further along: move it there (gradually, see PositionSmoothing) and
            //keep the orbit itself (its kind, like pointing down or spinning, and its speed). Does nothing for
            //orbits that aren't circular
            PositionSmoothing.orbitToward(entity, (float) orbit.getDouble("circularOrbitAngle"));
            return;
        }
        entity.setCircularOrbit(orbitFocus,((float) orbit.getDouble("circularOrbitAngle")),((float) orbit.getDouble("circularOrbitRadius")),((float) orbit.getDouble("circularOrbitPeriod")));
    }
}
