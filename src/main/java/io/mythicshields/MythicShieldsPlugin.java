package io.mythicshields;

import io.mythicshields.command.ShieldCommand;
import io.mythicshields.registry.ShieldRegistry;
import io.mythicshields.service.BlockingService;
import io.mythicshields.service.ComponentService;
import io.mythicshields.service.ShieldLog;
import io.mythicshields.service.SkillBridge;
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
    private ComponentService components;
    private SkillBridge skillBridge;
    private BukkitTask regenTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        SHIELD_KEY = new NamespacedKey(this, "shield_id");

        ShieldLog.init(this);
        registry = new ShieldRegistry(this);
        registry.reload();

        // Мост к MythicMobs: триггер ~onTakedShieldDamage + скиллы щита
        skillBridge = new SkillBridge(this);
        skillBridge.register();

        blocking = new BlockingService(this, registry, skillBridge);
        blocking.register();

        // Режим COMPONENT: вешает ванильный компонент blocks_attacks на кастомные щиты
        components = new ComponentService(this, registry);
        components.register();

        // Регенерация: раз в секунду; тонкая настройка внутри сервиса
        regenTask = Bukkit.getScheduler().runTaskTimer(this, blocking::tickRegen, 20L, 20L);

        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("mythicshields");
        if (cmd != null) {
            ShieldCommand executor = new ShieldCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        ShieldLog.info("MythicShields включён. Щитов загружено: " + registry.size());
    }

    @Override
    public void onDisable() {
        if (regenTask != null) regenTask.cancel();
        if (blocking != null) blocking.clearAll();
    }

    public ShieldRegistry registry() { return registry; }
    public BlockingService blocking() { return blocking; }
    public ComponentService components() { return components; }
    public SkillBridge skillBridge() { return skillBridge; }

    public void reloadAll() {
        reloadConfig();
        registry.reload();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        blocking.clear(e.getPlayer().getUniqueId());
    }
}
