package matlabmaster.multiplayer.utils;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;

import java.util.ArrayList;
import java.util.List;

public class SectorScriptsUtility {
    List<EveryFrameScript> savedScripts = new ArrayList<>();
    public void disableScripts(){
        if(savedScripts.isEmpty()){
            savedScripts.addAll(Global.getSector().getScripts());
        }else {
            Global.getSector().getScripts().clear();
        }
    }

    public void restoreScripts(){
        if(Global.getSector().getScripts().isEmpty()){
            Global.getSector().getScripts().addAll(savedScripts);
        }
    }
}
