package io.mythicshields.command;

import io.mythicshields.MythicShieldsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public final class ShieldCommand implements CommandExecutor, TabCompleter {

    private final MythicShieldsPlugin plugin;

    public ShieldCommand(MythicShieldsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("mythicshields.admin")) {
            sender.sendMessage("§cНедостаточно прав.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§6/" + label + " reload §7- перезагрузить конфиги");
            sender.sendMessage("§6/" + label + " list §7- список щитов");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage("§aMythicShields перезагружен. Щитов: " + plugin.registry().size());
            }
            case "list" -> {
                sender.sendMessage("§6Загруженные щиты:");
                plugin.registry().all().forEach(s ->
                        sender.sendMessage("§7- §f" + s.getItemId()));
            }
            default -> sender.sendMessage("§cНеизвестная подкоманда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("reload", "list");
        return List.of();
    }
}
