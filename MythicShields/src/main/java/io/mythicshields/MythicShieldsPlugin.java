package io.mythicshields;

import io.mythicshields.command.ShieldCommand;
import io.mythicshields.registry.ShieldRegistry;
import io.mythicshields.service.BlockingService;
import io.mythicshields.service.ShieldLog;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MythicShieldsPlugin extends JavaPlugin implements Listener {

    public static NamespacedKey SHIELD_KEY;

    private ShieldRegistry registry;
    private BlockingService blocking;
    private BukkitTask regenTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        SHIELD_KEY = new NamespacedKey(this, "shield_id");

        ShieldLog.init(this);
        registry = new ShieldRegistry(this);
        blocking = new BlockingService(this, registry);
        blocking.register();

        regenTask = Bukkit.getScheduler().runTaskTimer(this, () -> blocking.tickRegen(), 20L, 20L);

        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("mythicshields");
        if (cmd != null) {
            ShieldCommand executor = new ShieldCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        ShieldLog.info("MythicShields enabled. Registered shields: " + registry.size());
    }

    @Override
    public void onDisable() {
        if (regenTask != null)
            regenTask.cancel();
        if (blocking != null)
            blocking.clearAll();
    }

    public ShieldRegistry registry() {
        return registry;
    }

    public BlockingService blocking() {
        return blocking;
    }

    public void reloadAll() {
        reloadConfig();
        registry.reload();
    }

    /*
     * -------------------------------------------------------- *
     * События сброса состояния
     * --------------------------------------------------------
     */

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        blocking.clear(e.getPlayer().getUniqueId());
    }
}
