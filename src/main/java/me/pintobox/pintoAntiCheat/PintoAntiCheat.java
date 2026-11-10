package me.pintobox.pintoAntiCheat;

import me.pintobox.pintoAntiCheat.cavevis.core.CaveVis;
import me.pintobox.pintoAntiCheat.rotation.PitchStepCheck;
import me.pintobox.pintoAntiCheat.testing.BlockPlaceToggle;
import org.bukkit.plugin.java.JavaPlugin;

public final class PintoAntiCheat extends JavaPlugin {
    private CaveVis caveVis;
    private PitchStepCheck pitchStepCheck;

    @Override
    public void onEnable() {
        caveVis = new CaveVis(this);
        caveVis.start();

        pitchStepCheck = new PitchStepCheck(this);
        pitchStepCheck.start();

        BlockPlaceToggle blockPlaceToggle = new BlockPlaceToggle();
        getServer().getPluginManager().registerEvents(blockPlaceToggle, this);
        getCommand("blockplacing").setExecutor(blockPlaceToggle);
    }

    @Override
    public void onDisable() {
        if (caveVis != null) {
            caveVis.close();
            caveVis = null;
        }

        if (pitchStepCheck != null) {
            pitchStepCheck.close();
            pitchStepCheck = null;
        }
    }
}