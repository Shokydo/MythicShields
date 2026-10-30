package io.mythicshields.command;

import io.mythicshields.MythicShieldsPlugin;
import io.mythicshields.model.ShieldConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

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
            sender.sendMessage("§6/" + label + " apply §7- надеть blocks_attacks на предмет в руке");
            sender.sendMessage("§6/" + label + " bind <id> §7- привязать предмет в руке к щиту");
            sender.sendMessage("§6/" + label + " unbind §7- отвязать предмет в руке");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage("§aMythicShields перезагружен. Щитов: " + plugin.registry().size());
            }
            case "list" -> {
                sender.sendMessage("§6Загруженные щиты:");
                plugin.registry().all().forEach(s -> sender.sendMessage("§7- §f" + s.getItemId()
                        + " §7(" + s.mode().name().toLowerCase(Locale.ROOT) + ")"));
            }
            case "apply" -> handleApply(sender);
            case "bind" -> handleBind(sender, args);
            case "unbind" -> handleUnbind(sender);
            default -> sender.sendMessage("§cНеизвестная подкоманда.");
        }
        return true;
    }

    /* ------------------------------------------------------- apply */

    private void handleApply(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cТолько для игроков.");
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        ShieldConfig cfg = plugin.components().resolve(item);
        if (cfg == null) {
            sender.sendMessage("§cПредмет в руке не распознан как щит COMPONENT-режима.");
            sender.sendMessage("§7Используй §f/mythicshields bind <id>§7, если предмет выдан не MythicMobs (например, Nexo).");
            return;
        }
        if (plugin.components().applyComponentTo(item)) {
            p.getInventory().setItemInMainHand(item);
            sender.sendMessage("§aКомпонент blocks_attacks надет на предмет (" + cfg.getItemId() + "). Зажми ПКМ — блок должен работать нативно.");
        } else if (plugin.components().hasComponent(item)) {
            sender.sendMessage("§eКомпонент уже есть на предмете. Если блок всё равно не работает — см. §f/mythicshields bind§e и Debug: true.");
        }
    }

    /* ------------------------------------------------------- bind */

    private void handleBind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cТолько для игроков.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /mythicshields bind <id>");
            sender.sendMessage("§7Доступные: " + String.join(", ", plugin.registry().ids()));
            return;
        }
        ShieldConfig cfg = plugin.registry().get(args[1]);
        if (cfg == null) {
            sender.sendMessage("§cЩите '§f" + args[1] + "§c' не найден. Смотри /mythicshields list");
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            sender.sendMessage("§cВозьми предмет в главную руку.");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(MythicShieldsPlugin.SHIELD_KEY, PersistentDataType.STRING, cfg.getItemId());
        item.setItemMeta(meta);
        p.getInventory().setItemInMainHand(item);

        boolean applied = false;
        if (cfg.mode() == ShieldConfig.Mode.COMPONENT) {
            ItemStack after = p.getInventory().getItemInMainHand();
            if (plugin.components().applyComponentTo(after)) {
                p.getInventory().setItemInMainHand(after);
                applied = true;
            }
        }
        sender.sendMessage("§aПредмет привязан к щите §f" + cfg.getItemId()
                + (applied ? " §aи получил blocks_attacks." : "."));
    }

    private void handleUnbind(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cТолько для игроков.");
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            sender.sendMessage("§cВозьми предмет в главную руку.");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(MythicShieldsPlugin.SHIELD_KEY);
        item.setItemMeta(meta);
        p.getInventory().setItemInMainHand(item);
        sender.sendMessage("§aПредмет отвязан.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("reload", "list", "apply", "bind", "unbind");
        if (args.length == 2 && args[0].equalsIgnoreCase("bind"))
            return List.copyOf(plugin.registry().ids());
        return List.of();
    }
}
