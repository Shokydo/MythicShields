package io.mythicshields.service;

import io.mythicshields.MythicShieldsPlugin;
import io.mythicshields.model.ShieldConfig;
import io.mythicshields.registry.ShieldRegistry;
import io.mythicshields.util.Expr;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ядро состояния блокировки.
 *
 * Механика "hand-swap": при зажатии ПКМ с кастомным щитом (не vanilla SHIELD) предмет
 * во временно заменяется на ванильный SHIELD → Bukkit сам включает isBlocking()==true.
 * При отпускании/прерывании предмет возвращается обратно.
 *
 * Для vanilla-щитов с Shield.Enabled реализуется только обработка урона/прочности.
 */
public final class BlockingService implements Listener {

    private static final NamespacedKey MM_ID_KEY = new NamespacedKey("mythicmobs", "item_id");

    private final MythicShieldsPlugin plugin;
    private final ShieldRegistry registry;

    /** UUID -> имя руки (HAND / OFF_HAND), в которой поднят щит */
    private final Map<UUID, EquipmentSlot> raisedHand = new ConcurrentHashMap<>();
    /** UUID -> внутренний ID щита */
    private final Map<UUID, String> activeShield = new ConcurrentHashMap<>();
    /** UUID -> сохранённый кастомный предмет (на время блокировки) */
    private final Map<UUID, ItemStack> hiddenItem = new ConcurrentHashMap<>();
    /** UUID -> тик, когда был последний заблокированный удар (для DelayAfterBlock) */
    private final Map<UUID, Long> lastBlockTick = new ConcurrentHashMap<>();
    /** UUID -> кулдаун до (millis) */
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    /** UUID -> текущая прочность */
    private final Map<UUID, Double> durability = new ConcurrentHashMap<>();

    public BlockingService(MythicShieldsPlugin plugin, ShieldRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /* ------------------------------------------------------- *
     *  Публичный API
     * ------------------------------------------------------- */

    public boolean isBlocking(Player p) {
        return activeShield.containsKey(p.getUniqueId());
    }

    public String activeShieldId(Player p) {
        return activeShield.get(p.getUniqueId());
    }

    public double durabilityOf(Player p) {
        return durability.getOrDefault(p.getUniqueId(), 0.0);
    }

    public void addDurability(Player p, double amount) {
        durability.merge(p.getUniqueId(), amount, Double::sum);
    }

    public void clearAll() {
        for (UUID id : new HashSet<>(activeShield.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) lower(p, true);
        }
        activeShield.clear();
        hiddenItem.clear();
        raisedHand.clear();
        durability.clear();
        cooldownUntil.clear();
    }

    public void clear(UUID id) {
        activeShield.remove(id);
        hiddenItem.remove(id);
        raisedHand.remove(id);
        durability.remove(id);
        cooldownUntil.remove(id);
    }

    /* ------------------------------------------------------- *
     *  Raise (ПКМ)
     * ------------------------------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (item == null) return;

        ShieldConfig cfg = resolveConfig(item);
        if (cfg == null) return;

        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (activeShield.containsKey(id)) return; // уже поднят

        // Кулдаун
        Long until = cooldownUntil.get(id);
        if (until != null && System.currentTimeMillis() < until) return;

        final ItemStack held = item.clone();
        final EquipmentSlot hand = e.getHand();
        final ShieldConfig conf = cfg;

        // Подмена слота внутри события запрещена — откладываем
        Bukkit.getScheduler().runTask(plugin, () -> raise(p, conf, held, hand));
    }

    /** Реальный подъём щита. */
    private void raise(Player p, ShieldConfig cfg, ItemStack custom, EquipmentSlot hand) {
        UUID id = p.getUniqueId();
        if (!p.isOnline() || activeShield.containsKey(id)) return;

        ItemStack current = p.getInventory().getItem(hand);
        if (current == null || !current.isSimilar(custom)) return; // предмет уже сменился

        // Если предмет — vanilla SHIELD, просто включаем состояние (Bukkit сам ставит isBlocking)
        hiddenItem.put(id, current.clone());
        raisedHand.put(id, hand);
        activeShield.put(id, cfg.getItemId());

        double max = evalExpr(p, cfg.maxDurability(), 100);
        durability.put(id, max);

        if (current.getType() != Material.SHIELD) {
            // Hand-swap: заменяем на vanilla щит → isBlocking() станет true
            ItemStack shield = new ItemStack(Material.SHIELD);
            ItemMeta meta = shield.getItemMeta();
            meta.setUnbreakable(true);
            meta.displayName(net.kyori.adventure.text.Component.text(" "));
            shield.setItemMeta(meta);
            p.getInventory().setItem(hand, shield);
        }

        p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.2f);
        ShieldLog.debug("Raise " + p.getName() + " (" + cfg.getItemId() + "), durability=" + max);
    }

