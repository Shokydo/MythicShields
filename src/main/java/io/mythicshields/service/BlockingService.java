package io.mythicshields.service;

import io.mythicshields.MythicShieldsPlugin;
import io.mythicshields.model.ShieldConfig;
import io.mythicshields.registry.ShieldRegistry;
import io.mythicshields.util.Expr;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние блокировки для кастомных предметов.
 * Для нещитовых предметов (кроме щитового предмета) настоящий raise достигается
 * подменой предмета на vanilla SHIELD (hand-swap), что гарантирует
 * HumanEntity#isBlocking() == true и условие blocking в MythicMobs.
 */
public final class BlockingService implements Listener {

    private static final NamespacedKey MM_ID_KEY = new NamespacedKey("mythicmobs", "item_id");

    private record ShieldCooldownKey(UUID playerId, String shieldId) {}

    private final MythicShieldsPlugin plugin;
    private final ShieldRegistry registry;
    private final SkillBridge skillBridge;
    /** UUID -> рука (HAND / OFF_HAND), в которой поднят щит */
    private final Map<UUID, EquipmentSlot> raisedHand = new ConcurrentHashMap<>();
    /** COMPONENT-режим: UUID -> id щита, которым игрок зажимает ПКМ */
    private final Map<UUID, String> blockingCandidates = new ConcurrentHashMap<>();
    /** UUID -> id активного щита */
    private final Map<UUID, String> activeShield = new ConcurrentHashMap<>();
    /** UUID -> id последнего использованного щита (для регенерации после опускания) */
    private final Map<UUID, String> lastShield = new ConcurrentHashMap<>();
    /** UUID -> сохранённый кастомный предмет */
    private final Map<UUID, ItemStack> hiddenItem = new ConcurrentHashMap<>();
    /** UUID -> время последнего заблокированного удара (мс) */
    private final Map<UUID, Long> lastBlock = new ConcurrentHashMap<>();
    /** UUID -> время последнего списания ресурса за блок (мс) */
    private final Map<UUID, Long> lastCharge = new ConcurrentHashMap<>();
    /** UUID -> время начала удержания блока (мс) — для спада резиста */
    private final Map<UUID, Long> blockStartMs = new ConcurrentHashMap<>();
    /** Player + shield ID -> cooldown end time (ms); other Mythic items remain usable. */
    private final Map<ShieldCooldownKey, Long> cooldownUntil = new ConcurrentHashMap<>();
    /** Player + shield ID whose blocks_attacks component is temporarily removed. */
    private final Set<ShieldCooldownKey> disarmed = ConcurrentHashMap.newKeySet();
    /** UUID -> текущая прочность */
    private final Map<UUID, Double> durability = new ConcurrentHashMap<>();
    /** Damage event identity prevents duplicate callbacks without suppressing rapid separate hits. */
    private final Set<EntityDamageEvent> firedDamageEvents = Collections.newSetFromMap(new WeakHashMap<>());

