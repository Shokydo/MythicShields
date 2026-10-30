package io.mythicshields.registry;

import io.mythicshields.MythicShieldsPlugin;
import io.mythicshields.model.ShieldConfig;
import io.mythicshields.service.ShieldLog;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Реестр конфигураций щитов.
 * Читает:
 *   - plugins/MythicShields/items.yml (аддоновые предметы)
 *   - plugins/MythicMobs/Items/*.yml  (ищем секцию Shield внутри каждого предмета)
 */
public class ShieldRegistry {

    private final MythicShieldsPlugin plugin;
    private final Map<String, ShieldConfig> byId = new HashMap<>();

    public ShieldRegistry(MythicShieldsPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        byId.clear();

        File addonItems = new File(plugin.getDataFolder(), "items.yml");
        if (addonItems.isFile()) {
            loadFile(YamlConfiguration.loadConfiguration(addonItems), addonItems.getName());
        }

        File mmDir = new File(plugin.getDataFolder().getParentFile(), "MythicMobs/Items");
        File[] files = mmDir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                try {
                    loadFile(YamlConfiguration.loadConfiguration(f), f.getName());
                } catch (Exception ex) {
                    ShieldLog.warn("Failed to parse " + f.getName() + ": " + ex.getMessage());
                }
            }
        }
        ShieldLog.info("Loaded " + byId.size() + " shield config(s).");
    }

    private void loadFile(YamlConfiguration yaml, String fileName) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection item = yaml.getConfigurationSection(key);
            if (item == null) continue;
            ConfigurationSection shield = item.getConfigurationSection("Shield");
            if (shield == null || !shield.getBoolean("Enabled", false)) continue;
            try {
                byId.put(key.toLowerCase(), ShieldConfig.load(key, shield, plugin.getConfig()));
            } catch (Exception ex) {
                ShieldLog.warn("Shield '" + key + "' in " + fileName + ": " + ex.getMessage());
            }
        }
    }

    public ShieldConfig get(String internalName) {
        return internalName == null ? null : byId.get(internalName.toLowerCase());
    }

    public ShieldConfig byItemStack(org.bukkit.inventory.ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        var meta = stack.getItemMeta();
        // Наш ключ (если предмет был выдан аддоном) или ключ MythicMobs
        String id = meta.getPersistentDataContainer().get(MythicShieldsPlugin.SHIELD_KEY,
                org.bukkit.persistence.PersistentDataType.STRING);
        if (id != null) return byId.get(id.toLowerCase());
        return null;
    }

    public Collection<ShieldConfig> all() { return byId.values(); }
    public int size() { return byId.size(); }
}
