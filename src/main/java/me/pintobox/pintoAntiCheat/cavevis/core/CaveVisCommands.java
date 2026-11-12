package me.pintobox.pintoAntiCheat.cavevis.core;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

final class CaveVisCommands implements CommandExecutor, TabCompleter {
    private final CaveVis caveVis;

    CaveVisCommands(CaveVis caveVis) {
        this.caveVis = caveVis;
    }

    @Override
    public boolean onCommand(
        CommandSender sender, Command command, String label, String[] args
    ) {
        try {
            String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

            switch (sub) {
                case "build" -> {
                    Player player = player(sender);
                    int radius = args.length > 1
                        ? Integer.parseInt(args[1])
                        : caveVis.defaultPreloadRadius();
                    caveVis.build(sender, player, radius);
                }
                case "cancel" -> {
                    caveVis.cancelBuild();
                    message(sender, "Build cancelled; previous cache retained.");
                }
                case "clear" -> {
                    caveVis.clear();
                    message(sender, "Ray cache cleared; terrain filtering remains active.");
                }
                case "profile" -> {
                    if (args.length != 2) {
                        throw new IllegalArgumentException(
                            "Usage: /cavevis profile <on|off|reset|detail>"
                        );
                    }

                    String action = args[1].toLowerCase(Locale.ROOT);
                    message(sender, caveVis.profile(player(sender), action));
                }
                case "raystats" -> {
                    boolean tickStats = args.length > 1;
                    if (tickStats && !args[1].equalsIgnoreCase("tick")) {
                        throw new IllegalArgumentException("Usage: /cavevis raystats [tick]");
                    }
                    for (String line : caveVis.stats(sender, tickStats)) {
                        message(sender, line);
                    }
                }
                default -> message(sender,
                    "/cavevis build [radius] | cancel | clear "
                        + "| profile <on|off|reset|detail> | raystats [tick]");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            message(sender, e.getMessage());
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
        CommandSender sender, Command command, String alias, String[] args
    ) {
        if (args.length == 0) return List.of();

        List<String> options = args.length == 1
            ? List.of("build", "cancel", "clear", "profile", "raystats")
            : args.length == 2 && args[0].equalsIgnoreCase("profile")
            ? List.of("on", "off", "reset", "detail")
            : args.length == 2 && args[0].equalsIgnoreCase("raystats")
            ? List.of("tick")
            : List.of();

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream()
            .filter(value -> value.startsWith(prefix))
            .toList();
    }

    private static Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new IllegalArgumentException("This command needs an in-game player");
    }

    private static void message(CommandSender sender, String text) {
        sender.sendMessage("[CaveVis] " + text);
    }
}