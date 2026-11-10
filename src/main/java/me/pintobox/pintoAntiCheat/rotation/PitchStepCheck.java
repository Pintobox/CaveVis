package me.pintobox.pintoAntiCheat.rotation;

import io.papermc.paper.rotation.PitchPacketHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PitchStepCheck implements PitchPacketHook.Listener, Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, PitchStepState> states = new HashMap<>();

    public PitchStepCheck(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        PitchPacketHook.register(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void close() {
        PitchPacketHook.unregister(this);
        HandlerList.unregisterAll(this);
        states.clear();
    }

    @Override
    public void onRotation(Player player, float yaw, float pitch) {
        PitchStepState state = states.computeIfAbsent(
            player.getUniqueId(), id -> new PitchStepState()
        );

        double oldStep = state.step();
        PitchStepState.Result result = state.sample(yaw, pitch);

        if (state.takeUnstableReport()) {
            player.sendMessage(
                "[RotationStep] Couldn't fit a stable sensitivity step across "
                    + RotationStepSettings.UNSTABLE_REPORT_INTERVAL
                    + " more look packets."
            );
        }

        if (state.step() > 0.0 && state.step() != oldStep) {
            player.sendMessage(String.format(
                Locale.ROOT,
                "[RotationStep] Learned shared step: %.6f deg",
                state.step()
            ));
        }

        if (result != null) {
            player.sendMessage(String.format(
                Locale.ROOT,
                "[RotationStep] %s mismatch: delta=%.6f deg, step=%.6f deg, error=%.6f deg",
                result.axis(), result.delta(), result.step(), result.error()
            ));
        }
    }

    @Override
    public void onReset(Player player) {
        PitchStepState state = states.get(player.getUniqueId());
        if (state != null) state.resetAngle();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        onReset(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}