package io.mythicshields.service;

import io.mythicshields.MythicShieldsPlugin;
import io.mythicshields.model.ShieldConfig;
import io.mythicshields.registry.ShieldRegistry;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BlocksAttacks;
import io.papermc.paper.datacomponent.item.blocksattacks.DamageReduction;
import io.papermc.paper.datacomponent.item.blocksattacks.ItemDamageFunction;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.set.RegistrySet;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mode = COMPONENT: предмет сам делает предмет настоящим ванильным щитом.
 * Компонент навешивается на предмет, как только он замечен в руках /
 * инвентаре игрока (ПЛЮС периодический свип предметов в руках).
 * После этого предмет блокирует нативно: кастомная модель всегда в руке,
 * зажатие ПКМ = настоящий ванильный блок.
 */
public final class ComponentService implements Listener {

    private final MythicShieldsPlugin plugin;
    private final ShieldRegistry registry;

    public ComponentService(MythicShieldsPlugin plugin, ShieldRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Свип: предметы в руках, выданные без событий (mm i give в текущий слот и т.п.)
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweepHeldItems, 40L, 40L);
    }

    /* ------------------------------------------------------- API */

    /**
     * Накладывает компонент на временный ванильный щит в режиме VANILLA_SWAP.
     * Нужен в том числе для отключения стандартных звуков ванильного щита.
     */
    public void applyTemporaryShieldComponent(ItemStack item, ShieldConfig cfg) {
        if (item == null || cfg == null) return;
        try {
            item.setData(DataComponentTypes.BLOCKS_ATTACKS, build(cfg));
        } catch (Throwable t) {
            ShieldLog.warn("Не удалось настроить временный щит " + cfg.getItemId() + ": " + t);
        }
    }

    /**
     * Возвращает конфиг щита для предмета (по собственному PDC-ключу
     * или ключу MythicMobs), если щит зарегистрирован и режим COMPONENT.
     */
    public ShieldConfig resolve(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();

        // 1) собственный ключ
        String own = pdc.get(MythicShieldsPlugin.SHIELD_KEY, PersistentDataType.STRING);
        ShieldConfig cfg = own != null ? registry.get(own) : null;

        // 2) ЛЮБОЙ ключ namespace mythicmobs (MM иногда меняет формат ключа item_id)
        if (cfg == null) {
            for (NamespacedKey key : pdc.getKeys()) {
                if (!key.getNamespace().equalsIgnoreCase("mythicmobs")) continue;
                String value = pdc.get(key, PersistentDataType.STRING);
                if (value == null) continue;
                ShieldConfig cand = registry.get(value);
                if (cand == null && value.contains(":"))
                    cand = registry.get(value.substring(value.lastIndexOf(':') + 1));
                if (cand != null) {
                    cfg = cand;
                    break;
                }
            }
        }

        // 3) Match.* (материал + CustomModelData)
        if (cfg == null) cfg = registry.match(item);

        if (cfg != null && cfg.mode() != ShieldConfig.Mode.COMPONENT) return null;
        return cfg;
    }

    /** Есть ли на предмете уже ванильный блокирующий компонент. */
    public boolean hasComponent(ItemStack item) {
        try {
            return item.hasData(DataComponentTypes.BLOCKS_ATTACKS);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Снимает ванильный компонент blocks_attacks с предмета (для disarm на КД). */
    public void removeComponent(ItemStack item) {
        try {
            item.resetData(DataComponentTypes.BLOCKS_ATTACKS);
        } catch (Throwable t) {
            try {
                // fallback: повторно записать компонент с нулевой задержкой и
                // нулевыми правилами нельзя — просто пометить отсутствие нечем;
                // оставляем как есть ( disarm не сработал, но и без урона логике)
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Накладывает компонент blocks_attacks на КОПИЮ предмета.
     * ВНИМАНИЕ: изменение живёт только в переданном ItemStack —
     * вызывающий обязан записать предмет обратно в слот (write-back)!
     * true = компонент сейчас был применён.
     */
    public boolean applyComponentTo(ItemStack item) {
        ShieldConfig cfg = resolve(item);
        if (cfg == null) return false;
        try {
            if (hasComponent(item)) {
                BlocksAttacks current = item.getData(DataComponentTypes.BLOCKS_ATTACKS);
                // Items saved by an older plugin version may already have
                // blocks_attacks with vanilla sounds. Refresh them when sound
                // settings differ, without rewriting the component each sweep.
                if (current != null
                        && Objects.equals(current.blockSound(), componentSound(cfg.component.blockSound))
                        && Objects.equals(current.disableSound(), componentSound(cfg.component.disableSound))) {
                    return false;
                }
            }
            item.setData(DataComponentTypes.BLOCKS_ATTACKS, build(cfg));
        } catch (Throwable t) {
            ShieldLog.warn("Не удалось применить blocks_attacks к " + cfg.getItemId() + ": " + t);
            return false;
        }
        ShieldLog.debug("blocks_attacks применён к предмету щита " + cfg.getItemId());
        return true;
    }

    /** Форс-подготовка предмета игрока в слоте: применить компонент и записать обратно. */
    public boolean ensureInSlot(Player p, EquipmentSlot hand) {
        PlayerInventory inv = p.getInventory();
        ItemStack item = inv.getItem(hand);
        if (item == null) return false;

        ShieldConfig cfg = resolve(item);
        if (cfg == null) return false;
        if (plugin.blocking() != null && plugin.blocking().isOnCooldown(p, cfg)) {
            if (hasComponent(item)) {
                removeComponent(item);
                inv.setItem(hand, item); // keep this shield disarmed for its own cooldown
            }
            return false;
        }

        if (applyComponentTo(item)) {
            inv.setItem(hand, item); // write-back — в некоторых слотах это копия
            return true;
        }
        return false;
    }

    /** Проверить обе руки игрока. */
    public void ensureInHands(Player p) {
        ensureInSlot(p, EquipmentSlot.HAND);
        ensureInSlot(p, EquipmentSlot.OFF_HAND);
    }

    private void sweepHeldItems() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ensureInHands(p);
        }
    }

    /* ------------------------------------------------------- События */

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ensureInHands(e.getPlayer());
        PlayerInventory inv = e.getPlayer().getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (applyComponentTo(stack)) inv.setItem(i, stack);
        }
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent e) {
        PlayerInventory inv = e.getPlayer().getInventory();
        int slot = e.getNewSlot();
        ItemStack item = inv.getItem(slot);
        if (applyComponentTo(item)) inv.setItem(slot, item);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        ensureInHands(e.getPlayer());
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player) {
            ItemStack stack = e.getItem().getItemStack();
            if (applyComponentTo(stack)) e.getItem().setItemStack(stack);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        // фикс 1.21.8: у InventoryClickEvent нет getPlayer(); работаем через getWhoClicked()/view
        ItemStack current = e.getCurrentItem();
        if (applyComponentTo(current)) e.setCurrentItem(current);
        ItemStack cursor = e.getView().getCursor();
        if (applyComponentTo(cursor)) e.getView().setCursor(cursor);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // главное — успеть до поднятия щита самим ванильным клиентом
        ensureInSlot(e.getPlayer(), e.getHand() == null ? EquipmentSlot.HAND : e.getHand());
    }

    /* ------------------------------------------------------- Компонент из конфига */

    private BlocksAttacks build(ShieldConfig cfg) {
        ShieldConfig.BlockingComponent comp = cfg.component;

        BlocksAttacks.Builder builder = BlocksAttacks.blocksAttacks()
                .blockDelaySeconds(comp.blockDelaySeconds)
                .disableCooldownScale(comp.disableCooldownScale)
                .itemDamage(ItemDamageFunction.itemDamageFunction()
                        .threshold(comp.itemDamageThreshold)
                        .base(comp.itemDamageBase)
                        .factor(comp.itemDamageFactor)
                        .build());

        for (ShieldConfig.DamageReductionSpec spec : comp.reductions) {
            DamageReduction.Builder rb = DamageReduction.damageReduction()
                    .base(spec.base)
                    .factor(spec.factor)
                    .horizontalBlockingAngle(spec.horizontalBlockingAngle);
            if (!spec.types.isEmpty()) rb.type(types(spec.types));
            builder.addDamageReduction(rb.build());
        }

        // The Paper builder inherits vanilla shield sounds when these fields are
        // omitted. Use Minecraft's no-op sound event by default so every shield
        // is silent unless its owner explicitly configures a sound.
        builder.blockSound(componentSound(comp.blockSound));
        builder.disableSound(componentSound(comp.disableSound));
        if (comp.bypassedBy != null && !comp.bypassedBy.isBlank()) {
            builder.bypassedBy(tagKey(comp.bypassedBy));
        }
        return builder.build();
    }

    private Key componentSound(String sound) {
        if (sound == null || sound.isBlank()) return Key.key("minecraft:intentionally_empty");
        return Key.key(sound.trim());
    }

    private RegistryKeySet<DamageType> types(List<String> names) {
        List<DamageType> values = new ArrayList<>();
        for (String raw : names) {
            String s = raw.trim();
            if (s.startsWith("#")) { // тег: расширяем в конкретные типы через реестр
                TagKey<DamageType> key = tagKey(s);
                for (DamageType dt : Registry.DAMAGE_TYPE.getTagValues(key)) values.add(dt);
            } else {
                NamespacedKey key = NamespacedKey.fromString(s.toLowerCase());
                DamageType dt = key == null ? null : Registry.DAMAGE_TYPE.get(key);
                if (dt != null) values.add(dt);
            }
        }
        return RegistrySet.keySetFromValues(RegistryKey.DAMAGE_TYPE, values);
    }

    private TagKey<DamageType> tagKey(String raw) {
        String s = raw.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (!s.contains(":")) s = "minecraft:" + s;
        return TagKey.create(RegistryKey.DAMAGE_TYPE, Key.key(s));
    }
}
