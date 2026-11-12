package me.pintobox.pintoAntiCheat;

import me.pintobox.pintoAntiCheat.cavevis.core.CaveVis;
import org.bukkit.plugin.java.JavaPlugin;

public final class PintoAntiCheat extends JavaPlugin {
    private CaveVis caveVis;

    @Override
    public void onEnable() {
        caveVis = new CaveVis(this);
        caveVis.start();
    }

    @Override
    public void onDisable() {
        if (caveVis != null) {
            caveVis.close();
            caveVis = null;
        }
    }
}