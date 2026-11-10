package me.pintobox.pintoAntiCheat.testing;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public final class BlockPlaceToggle implements Listener, CommandExecutor {
    private boolean placingEnabled = true;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!placingEnabled) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (!sender.hasPermission("pintoanticheat.blockplacing")) {
            sender.sendMessage("You don't have permission.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage("Usage: /" + label + " <on|off>");
            sender.sendMessage("Block placing: " +
                (placingEnabled ? "ON" : "OFF"));
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> placingEnabled = true;
            case "off" -> placingEnabled = false;
            default -> {
                sender.sendMessage("Usage: /" + label + " <on|off>");
                return true;
            }
        }

        sender.sendMessage("Block placing: " +
            (placingEnabled ? "ON" : "OFF"));
        return true;
    }
}