    /* ------------------------------------------------------- *
     *  Lower
     * ------------------------------------------------------- */

    public void lower(Player p, boolean silent) {
        UUID id = p.getUniqueId();
        String shieldId = activeShield.remove(id);
        if (shieldId == null) return;

        EquipmentSlot hand = raisedHand.remove(id);
        ItemStack custom = hiddenItem.remove(id);
        durability.remove(id);

        if (hand != null && custom != null) {
            ItemStack cur = p.getInventory().getItem(hand);
            if (cur != null && cur.getType() == Material.SHIELD) {
                p.getInventory().setItem(hand, custom);
            } else {
                // предмет сменили вручную — кладём в инвентарь
                p.getInventory().addItem(custom);
            }
        }

        if (!silent) p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.4f, 0.8f);
        ShieldLog.debug("Lower " + p.getName());
    }

    /* ------------------------------------------------------- *
     *  Урон / прочность
     * ------------------------------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        String shieldId = activeShield.get(id);
        if (shieldId == null) return;

        ShieldConfig cfg = registry.get(shieldId);
        if (cfg == null) return;

        // Резисты
        double reduction = evalExpr(p, cfg.damageReduction(), 0.3);
        var resistSec = cfg.resistances();
        if (resistSec != null) {
            String cause = e.getCause().name();
            if (resistSec.isSet(cause)) {
                reduction = evalExpr(p, Expr.of(resistSec.getString(cause)), reduction);
            }
        }
        double mult = evalExpr(p, cfg.damageMultiplier(), 1.0);

        double finalDamage = e.getDamage() * (1.0 - Math.max(0, Math.min(1, reduction))) * mult;
        finalDamage = Math.max(0, finalDamage);
        e.setDamage(finalDamage);

        // Knockback resistance (эмуляция)
        double kb = evalExpr(p, cfg.knockbackResistance(), 0.5);
        if (kb > 0) {
            var vel = p.getVelocity();
            p.setVelocity(vel.multiply(1.0 - Math.min(1, kb)));
        }

        // Расход ресурса
        double cost = evalExpr(p, cfg.costPerBlock(), 2);
        spendResource(p, cfg, cost);

        lastBlockTick.put(id, System.currentTimeMillis());
        p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
    }

    private void spendResource(Player p, ShieldConfig cfg, double cost) {
        UUID id = p.getUniqueId();
        switch (cfg.resourceType()) {
            case DURABILITY -> {
                double cur = durability.getOrDefault(id, 0.0) - cost;
                durability.put(id, cur);
                double threshold = evalExpr(p, cfg.breakThreshold(), 0);
                if (cur <= threshold) {
                    handleBreak(p, cfg);
                }
            }
            case HP -> {
                double hp = p.getHealth() - cost;
                p.setHealth(Math.max(1.0, hp));
            }
            case VARIABLE -> {
                // Переменные MythicMobs недоступны напрямую без их API-сессии — используем PDC игрока
                double cur = p.getPersistentDataContainer()
                        .getOrDefault(key("res_" + cfg.variableName()), PersistentDataType.DOUBLE, 0.0) - cost;
                p.getPersistentDataContainer().set(key("res_" + cfg.variableName()), PersistentDataType.DOUBLE, cur);
                double min = evalExpr(p, cfg.minResource(), 1);
                if (cfg.requireOnBlock() && cur < min) {
                    lower(p, false);
                }
            }
        }
    }

    private void handleBreak(Player p, ShieldConfig cfg) {
        UUID id = p.getUniqueId();
        switch (cfg.onBreak()) {
            case BREAK_ITEM -> {
                lower(p, true);
                EquipmentSlot hand = raisedHand.getOrDefault(id, EquipmentSlot.HAND);
                p.getInventory().setItem(hand, null);
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                // Кулдаун после поломки
                int breakCd = cfg.raiseCooldownBreakTicks();
                cooldownUntil.put(id, System.currentTimeMillis() + breakCd * 50L);
            }
            case STAY_BROKEN -> {
                // щит остаётся, но не блокирует — снимаем активное состояние и даём длинный кулдаун
                lower(p, true);
                cooldownUntil.put(id, System.currentTimeMillis() + cfg.raiseCooldownBreakTicks() * 50L);
            }
            case DROP_TO_INVENTORY -> {
                lower(p, true);
                p.getInventory().addItem(hiddenItem.getOrDefault(id, new ItemStack(Material.SHIELD)));
                cooldownUntil.put(id, System.currentTimeMillis() + cfg.raiseCooldownBreakTicks() * 50L);
            }
        }
        ShieldLog.debug("Shield broken for " + p.getName());
    }

    /* ------------------------------------------------------- *
     *  Регенерация (вызывается из главного класса раз в тик)
     * ------------------------------------------------------- */

    public void tickRegen() {
        for (var entry : new HashMap<>(durability).entrySet()) {
            UUID id = entry.getKey();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;

            String shieldId = activeShield.get(id);
            boolean blocking = shieldId != null;
            ShieldConfig cfg = registry.get(shieldId != null ? shieldId : hiddenShieldId(id));
            if (cfg == null) continue;

            if (blocking && !cfg.regenWhileBlocking()) continue;

            long since = System.currentTimeMillis() - lastBlockTick.getOrDefault(id, 0L);
            if (since < cfg.regenDelay() * 50L) continue;

            double amount = evalExpr(p, cfg.regenAmount(), 0.5);
            addDurability(p, amount);
        }
    }

    private String hiddenShieldId(UUID id) {
        ItemStack item = hiddenItem.get(id);
        if (item == null) return null;
        return readMythicId(item);
    }

    /* ------------------------------------------------------- *
     *  Прерывания блокировки
     * ------------------------------------------------------- */

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent e) {
        if (activeShield.containsKey(e.getPlayer().getUniqueId())) {
            lower(e.getPlayer(), true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (activeShield.containsKey(id)) {
            // Не возвращаем предмет — дроп обрабатывает Minecraft
            activeShield.remove(id);
            raisedHand.remove(id);
            hiddenItem.remove(id);
            durability.remove(id);
        }
    }

    @EventHandler
    public void onGamemodeChange(PlayerGameModeChangeEvent e) {
        if (activeShield.containsKey(e.getPlayer().getUniqueId())) {
            lower(e.getPlayer(), true);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (activeShield.containsKey(e.getPlayer().getUniqueId())) {
            lower(e.getPlayer(), true);
        }
    }

    /* ------------------------------------------------------- *
     *  Helpers
     * ------------------------------------------------------- */

    private ShieldConfig resolveConfig(ItemStack item) {
        // Сначала наш собственный ключ (предметы, размеченные аддоном)
        String own = readKey(item, MythicShieldsPlugin.SHIELD_KEY);
        if (own != null) return registry.get(own);
        // Затем ключ MythicMobs
        String mm = readMythicId(item);
        return mm != null ? registry.get(mm) : null;
    }

    private String readMythicId(ItemStack item) {
        return readKey(item, MM_ID_KEY);
    }

    private String readKey(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    private double evalExpr(Player p, Expr expr, double fallback) {
        return expr.eval(null, fallback);
    }

}
