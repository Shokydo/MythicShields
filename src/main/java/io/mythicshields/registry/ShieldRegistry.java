package io.mythicshields.registry;

import io.mythicshields.MythicShieldsPlugin;
import io.mythicshields.model.ShieldConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Реестр конфигураций щитов. Два источника:
 *  1) plugins/MythicMobs/Items/*.yml — секция Shield прямо внутри предмета MythicMobs;
 *  2) plugins/MythicShields/Items/*.yml — самостоятельные определения щитов,
 *     в т.ч. с Match.* для выбора предметов БЕЗ PDC-ключей (Nexo, креатив и т.п.).
 */
public class ShieldRegistry {

    private final MythicShieldsPlugin plugin;
    private final Map<String, ShieldConfig> shields = new HashMap<>();
    private final List<ShieldConfig> matchedShields = new ArrayList<>();

    public ShieldRegistry(MythicShieldsPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        shields.clear();
        matchedShields.clear();

        int mmCount = parseMMItems();
        int addonCount = parseAddonItems();

        if (mmCount == 0 && addonCount == 0) {
            io.mythicshields.service.ShieldLog.warn("Щиты не загружены: нет Shield-секций в plugins/MythicMobs/Items и файлов в plugins/MythicShields/Items.");
        } else {
            io.mythicshields.service.ShieldLog.info("Щитов загружено: " + shields.size()
                    + " (из MythicMobs Items: " + mmCount + ", из MythicShields Items: " + addonCount + ")");
        }
    }

    /* ------------------------------------------------------- 1) MythicMobs Items */

    private int parseMMItems() {
        File mmItems = new File(plugin.getDataFolder().getParentFile(), "MythicMobs" + File.separator + "Items");
        return parseFolder(mmItems, false);
    }

    /* ------------------------------------------------------- 2) Собственные определения аддона */

    private int parseAddonItems() {
        File addonItems = new File(plugin.getDataFolder(), "Items");
        return parseFolder(addonItems, true);
    }

    private int parseFolder(File folder, boolean standalone) {
        File[] files = folder.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files == null || files.length == 0) return 0;
        int count = 0;
        for (File file : files) {
            try {
                count += parseFile(YamlConfiguration.loadConfiguration(file), standalone);
            } catch (Exception ex) {
                io.mythicshields.service.ShieldLog.warn("Не удалось прочитать " + file.getName() + ": " + ex.getMessage());
            }
        }
        return count;
    }

    private int parseFile(YamlConfiguration yaml, boolean standalone) {
        int count = 0;
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection itemSec = yaml.getConfigurationSection(key);
            if (itemSec == null) continue;

            boolean hasShieldSec = itemSec.getConfigurationSection("Shield") != null;
            // В MM-предметах щит только в секции Shield; в автономных файлах
            // сам узел является секцией щита.
            boolean enabled = hasShieldSec
                    ? itemSec.getBoolean("Shield.Enabled", false)
                    : (standalone && itemSec.getBoolean("Enabled", true));
            if (!enabled) continue;

            ConfigurationSection shieldSec = hasShieldSec ? itemSec.getConfigurationSection("Shield") : itemSec;

            try {
                ShieldConfig cfg = ShieldConfig.from(plugin, key.toLowerCase(), shieldSec);
                // В MM-предметах материал/CustomModelData лежат в корне узла —
                // используем их как Match, если он не задан явно
                if (!standalone) deriveMatchFromItemRoot(cfg, itemSec);
                // Скиллы щита часто пишут в корне предмета (рядом с Shield:), как
                // обычные MM-item Skills. Читаем их тоже — аддон сам отфильтрует
                // строки по своим триггерам (~onTakedShieldDamage и т. д.).
                if (!standalone && itemSec != shieldSec) {
                    mergeRootSkills(cfg, itemSec);
                }
                if (cfg.hasSkills()) {
                    io.mythicshields.service.ShieldLog.debug("Щит " + key + ": строк скиллов "
                            + cfg.skills.size() + " (триггеры щита найдут себя сами)");
                }
                shields.put(key.toLowerCase(), cfg);
                if (cfg.hasMatch()) matchedShields.add(cfg);
                count++;
            } catch (Exception ex) {
                io.mythicshields.service.ShieldLog.warn("Ошибка конфига щита '" + key + "': " + ex.getMessage());
            }
        }
        return count;
    }

    /** Добавляет строки Skills из корня предмета MythicMobs (без дублей). */
    static void mergeRootSkills(ShieldConfig cfg, ConfigurationSection itemSec) {
        List<String> raw = itemSec.getStringList("Skills");
        if (raw.isEmpty()) {
            String single = itemSec.getString("Skills");
            if (single != null && !single.isBlank()) raw = List.of(single);
        }
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            String line = s.trim();
            if (!cfg.skills.contains(line)) cfg.skills.add(line);
        }
    }

    /**
     * Из корня MM-предмета (Id/Material + CustomModelData) выводим условия
     * автосоответствия, если Match.* не задан явно. Автовывод включается
     * только когда у предмета задан CustomModelData — иначе есть риск
     * принять за щит любой предмет того же материала.
     */
    private void deriveMatchFromItemRoot(ShieldConfig cfg, ConfigurationSection itemSec) {
        if (cfg.hasMatch()) return;
        Object cmd = itemSec.get("CustomModelData");
        Integer cmdValue = null;
        if (cmd instanceof Number n) cmdValue = n.intValue();
        else if (cmd != null) {
            try {
                cmdValue = Integer.parseInt(String.valueOf(cmd).trim());
            } catch (NumberFormatException ignored) {}
        }
        if (cmdValue == null) return; // без CMD автовывод не делаем

        cfg.matchCustomModelData.add(String.valueOf(cmdValue));
        String material = firstNonBlank(itemSec.getString("Id"), itemSec.getString("Material"));
        if (material != null) cfg.matchMaterial = material.trim().toUpperCase();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /* ------------------------------------------------------- Поиск */

    public ShieldConfig get(String internalName) {
        if (internalName == null)
            return null;
        return shields.get(internalName.toLowerCase());
    }

    /**
     * Найти щит по Match.* (материал + CustomModelData) для предметов,
     * у которых нет PDC-ключей MythicMobs/MythicShields.
     */
    public ShieldConfig match(ItemStack item) {
        if (item == null) return null;
        for (ShieldConfig c : matchedShields) {
            if (c.matches(item)) return c;
        }
        return null;
    }

    public java.util.Collection<ShieldConfig> all() {
        return shields.values();
    }

    public Set<String> ids() {
        return shields.keySet();
    }

    public int size() {
        return shields.size();
    }
}