    public BlockingService(MythicShieldsPlugin plugin, ShieldRegistry registry, SkillBridge skillBridge) {
        this.plugin = plugin;
        this.registry = registry;
        this.skillBridge = skillBridge;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Опрос состояние блокировки: в COMPONENT-режиме подмены нет,
        // настоящий подъем отслеживаем через Player#isBlocking().
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickBlockingState, 1L, 1L);
    }

    /* ------------------------------------------------------- API */

    public boolean isBlocking(Player p) {
        return activeShield.containsKey(p.getUniqueId());
    }

    public boolean isOnCooldown(Player p, ShieldConfig cfg) {
        return cfg != null && cooldownUntil.getOrDefault(cooldownKey(p.getUniqueId(), cfg), 0L)
                > System.currentTimeMillis();
    }

    private long cooldownUntil(UUID playerId, ShieldConfig cfg) {
        return cooldownUntil.getOrDefault(cooldownKey(playerId, cfg), 0L);
    }

    private ShieldCooldownKey cooldownKey(UUID playerId, ShieldConfig cfg) {
        return new ShieldCooldownKey(playerId, cfg.getItemId().toLowerCase(Locale.ROOT));
    }

    public void clearAll() {
        for (UUID id : new HashSet<>(activeShield.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) lower(p, true, false);
        }
        for (ShieldCooldownKey key : new HashSet<>(cooldownUntil.keySet())) {
            Player p = Bukkit.getPlayer(key.playerId());
            ShieldConfig cfg = registry.get(key.shieldId());
            if (p != null && cfg != null) clearItemCooldownVisual(p, cfg);
        }
        for (ShieldCooldownKey key : new HashSet<>(disarmed)) {
            Player p = Bukkit.getPlayer(key.playerId());
            ShieldConfig cfg = registry.get(key.shieldId());
            if (p != null && cfg != null) restoreShieldComponentsInHands(p, cfg);
        }
        activeShield.clear();
        hiddenItem.clear();
        raisedHand.clear();
        durability.clear();
        cooldownUntil.clear();
        disarmed.clear();
        lastBlock.clear();
        lastShield.clear();
        lastCharge.clear();
        blockingCandidates.clear();
        blockStartMs.clear();
        firedDamageEvents.clear();
    }

    public void clear(UUID id) {
        Player p = Bukkit.getPlayer(id);
        for (ShieldCooldownKey key : new HashSet<>(cooldownUntil.keySet())) {
            if (!key.playerId().equals(id)) continue;
            ShieldConfig cfg = registry.get(key.shieldId());
            if (p != null && cfg != null) clearItemCooldownVisual(p, cfg);
        }
        activeShield.remove(id);
        hiddenItem.remove(id);
        raisedHand.remove(id);
        durability.remove(id);
        cooldownUntil.keySet().removeIf(key -> key.playerId().equals(id));
        disarmed.removeIf(key -> key.playerId().equals(id));
        lastBlock.remove(id);
        lastShield.remove(id);
        lastCharge.remove(id);
        blockingCandidates.remove(id);
        blockStartMs.remove(id);
        lastRegen.remove(id);
    }

    /* ------------------------------------------------------- Raise */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (item == null) return;

        ShieldConfig cfg = resolveConfig(item);
        if (cfg == null) return;

        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (activeShield.containsKey(id)) return;

        long until = cooldownUntil(id, cfg);
        if (until > System.currentTimeMillis()) {
            disarmDuringCooldown(p, cfg, until);
            return;
        }

        if (cfg.mode() == ShieldConfig.Mode.COMPONENT) {
            // Кастомный щит: предмет остаётся в руке, настоящий блок делает
            // ванильный компонент blocks_attacks. Только отслеживаем состояние.
            double max = evalExpr(p, cfg.maxDurability(), 100);
            if (!durability.containsKey(id)) durability.put(id, max);
            else durability.put(id, Math.min(durability.get(id), max));
            blockingCandidates.computeIfAbsent(id, k -> cfg.getItemId());
            return;
        }

        double max = evalExpr(p, cfg.maxDurability(), 100);
        // Прочность щита сохраняется между подъёмами (регенерация работает и вне блока);
        // запоминаем максимум, значения выше max не бывает.
        if (!durability.containsKey(id)) {
            durability.put(id, max);
        } else {
            durability.put(id, Math.min(durability.get(id), max));
        }

        final ItemStack held = item.clone();
        final EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
        final ShieldConfig conf = cfg;

        Bukkit.getScheduler().runTask(plugin, () -> raise(p, conf, held, hand));
    }

    /* ------------------------------------------------------- COMPONENT-режим: слежение за состоянием */

    /**
     * Опрашиваем ВСЕХ игроков каждый тик: Player#isBlocking() — единственный
     * надёжный признак зажатого щита; дополнительно смотрим isHandRaised(), чтобы
     * не ждать ванильную задержку активации блока. Ежетиковая проверка — щит
     * поднимается/опускается в пределах 1 тика (50 мс).
     */
    private void tickBlockingState() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean tracked = activeShield.containsKey(id);

            // isBlocking() в 1.21.8 включается только после ванильной задержки блока
            // (block_delay_seconds). Чтобы щит «вставал» с первого тика, дополнительно
            // смотрим на зажатый ПКМ (isHandRaised) — но только если в руке реально щит.
            boolean handRaised = p.isHandRaised() && !p.isSprinting();

            if (tracked) {
                if (p.isBlocking() || (handRaised && shieldIdInHand(p) != null)) continue;
                ShieldConfig cfg = registry.get(activeShield.get(id));
                if (cfg != null && cfg.mode() == ShieldConfig.Mode.COMPONENT) {
                    lower(p, true);
                    ShieldLog.debug("Lower (component) " + p.getName());
                }
                continue;
            }

            boolean blocking = p.isBlocking() || handRaised;
            if (blocking) {
                String shieldId = shieldIdInHand(p);
                if (shieldId == null) continue;
                ShieldConfig cfg = registry.get(shieldId);
                if (cfg == null) continue;

                long until = cooldownUntil(id, cfg);
                if (until > System.currentTimeMillis()) {
                    // Снимаем блокирующий компонент только с конкретного щита на КД.
                    disarmDuringCooldown(p, cfg, until);
                    continue;
                }

                double max = evalExpr(p, cfg.maxDurability(), 100);
                durability.putIfAbsent(id, max);

                activeShield.put(id, shieldId);
                blockStartMs.put(id, System.currentTimeMillis()); // резист при поднятии максимальный
                ShieldLog.debug("Raise (component) " + p.getName() + " (" + shieldId + ")");
            }
        }
    }

    private void raise(Player p, ShieldConfig cfg, ItemStack custom, EquipmentSlot hand) {
        UUID id = p.getUniqueId();
        if (!p.isOnline() || activeShield.containsKey(id)) return;

        ItemStack current = p.getInventory().getItem(hand);
        if (current == null || !current.isSimilar(custom)) return;

        hiddenItem.put(id, current.clone());
        raisedHand.put(id, hand);
        activeShield.put(id, cfg.getItemId());

        ItemStack raisedItem = current;
        if (current.getType() != Material.SHIELD) {
            raisedItem = new ItemStack(Material.SHIELD);
            ItemMeta meta = raisedItem.getItemMeta();
            if (meta != null) {
                meta.setUnbreakable(true);
                meta.setDisplayName("\u00A7r");
                raisedItem.setItemMeta(meta);
            }
        }
        // VANILLA_SWAP previously inherited the vanilla shield's block/break
        // sounds. Apply the configured (silent by default) component to the
        // temporary shield too, then restore the original item in lower().
        if (cfg.mode() == ShieldConfig.Mode.VANILLA_SWAP) {
            plugin.components().applyTemporaryShieldComponent(raisedItem, cfg);
        }
        p.getInventory().setItem(hand, raisedItem);

        blockStartMs.put(id, System.currentTimeMillis()); // резист при поднятии максимальный
        ShieldLog.debug("Raise " + p.getName() + " (" + cfg.getItemId() + ")");
    }

    /* ------------------------------------------------------- Lower */

    public void lower(Player p, boolean silent) {
        lower(p, silent, true);
    }

    /** Lower without applying the normal release cooldown (used for break/axe override/shutdown). */
    private void lower(Player p, boolean silent, boolean applyReleaseCooldown) {
        UUID id = p.getUniqueId();
        String shieldId = activeShield.remove(id);
        if (shieldId == null) return;

        lastShield.put(id, shieldId);
        blockStartMs.remove(id);

        ShieldConfig cfg = registry.get(shieldId);

        EquipmentSlot hand = raisedHand.remove(id);
        ItemStack custom = hiddenItem.remove(id);
        // прочность НЕ сбрасываем — остаётся на регенерацию между подъёмами

        if (hand != null && custom != null) {
            ItemStack cur = p.getInventory().getItem(hand);
            if (cur != null && cur.getType() == Material.SHIELD) {
                p.getInventory().setItem(hand, custom);
            } else {
                p.getInventory().addItem(custom);
            }
        }

        // Кулдаун при обычном опускании. При поломке/disable его задаёт
        // вызывающая сторона отдельно, чтобы не запускать OnStart второй раз.
        if (applyReleaseCooldown && cfg != null) {
            setCooldown(p, cfg, cfg.raiseCooldownTicksValue(), false);
        }

        ShieldLog.debug("Lower " + p.getName());
    }

    /* ------------------------------------------------------- Damage */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        String shieldId = activeShield.get(id);
        if (shieldId == null && p.isBlocking()) {
            // Catch the first hit even if the per-tick state poll has not yet
            // recorded this COMPONENT shield as active.
            shieldId = shieldIdInHand(p);
            if (shieldId != null) {
                activeShield.putIfAbsent(id, shieldId);
                blockStartMs.putIfAbsent(id, System.currentTimeMillis());
            }
        }
        if (shieldId == null) return;

        ShieldConfig cfg = registry.get(shieldId);
        if (cfg == null) {
            ShieldLog.debug("Damage: щит " + shieldId + " не найден в реестре");
            return;
        }

        String cause = e.getCause().name();
        double incomingDamage = e.getDamage();

        // Вариации скоупа защиты: щит может защищать от всего (ALL, по умолчанию),
        // только от перечисленных типов (Blocking.ProtectFrom) или всегда пропускать
        // некоторые типы целиком (Blocking.IgnoreTypes).
        if (!cfg.protectsAgainst(cause)) {
            ShieldLog.debug("Damage " + cause + " pierces shield " + shieldId);
            lastBlock.put(id, System.currentTimeMillis());
            return;
        }

        // Резист при блоке: либо спад от Start до Min по времени удержания
        // (Blocking.ResistDecay), либо статичный DamageReduction
        double reduction = decayedResist(p, cfg, id);

        var resistSec = cfg.resistances();
        if (resistSec != null && resistSec.isSet(e.getCause().name())) {
            reduction = evalExpr(p, Expr.of(resistSec.getString(e.getCause().name())), reduction);
        }
        double mult = evalExpr(p, cfg.damageMultiplier(), 1.0);

        double finalDamage = Math.max(0, e.getDamage() * (1.0 - clamp(reduction)) * mult);
        if (finalDamage <= 0) {
            // Полный блок: отменяем событие — иначе игрок всё равно "краснеет"
            // и получает ванильную анимацию урона без фактического урона.
            e.setCancelled(true);
        } else {
            e.setDamage(finalDamage);
        }
        ShieldLog.debug("Damage " + cause + ": " + String.format("%.1f", incomingDamage)
                + " -> " + String.format("%.1f", finalDamage) + " (resist " + String.format("%.2f", reduction)
                + ", mult " + String.format("%.2f", mult) + ") для " + p.getName());

        double kb = evalExpr(p, cfg.knockbackResistance(), 0.5);
        if (kb > 0) {
            // Отбрасывание сервер применяет ПОСЛЕ события урона, поэтому гасим
            // скорость на тик позже — иначе гасится старая, а не будущая отдача.
            final double factor = clamp(kb);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && !p.isDead()) {
                    org.bukkit.util.Vector v = p.getVelocity();
                    p.setVelocity(new org.bukkit.util.Vector(
                            v.getX() * (1.0 - factor),
                            Math.max(0, v.getY()) * (1.0 - factor), // вертикальный отскок гасим, но не тянем в землю
                            v.getZ() * (1.0 - factor)));
                }
            });
        }

        // Trigger before spending durability so a breaking hit emits OnTakedShieldDamage
        // first and OnCrackShield second. Keep the original hit damage in metadata.
        fireShieldSkills(p, cfg, reduction, incomingDamage, e, finalDamage <= 0);
        chargeCost(p, cfg, incomingDamage, reduction);
        lastBlock.put(id, System.currentTimeMillis());
    }

    /**
     * Полностью заблокированный ванильный удар: событие урона обычно
     * приходит отменённым (finalDamage = 0). Начисляем расход прочности,
     * если щит защищает от этого типа урона.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFullyBlocked(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!e.isCancelled()) return; // незакрытые удары обрабатывает onDamage
        UUID id = p.getUniqueId();
        String shieldId = activeShield.get(id);
        if (shieldId == null && !p.isBlocking()) return;
        shieldId = shieldId != null ? shieldId : shieldIdInHand(p);
        if (shieldId == null) return;

        ShieldConfig cfg = registry.get(shieldId);
        if (cfg == null) return;
        if (!cfg.protectsAgainst(e.getCause().name())) {
            // урон вне скоупа защиты не должен и здесь списывать прочность
            if (lastBlock.getOrDefault(id, 0L) < System.currentTimeMillis() - 50) {
                lastBlock.put(id, System.currentTimeMillis());
            }
            return;
        }
        // A cancelled event is still a shield hit. Fire the skill independently
        // of durability-cost deduplication so rapid hits do not lose their trigger.
        double resistFull = decayedResist(p, cfg, id);
        var resistSecFull = cfg.resistances();
        if (resistSecFull != null && resistSecFull.isSet(e.getCause().name())) {
            resistFull = evalExpr(p, Expr.of(resistSecFull.getString(e.getCause().name())), resistFull);
        }
        fireShieldSkills(p, cfg, resistFull, e.getDamage(), e, true);
        maybeChargeCost(p, cfg, e.getDamage(), resistFull);
        lastBlock.put(id, System.currentTimeMillis());
    }

    /** Выстреливает скиллы щита (дамагер — если урон пришёл от сущности). */
    private void fireShieldSkills(Player p, ShieldConfig cfg, double resist, double damage,
                                  EntityDamageEvent e, boolean fullBlock) {
        // The same event reaches HIGH and MONITOR handlers. Deduplicate by event
        // identity rather than time, so two rapid independent hits both trigger.
        if (!firedDamageEvents.add(e)) return;

        Entity damager = null;
        if (e instanceof EntityDamageByEntityEvent by) {
            var d = by.getDamager();
            // взрывы/стрелы берём через источник снаряда
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) d = shooter;
            if (d instanceof Entity ent) damager = ent;
        }
        skillBridge.fireShieldSkills(p, cfg, resist, damage, damager, fullBlock);
    }

    /** Списывает ресурс за блок не чаще раза за тик (дедуп между обработчиками). */
    private void maybeChargeCost(Player p, ShieldConfig cfg, double damage, double resist) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastCharge.getOrDefault(id, 0L);
        if (now - last < 30) return; // уже списали в этом тике
        lastCharge.put(id, now);
        chargeCost(p, cfg, damage, resist);
    }

    /**
     * Списание за блок. CostPerBlock поддерживает плейсхолдеры <damage> (урон удара)
     * и <resist> (текущий резист). Например CostPerBlock: '<damage> / 10 * <resist>'
     * при уроне 10 и резисте 0.5 даст расход 5.
     */
    private void chargeCost(Player p, ShieldConfig cfg, double damage, double resist) {
        UUID id = p.getUniqueId();
        double cost;
        if (cfg.costPerBlock().hasVars()) {
            java.util.Map<String, Double> vars = new HashMap<>();
            vars.put("damage", damage);
            vars.put("resist", resist);
            cost = cfg.costPerBlock().eval(p, 2, vars);
        } else {
            cost = evalExpr(p, cfg.costPerBlock(), 2);
        }
        // дедуп: полный блок обрабатывают оба обработчика (HIGH + MONITOR);
        // помечаем тик, чтобы MONITOR-обработчик не списал дважды
        lastCharge.put(id, System.currentTimeMillis());
        spendResource(p, cfg, cost);
    }

    private void spendResource(Player p, ShieldConfig cfg, double cost) {
        UUID id = p.getUniqueId();
        switch (cfg.resourceType()) {
            case DURABILITY -> {
                double cur = durability.getOrDefault(id, 0.0) - cost;
                durability.put(id, cur);
                double threshold = evalExpr(p, cfg.breakThreshold(), 0);
                if (cur <= threshold) handleBreak(p, cfg);
            }
            case HP -> p.setHealth(Math.max(1.0, p.getHealth() - cost));
            case VARIABLE -> {
                NamespacedKey key = new NamespacedKey(plugin, "res_" + cfg.variableName().toLowerCase());
                double cur = p.getPersistentDataContainer()
                        .getOrDefault(key, PersistentDataType.DOUBLE, 0.0) - cost;
                p.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, cur);
                double min = evalExpr(p, cfg.minResource(), 1);
                if (cfg.requireOnBlock() && cur < min) lower(p, false);
            }
        }
    }

    private void handleBreak(Player p, ShieldConfig cfg) {
        UUID id = p.getUniqueId();
        boolean disabledLikeVanilla = cfg.disableLikeVanillaOnBreak;
        switch (cfg.onBreak()) {
            case BREAK_ITEM -> {
                EquipmentSlot hand = raisedHand.getOrDefault(id, EquipmentSlot.HAND);
                lower(p, true, false);
                p.getInventory().setItem(hand, null);
                setCooldown(p, cfg, cfg.raiseCooldownBreakTicks(), true);
            }
            case STAY_BROKEN -> {
                // Щит уходит в кулдаун и НЕ восстанавливает прочность —
                // после кулдауна регенерация сама отстроит его (если Regen задан),
                // иначе он «сломан» пока прочность не восстановится иным путём.
                lower(p, true, false);
                if (disabledLikeVanilla) {
                    // Ванильный эффект disable: как будто щит сломали топором
                    applyVanillaDisable(p);
                }
                setCooldown(p, cfg, cfg.raiseCooldownBreakTicks(), true);
            }
            case REPAIR -> {
                // Прочность сразу до максимума; щит недоступен на кулдаун
                double max = evalExpr(p, cfg.maxDurability(), 100);
                lower(p, true, false);
                durability.put(id, max);
                setCooldown(p, cfg, cfg.raiseCooldownBreakTicks(), true);
            }
            case DROP_TO_INVENTORY -> {
                // Дубликат со STAY_BROKEN (предмет уже в инвентаре в COMPONENT-режиме):
                // опустить и дать кулдаун без восстановления прочности.
                lower(p, true, false);
                setCooldown(p, cfg, cfg.raiseCooldownBreakTicks(), true);
            }
        }
        ShieldLog.debug("Shield broken for " + p.getName());
        // Триггер поломки: звуки/частицы полностью на совести конфига
        skillBridge.fireTrigger(p, cfg, SkillBridge.CRACK);
        // REPAIR restores durability immediately. With no break cooldown there
        // will be no cooldown-completion callback to emit OnFullyShield.
        if (cfg.onBreak() == ShieldConfig.OnBreak.REPAIR && cfg.raiseCooldownBreakTicks() <= 0) {
            skillBridge.fireTrigger(p, cfg, SkillBridge.FULLY);
        }
    }

    /* ------------------------------------------------------- Regen */

    /** UUID -> тик последней регенерации (для Regen.Interval). */
    private final Map<UUID, Long> lastRegen = new ConcurrentHashMap<>();

    public void tickRegen() {
        long now = System.currentTimeMillis();
        for (var entry : new HashMap<>(durability).entrySet()) {
            UUID id = entry.getKey();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;

            String shieldId = activeShield.get(id);
            ShieldConfig cfg;
            boolean blocking = shieldId != null;
            if (shieldId != null) {
                cfg = registry.get(shieldId);
            } else {
                ItemStack item = p.getInventory().getItemInMainHand();
                cfg = item == null ? null : resolveConfig(item);
                if (cfg == null) {
                    ItemStack off = p.getInventory().getItemInOffHand();
                    cfg = off == null ? null : resolveConfig(off);
                }
                if (cfg == null) cfg = registry.get(lastShield.get(id));
            }
            if (cfg == null) continue;

            if (blocking && !cfg.isRegenWhileBlocking()) continue;

            long since = now - lastBlock.getOrDefault(id, 0L);
            if (since < cfg.regenDelayTicks() * 50L) continue;

            // Regen.Interval: период регенерации в тиках
            long intervalMs = cfg.regenIntervalTicks() * 50L;
            long last = lastRegen.getOrDefault(id, 0L);
            if (now - last < intervalMs) continue;
            lastRegen.put(id, now);

            double max = evalExpr(p, cfg.maxDurability(), 100);
            double cur = durability.getOrDefault(id, max);
            double amount = evalExpr(p, cfg.regenAmount, 0.5);
            double next = Math.min(max, cur + amount);
            if (next == cur) continue; // полная прочность — не спамим лог
            durability.put(id, next);
            ShieldLog.debug("Regen " + p.getName() + " (" + cfg.getItemId() + "): "
                    + String.format("%.1f", cur) + " -> " + String.format("%.1f", next));
            // ~onFullyShield: прочность восстановилась до максимума, когда щит уже доступен.
            boolean onCooldown = cooldownUntil(id, cfg) > now || disarmed.contains(cooldownKey(id, cfg));
            if (next >= max && cur < max && !onCooldown) {
                skillBridge.fireTrigger(p, cfg, SkillBridge.FULLY);
            }
        }
    }

    /* ------------------------------------------------------- Interrupts */

    /**
     * Ванильный disable топором (Component.AxeDisableTicks > 0):
     * удар топором по блокирующему игроку выводит щит из строя на N тиков —
     * щит опускается и не может быть поднят, пока идёт КД.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAxeHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        UUID id = p.getUniqueId();
        if (!activeShield.containsKey(id) && !p.isBlocking()) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || !weapon.getType().name().endsWith("_AXE")) return;

        ShieldConfig cfg = null;
        String shieldId = activeShield.get(id);
        if (shieldId != null) cfg = registry.get(shieldId);
        if (cfg == null) cfg = shieldIdInHand(p) == null ? null : registry.get(shieldIdInHand(p));
        if (cfg == null) return;

        int ticks;
        if (cfg.component.axeDisableTicks > 0) {
            ticks = cfg.component.axeDisableTicks;
        } else {
            ticks = axeDisableTicksDefault();
        }
        if (ticks <= 0) return;
        lower(p, true, false);
        setCooldown(p, cfg, ticks, false);
        ShieldLog.debug("Axe-disable " + ticks + "t для " + p.getName());
    }

    /**
     * Ставит настоящий кулдаун на щит: с этого момента его физически нельзя
     * поднять. Вызывается сразу при опускании щита (и при поломке/disable).
     */
    /**
     * @param fromBreak true — КД начислен поломкой (тогда ~onCrackShield уже сыграл,
     *                  а ~onStartShieldCooldown не вызывается)
     */
    private void setCooldown(Player p, ShieldConfig cfg, int ticks, boolean fromBreak) {
        if (ticks <= 0 || cfg == null) return;
        UUID id = p.getUniqueId();
        ShieldCooldownKey key = cooldownKey(id, cfg);
        long now = System.currentTimeMillis();
        long previousUntil = cooldownUntil.getOrDefault(key, 0L);
        boolean alreadyCoolingDown = previousUntil > now;
        long requestedUntil = now + ticks * 50L;
        // Never shorten a cooldown already running for this shield.
        long until = Math.max(previousUntil, requestedUntil);
        cooldownUntil.put(key, until);
        ShieldLog.debug("Cooldown " + ticks + "t" + (fromBreak ? " (break)" : "")
                + " для " + p.getName() + " / " + cfg.getItemId());
        if (Bukkit.isPrimaryThread()) {
            disarmDuringCooldown(p, cfg, until);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> disarmDuringCooldown(p, cfg, until));
        }
        // Fire only on an actual transition into cooldown. A break cooldown is
        // intentionally excluded; OnCrackShield handles that event instead.
        if (!fromBreak && !alreadyCoolingDown) {
            skillBridge.fireTrigger(p, cfg, SkillBridge.START_COOLDOWN);
        }
    }

    /** Снимает блокирующий компонент и ставит визуальный КД только на этот щит. */
    private void disarmDuringCooldown(Player p, ShieldConfig cfg, long until) {
        ShieldCooldownKey key = cooldownKey(p.getUniqueId(), cfg);
        if (disarmed.add(key)) {
            ShieldLog.debug("Disarm на КД для " + p.getName() + " / " + cfg.getItemId());
            scheduleDisarmRestore(p, key);
        }
        if (cfg.mode() == ShieldConfig.Mode.COMPONENT) {
            removeComponentFromHands(p, cfg);
        }
        applyItemCooldownVisual(p, cfg, until);
    }

    /** Возвращает компонент только щиту с истёкшим КД. */
    private void scheduleDisarmRestore(Player p, ShieldCooldownKey key) {
        long until = cooldownUntil.getOrDefault(key, 0L);
        long remaining = Math.max(1, until - System.currentTimeMillis());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) {
                disarmed.remove(key);
                return;
            }
            if (cooldownUntil.getOrDefault(key, 0L) > System.currentTimeMillis()) {
                // КД продлили (например, disable сменился поломкой) — ждём дальше.
                scheduleDisarmRestore(p, key);
                return;
            }

            long expiredUntil = cooldownUntil.getOrDefault(key, 0L);
            cooldownUntil.remove(key, expiredUntil);
            disarmed.remove(key);
            ShieldConfig cfg = registry.get(key.shieldId());
            if (cfg != null && cfg.mode() == ShieldConfig.Mode.COMPONENT) {
                restoreShieldComponentsInHands(p, cfg);
            }
            ShieldLog.debug("КД кончился для " + p.getName() + " / " + key.shieldId());

            // Cooldown completion is not a full restore if durability is still
            // below Max. In that case tickRegen fires the trigger on full restore.
            if (cfg != null && isFullyRestored(p, key.playerId(), cfg)) {
                skillBridge.fireTrigger(p, cfg, SkillBridge.FULLY);
            }
        }, Math.max(1, remaining / 50L + 1));
    }

    /** Визуальный откат только предметов, распознанных как этот щит. */
    private void applyItemCooldownVisual(Player p, ShieldConfig cfg, long untilMs) {
        long remainingMs = untilMs - System.currentTimeMillis();
        if (remainingMs <= 0 || remainingMs > 3_600_000) return;
        int ticks = (int) Math.max(1, remainingMs / 50L);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            ItemStack item = p.getInventory().getItem(slot);
            ShieldConfig heldCfg = item == null ? null : resolveConfig(item);
            if (heldCfg == null || !sameShield(heldCfg, cfg)) continue;
            if (!ensureShieldCooldownGroup(item, cfg)) continue;
            p.getInventory().setItem(slot, item); // persist USE_COOLDOWN group before applying timer
            try {
                p.setCooldown(item, ticks); // Paper API: scoped to the item's cooldown group
            } catch (Throwable t) {
                ShieldLog.warn("Не удалось показать КД на щите " + cfg.getItemId() + ": " + t);
            }
        }
    }

    private void clearItemCooldownVisual(Player p, ShieldConfig cfg) {
        Key group = shieldCooldownGroup(cfg);
        for (ItemStack item : p.getInventory().getContents()) {
            ShieldConfig heldCfg = item == null ? null : resolveConfig(item);
            if (heldCfg == null || !sameShield(heldCfg, cfg)) continue;
            try {
                UseCooldown cooldown = item.getData(DataComponentTypes.USE_COOLDOWN);
                if (cooldown != null && group.equals(cooldown.cooldownGroup())) {
                    p.setCooldown(item, 0);
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Item cooldowns are grouped by material unless the item has its own cooldown group.
     * Give each Mythic shield ID a distinct group so same-material Mythic items are unaffected.
     */
    private boolean ensureShieldCooldownGroup(ItemStack item, ShieldConfig cfg) {
        try {
            UseCooldown current = item.getData(DataComponentTypes.USE_COOLDOWN);
            Key group = shieldCooldownGroup(cfg);
            if (current != null && group.equals(current.cooldownGroup())) return true;
            float seconds = current == null ? 0.05f : Math.max(0.05f, current.seconds());
            item.setData(DataComponentTypes.USE_COOLDOWN,
                    UseCooldown.useCooldown(seconds).cooldownGroup(group).build());
            return true;
        } catch (Throwable t) {
            ShieldLog.warn("Не удалось настроить отдельную группу КД для щита " + cfg.getItemId() + ": " + t);
            return false;
        }
    }

    private Key shieldCooldownGroup(ShieldConfig cfg) {
        String id = cfg.getItemId().toLowerCase(Locale.ROOT);
        String safeId = id.replaceAll("[^a-z0-9._/-]", "_");
        String hash = Integer.toUnsignedString(id.hashCode(), 36);
        return Key.key("mythicshields", "shield/" + safeId + "-" + hash);
    }

    private boolean sameShield(ShieldConfig a, ShieldConfig b) {
        return a.getItemId().equalsIgnoreCase(b.getItemId());
    }

    private void removeComponentFromHands(Player p, ShieldConfig target) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            ItemStack item = p.getInventory().getItem(slot);
            ShieldConfig heldCfg = item == null ? null : resolveConfig(item);
            if (heldCfg == null || !sameShield(heldCfg, target) || !plugin.components().hasComponent(item)) continue;
            plugin.components().removeComponent(item);
            p.getInventory().setItem(slot, item); // write-back
        }
    }

    private void restoreShieldComponentsInHands(Player p, ShieldConfig target) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            ItemStack item = p.getInventory().getItem(slot);
            ShieldConfig heldCfg = item == null ? null : resolveConfig(item);
            if (heldCfg == null || !sameShield(heldCfg, target) || heldCfg.mode() != ShieldConfig.Mode.COMPONENT) continue;
            if (plugin.components().applyComponentTo(item)) {
                p.getInventory().setItem(slot, item); // write-back
            }
        }
    }

    private int axeDisableTicksDefault() {
        return plugin.getConfig().getInt("Defaults.Component.AxeDisableTicks", 0);
    }

    /**
     * Ванильный disable-эффект: как будто щит сломали топором.
     * Клиент показывает анимацию сломанного щита — предмет на 1 тик снимается
     * с руки и возвращается, при этом предмет остаётся, но заблокирован кулдауном.
     */
    private void applyVanillaDisable(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == org.bukkit.Material.AIR) return;
        p.getInventory().setItemInMainHand(null);
        Bukkit.getScheduler().runTask(plugin, () -> p.getInventory().setItemInMainHand(hand));
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent e) {
        if (activeShield.containsKey(e.getPlayer().getUniqueId())) {
            lower(e.getPlayer(), true);
        }
        blockingCandidates.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (activeShield.containsKey(id)) {
            activeShield.remove(id);
            raisedHand.remove(id);
            hiddenItem.remove(id);
        }
        durability.remove(id);
        lastShield.remove(id);
        lastCharge.remove(id);
        blockingCandidates.remove(id);
    }

    @EventHandler
    public void onGamemodeChange(PlayerGameModeChangeEvent e) {
        if (activeShield.containsKey(e.getPlayer().getUniqueId())) {
            lower(e.getPlayer(), true);
        }
        blockingCandidates.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (activeShield.containsKey(e.getPlayer().getUniqueId())) {
            lower(e.getPlayer(), true);
        }
        blockingCandidates.remove(e.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------- Helpers */

    private String shieldIdInHand(Player p) {
        if (p.hasActiveItem()) {
            return shieldIdInSlot(p, p.getActiveItemHand());
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            String shieldId = shieldIdInSlot(p, slot);
            if (shieldId != null) return shieldId;
        }
        return null;
    }

    private String shieldIdInSlot(Player p, EquipmentSlot slot) {
        ItemStack item = p.getInventory().getItem(slot);
        ShieldConfig cfg = item == null ? null : resolveConfig(item);
        return cfg != null && cfg.mode() == ShieldConfig.Mode.COMPONENT ? cfg.getItemId() : null;
    }

    private ShieldConfig resolveConfig(ItemStack item) {
        String own = readKey(item, MythicShieldsPlugin.SHIELD_KEY);
        ShieldConfig cfg;
        if (own != null) cfg = registry.get(own);
        else {
            String mm = readMythicId(item);
            cfg = mm != null ? registry.get(mm) : registry.match(item);
        }
        return cfg;
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

    private double evalExpr(Player p, Expr expr, double fallback) {
        return expr.eval(null, fallback);
    }

    private boolean isFullyRestored(Player p, UUID id, ShieldConfig cfg) {
        if (cfg.resourceType() != ShieldConfig.ResourceType.DURABILITY) return true;
        double max = evalExpr(p, cfg.maxDurability(), 100);
        return durability.getOrDefault(id, max) >= max;
    }

    /** Реальный clamp + расчёт текущего резиста с учётом спада при удержании. */
    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Текущий резист игрока при блоке.
     * Если Blocking.ResistDecay включён — резист спадает линейно с Start до Min
     * за Seconds удержания блока (каждый тик удержания = чуть ниже резист).
     * Иначе — статичный Blocking.DamageReduction.
     */
    private double decayedResist(Player p, ShieldConfig cfg, UUID id) {
        if (!cfg.resistDecay) {
            return evalExpr(p, cfg.damageReduction(), 0.3);
        }
        long start = blockStartMs.getOrDefault(id, System.currentTimeMillis());
        double elapsedTicks = (System.currentTimeMillis() - start) / 50.0;
        double from = clamp(cfg.decayStartResist);
        double to = clamp(cfg.decayMinResist);
        if (to > from) to = from; // Min не может быть выше Start
        if (elapsedTicks <= cfg.decayStartTicks) return from;   // фаза полного резиста
        double t = Math.min(1.0, (elapsedTicks - cfg.decayStartTicks) / cfg.decayDelayTicks);
        return from - (from - to) * t;                                  // плавный спад до Min
    }

}
