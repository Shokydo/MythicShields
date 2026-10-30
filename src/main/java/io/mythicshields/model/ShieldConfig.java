package io.mythicshields.model;

import io.mythicshields.MythicShieldsPlugin;
import io.mythicshields.util.Expr;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Конфигурация щита из секции Shield предмета. */
public class ShieldConfig {

    /** Поведение при поломке щита (прочность <= BreakThreshold). */
    public enum OnBreak {
        /** Предмет уничтожается (алиас: VANISH). */
        BREAK_ITEM,
        /** Предмет остаётся, блокирует снова только после ремонта / долгого кулдауна. */
        STAY_BROKEN,
        /** Предмет возвращается в инвентарь безопасно. */
        DROP_TO_INVENTORY,
        /** Прочность сразу восстанавливается до Max; щит недоступен на кулдаун. */
        REPAIR
    }

    public enum ResourceType {
        DURABILITY, HP, VARIABLE
    }

    /**
     * COMPONENT — предмет сам становится настоящим ванильным щитом через
     * компонент blocks_attacks (1.21.5+): предмет НИКОГДА не подменяется,
     * кастомная модель/имя/лоре всегда в руке.
     * VANILLA_SWAP — старая подмена на ванильный SHIELD на время блока.
     */
    public enum Mode {
        COMPONENT, VANILLA_SWAP
    }

    private final String itemId;

    public final Expr durabilityMax = new Expr("Durability.Max", 100);
    public final Expr durabilityCost = new Expr("Durability.CostPerBlock", 2);
    public final Expr durabilityThreshold = new Expr("Durability.BreakThreshold", 0);
    public OnBreak onBreak = OnBreak.STAY_BROKEN;

    public ResourceType resourceType = ResourceType.DURABILITY;
    public final Expr variableName = new Expr("Resource.Variable", "shield_resource");
    public final Expr variableCost = new Expr("Resource.Cost", 1);
    public final Expr minResource = new Expr("Resource.MinResource", 1);
    public boolean requireOnBlock = true;

    public final Expr regenAmount = new Expr("Regen.Amount", 0.5);
    public final Expr regenDelay = new Expr("Regen.DelayAfterBlock", 60);
    public final Expr regenInterval = new Expr("Regen.Interval", 40);
    public boolean regenWhileBlocking = false;

    public final Expr raiseCooldownTicks = new Expr("RaiseCooldown.Ticks", 0);
    public final Expr raiseCooldownBreakTicks = new Expr("RaiseCooldown.OnBreakTicks", 100);
    public final Expr raiseCooldownMessage = new Expr("RaiseCooldown.Message", null);

    public final Expr damageReduction = new Expr("Blocking.DamageReduction", 0.3);
    public final Expr damageMultiplier = new Expr("Blocking.DamageMultiplier", 1.0);
    public final Expr knockbackResistance = new Expr("Blocking.KnockbackResistance", 0.5);

    /** true = резист при блоке спадает с Start до Min за Seconds удержания. */
    public boolean resistDecay = false;
    public double decayStartResist = 1.0;
    public double decayMinResist = 0.2;
    /** Тиков полного резиста перед началом спада (StartTicks). */
    public int decayStartTicks = 0;
    /** Тиков, за которые резист доходит до Min (DelayTicks). */
    public int decayDelayTicks = 100;

    /** Поломка щита вызывает ванильный disable-эффект (как от удара топором). */
    public boolean disableLikeVanillaOnBreak = false;

    /** true = щит защищает от всех типов урона (по умолчанию). */
    private boolean protectAll = true;
    /** Типы урона (EntityDamageEvent.DamageCause), от которых щит защищает, если protectAll == false. */
    private final Set<String> protectFrom = new HashSet<>();
    /** Типы урона, которые всегда проходят сквозь щит целиком (даже при protectAll). */
    private final Set<String> ignoreTypes = new HashSet<>();

    public boolean abilityBlockAll = false;
    public final List<String> blockedSkills = new ArrayList<>();
    public final Expr abilityCooldown = new Expr("AbilityBlock.CooldownAfterBlock", 0);

    /**
     * Скиллы щита: строки формата MythicMobs
     *   skillname @ self ~onTakedShieldDamage{Resistance=0.2}
     * Фильтр Resistance — текущий резист в момент удара (0..1; значения >1
     * трактуются как проценты: 20 = 0.2). Скиллы выполняются аддоном
     * (игрок — кастёр и цель), MythicMobs сам триггер не проверяет.
     */
    public final List<String> skills = new ArrayList<>();
    public boolean hasSkills() { return !skills.isEmpty(); }

    /** Как реализуется блокировка состояния щита. */
    public Mode mode = Mode.COMPONENT;

    /**
     * Автоматическое распознавание предмета БЕЗ PDC-ключей (например, предмет
     * от Nexo или взятый из креатива): по материалу и/или CustomModelData.
     */
    public String matchMaterial;
    public final List<String> matchCustomModelData = new ArrayList<>();

    /**
     * Параметры ванильного компонента blocks_attacks (только для Mode=COMPONENT).
     * Звуки по умолчанию выключены; доля урона компонентом не определяется
     * (правила снижения урона задаёт аддон).
     */
    public static final class BlockingComponent {
        /**
         * Задержка активации блока после зажатия ПКМ (ванильное значение 0.25 с).
         * По умолчанию 0 — щит поднимается мгновенно, без «полусекунды» лага.
         */
        public float blockDelaySeconds = 0f;
        public float disableCooldownScale = 1f;
        /** Ванильный disable: топор выводит щит из строя на N тиков (0 = выключено). */
        public int axeDisableTicks = 0;
        /** Звук блока ванильного компонента. null/пусто = без звука (звук задаётся скиллами). */
        public String blockSound = null;
        /** Звук поломки ванильного компонента. null/пусто = без звука. */
        public String disableSound = null;
        public String bypassedBy = "#minecraft:bypasses_shield";
        public float itemDamageThreshold = 0f;
        public float itemDamageBase = 0f;
        public float itemDamageFactor = 0f;
        public final List<DamageReductionSpec> reductions = new ArrayList<>();
    }

    /** Встроенное в предмет правило снижения урона компонента (для Component.DamageReductions). */
    public static final class DamageReductionSpec {
        public final List<String> types = new ArrayList<>(); // пусто = все типы; "#tag" или "minecraft:player"
        public float base = 0f;
        public float factor = 1f;
        public float horizontalBlockingAngle = 90f;
    }

    public BlockingComponent component = new BlockingComponent();

    private ConfigurationSection resistancesSection;

    public ShieldConfig(String itemId) {
        this.itemId = itemId;
    }

    public String getItemId() {
        return itemId;
    }

    /* ------------------------------------------------------- accessors, используемые BlockingService */

    public Expr maxDurability()  { return durabilityMax; }
    public Expr costPerBlock()   { return durabilityCost; }
    public Expr breakThreshold() { return durabilityThreshold; }

    public boolean isRequireOnBlock() { return requireOnBlock; }

    public int regenDelayTicks()     { return (int) Math.max(0, regenDelay.eval(null, 60)); }
    public int regenIntervalTicks()  { return (int) Math.max(1, regenInterval.eval(null, 40)); }
    public boolean isRegenWhileBlocking() { return regenWhileBlocking; }

    public String raiseCooldownMessage() { return raiseCooldownMessage.evalStr(null); }
    public int raiseCooldownTicksValue() { return (int) Math.max(0, raiseCooldownTicks.eval(null, 0)); }
    public int raiseCooldownBreakTicks() { return (int) Math.max(0, raiseCooldownBreakTicks.eval(null, 100)); }

    public Expr damageReduction()     { return damageReduction; }
    public Expr damageMultiplier()    { return damageMultiplier; }
    public Expr knockbackResistance() { return knockbackResistance; }

    public ResourceType resourceType() { return resourceType; }
    public String variableName()       { return variableName.evalStr(null); }
    public Expr minResource()          { return minResource; }
    public boolean requireOnBlock()    { return isRequireOnBlock(); }

    public OnBreak onBreak() { return onBreak; }

    public Mode mode() { return mode; }

    public ConfigurationSection resistances() {
        return resistancesSection;
    }

    /* ------------------------------------------------------- скоуп защиты */

    public boolean isProtectAll() { return protectAll; }

    /** Защищает ли щит от урона данного типа. */
    public boolean protectsAgainst(String damageCause) {
        if (ignoreTypes.contains(damageCause)) return false;
        return protectAll || protectFrom.contains(damageCause);
    }

    /* ------------------------------------------------------- парсинг */

    public static ShieldConfig from(MythicShieldsPlugin plugin, String itemId, ConfigurationSection sec) {
        ShieldConfig c = new ShieldConfig(itemId);
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("Defaults");

        c.durabilityMax.load(sec, "Durability.Max");
        c.durabilityCost.load(sec, "Durability.CostPerBlock");
        c.durabilityThreshold.load(sec, "Durability.BreakThreshold");
        c.onBreak = parseOnBreak(sec.getString("Durability.OnBreak", "STAY_BROKEN"));

        String rt = sec.getString("Resource.Type", "DURABILITY").toUpperCase(Locale.ROOT);
        try {
            c.resourceType = ResourceType.valueOf(rt);
        } catch (Exception ignored) {
        }
        c.variableName.load(sec, "Resource.Variable");
        c.variableCost.load(sec, "Resource.Cost");
        c.minResource.load(sec, "Resource.MinResource");
        c.requireOnBlock = sec.getBoolean("Resource.RequireOnBlock", true);

        c.regenAmount.load(sec, "Regen.Amount");
        c.regenDelay.load(sec, "Regen.DelayAfterBlock");
        c.regenInterval.load(sec, "Regen.Interval");
        c.regenWhileBlocking = sec.getBoolean("Regen.RegenWhileBlocking", false);

        c.raiseCooldownTicks.load(sec, "RaiseCooldown.Ticks");
        // Обратная совместимость: старый AntispamTicks превращается в обычный
        // кулдаун при опускании, если RaiseCooldown.Ticks не задан явно.
        if (!c.raiseCooldownTicks.hasCustom()) {
            c.raiseCooldownTicks.load(sec, "RaiseCooldown.AntispamTicks");
        }
        c.raiseCooldownBreakTicks.load(sec, "RaiseCooldown.OnBreakTicks");
        c.raiseCooldownMessage.load(sec, "RaiseCooldown.Message");

        c.damageReduction.load(sec, "Blocking.DamageReduction");
        c.damageMultiplier.load(sec, "Blocking.DamageMultiplier");
        c.knockbackResistance.load(sec, "Blocking.KnockbackResistance");

        ConfigurationSection decaySec = sec.getConfigurationSection("Blocking.ResistDecay");
        if (decaySec != null) {
            c.resistDecay = decaySec.getBoolean("Enabled", true);
            c.decayStartResist = clamp01(decaySec.getDouble("Start", c.decayStartResist));
            c.decayMinResist = clamp01(decaySec.getDouble("Min", c.decayMinResist));
            // Основные опции (в тиках): StartTicks — тики полного резиста,
            // DelayTicks — тики спада от Start до Min
            int startDur = decaySec.getInt("StartTicks",
                    decaySec.getInt("Start_ticks", decaySec.getInt("StartDuration", -1)));
            if (startDur >= 0) c.decayStartTicks = startDur;
            int delayTicks = decaySec.getInt("DelayTicks", decaySec.getInt("Delay_ticks", -1));
            if (delayTicks >= 0) c.decayDelayTicks = Math.max(1, delayTicks);
            double endDelay = decaySec.getDouble("DelayEndMin", -1);
            if (endDelay >= 0) c.decayDelayTicks = Math.max(1, (int) endDelay);
            // Обратная совместимость со старым Seconds
            double seconds = decaySec.getDouble("Seconds", -1);
            if (seconds > 0 && startDur < 0 && delayTicks < 0 && endDelay < 0) {
                c.decayDelayTicks = Math.max(1, (int) (seconds * 20.0));
            }
        }

        c.disableLikeVanillaOnBreak = sec.getBoolean("Blocking.DisableLikeVanillaOnBreak", c.disableLikeVanillaOnBreak);

        parseProtectFrom(c, sec);
        parseIgnoreTypes(c, sec);
        parseComponent(c, sec);

        String mode = sec.getString("Mode", "COMPONENT");
        try {
            c.mode = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
        }

        c.matchMaterial = sec.getString("Match.Material", null);
        if (c.matchMaterial != null) c.matchMaterial = c.matchMaterial.trim().toUpperCase(Locale.ROOT);
        List<String> cmd = sec.getStringList("Match.CustomModelData");
        if (cmd.isEmpty()) {
            Object single = sec.get("Match.CustomModelData");
            if (single instanceof Number n) cmd = List.of(String.valueOf(n.intValue()));
        }
        for (String s : cmd) {
            c.matchCustomModelData.add(String.valueOf(s).trim());
        }

        c.abilityBlockAll = sec.getBoolean("AbilityBlock.BlockAllWhileBlocking", false);
        c.blockedSkills.addAll(sec.getStringList("AbilityBlock.BlockedSkills"));
        c.abilityCooldown.load(sec, "AbilityBlock.CooldownAfterBlock");
        c.resistancesSection = sec.getConfigurationSection("Blocking.Resistances");

        List<String> skillLines = sec.getStringList("Skills");
        for (String s : skillLines) {
            if (s != null && !s.isBlank()) c.skills.add(s.trim());
        }
        // алиасы: ShieldSkills / Blocking.Skills
        for (String s : sec.getStringList("ShieldSkills")) {
            if (s != null && !s.isBlank()) c.skills.add(s.trim());
        }
        for (String s : sec.getStringList("Blocking.Skills")) {
            if (s != null && !s.isBlank()) c.skills.add(s.trim());
        }

        // Глобальные дефолты из config.yml — только если значения не заданы предметом
        if (root != null) {
            if (!c.durabilityMax.hasCustom())
                c.durabilityMax.load(root, "Durability.Max");
            if (!c.durabilityCost.hasCustom())
                c.durabilityCost.load(root, "Durability.CostPerBlock");
            if (!c.regenAmount.hasCustom())
                c.regenAmount.load(root, "Regen.Amount");
            if (!c.regenInterval.hasCustom())
                c.regenInterval.load(root, "Regen.Interval");
            if (!c.regenDelay.hasCustom())
                c.regenDelay.load(root, "Regen.DelayAfterBlock");
            if (!c.raiseCooldownTicks.hasCustom())
                c.raiseCooldownTicks.load(root, "RaiseCooldown.Ticks");
            if (!c.raiseCooldownBreakTicks.hasCustom())
                c.raiseCooldownBreakTicks.load(root, "RaiseCooldown.OnBreakTicks");
            if (!c.damageReduction.hasCustom())
                c.damageReduction.load(root, "Blocking.DamageReduction");
        }
        return c;
    }

    /**
     * Blocking.ProtectFrom — "ALL" (по умолчанию) или список DamageCause:
     * щит применяет резисты только к перечисленным типам урона,
     * остальной проходит целиком (прочность за него не тратится).
     */
    private static void parseProtectFrom(ShieldConfig c, ConfigurationSection sec) {
        List<String> raw = sec.getStringList("Blocking.ProtectFrom");
        if (raw.isEmpty()) {
            String single = sec.getString("Blocking.ProtectFrom", "ALL");
            raw = single == null ? List.of("ALL") : List.of(single);
        }
        for (String s : raw) {
            String up = s.trim().toUpperCase(Locale.ROOT);
            if (up.equals("ALL")) {
                c.protectAll = true;
                c.protectFrom.clear();
                return;
            }
            c.protectAll = false;
            c.protectFrom.add(up);
        }
    }

    /**
     * Blocking.IgnoreTypes — типы урона, которые всегда пробивают щит целиком,
     * даже если он "ALL". Прочность за них не тратится, блокировка не отменяется.
     */
    private static void parseIgnoreTypes(ShieldConfig c, ConfigurationSection sec) {
        List<String> raw = sec.getStringList("Blocking.IgnoreTypes");
        if (raw.isEmpty()) {
            String single = sec.getString("Blocking.IgnoreTypes");
            if (single != null && !single.isBlank()) raw = List.of(single);
        }
        for (String s : raw) {
            c.ignoreTypes.add(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Shield.Component — параметры ванильного компонента blocks_attacks.
     * Больше ничего не переопределяет поведение аддона: только то, что предмет
     * ванильно умеет блокировать (задержка, звуки, допуск, отключение оружием).
     */
    private static void parseComponent(ShieldConfig c, ConfigurationSection sec) {
        ConfigurationSection comp = sec.getConfigurationSection("Component");
        if (comp == null) return;
        BlockingComponent b = c.component;
        b.blockDelaySeconds = (float) comp.getDouble("BlockDelaySeconds", b.blockDelaySeconds);
        b.disableCooldownScale = (float) comp.getDouble("DisableCooldownScale", b.disableCooldownScale);
        c.component.axeDisableTicks = comp.getInt("AxeDisableTicks", c.component.axeDisableTicks);
        b.blockSound = comp.getString("BlockSound", b.blockSound);
        b.disableSound = comp.getString("DisableSound", b.disableSound);
        b.bypassedBy = comp.getString("BypassedBy", b.bypassedBy);
        ConfigurationSection dmg = comp.getConfigurationSection("ItemDamage");
        if (dmg != null) {
            b.itemDamageThreshold = (float) dmg.getDouble("Threshold", 0);
            b.itemDamageBase = (float) dmg.getDouble("Base", 0);
            b.itemDamageFactor = (float) dmg.getDouble("Factor", 0);
        }
        List<Map<?, ?>> reductions = comp.getMapList("DamageReductions");
        for (Map<?, ?> m : reductions) {
            DamageReductionSpec r = new DamageReductionSpec();
            Object base = m.get("Base");
            Object factor = m.get("Factor");
            Object angle = m.get("Angle");
            if (base instanceof Number n) r.base = n.floatValue();
            if (factor instanceof Number n) r.factor = n.floatValue();
            else if (base != null) r.factor = 0f;
            if (angle instanceof Number n) r.horizontalBlockingAngle = n.floatValue();
            Object type = m.get("Type");
            if (type instanceof String s) r.types.add(s);
            else if (type instanceof List<?> l) {
                for (Object o : l) if (o != null) r.types.add(String.valueOf(o));
            }
            b.reductions.add(r);
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Заданы ли условия автосоответствия предмету (Match.*). */
    public boolean hasMatch() {
        return matchMaterial != null || !matchCustomModelData.isEmpty();
    }

    /** Подходит ли предмет под условия Match.Material / Match.CustomModelData. */
    public boolean matches(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !hasMatch()) return false;
        try {
            var meta = item.getItemMeta();
            if (matchMaterial != null && !item.getType().name().equalsIgnoreCase(matchMaterial)) return false;
            if (!matchCustomModelData.isEmpty()) {
                Integer cmd = null;
                try {
                    var cmc = meta.getCustomModelDataComponent();
                    if (cmc != null && !cmc.getFloats().isEmpty())
                        cmd = Math.round(cmc.getFloats().get(0));
                } catch (Throwable ignored) {}
                if (cmd == null) {
                    try {
                        int legacy = meta.getCustomModelData();
                        if (legacy != 0) cmd = legacy;
                    } catch (Throwable ignored) {}
                }
                if (cmd == null || !matchCustomModelData.contains(String.valueOf(cmd))) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static OnBreak parseOnBreak(String s) {        if (s == null) return OnBreak.STAY_BROKEN;
        switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "VANISH", "BREAK_ITEM" -> { return OnBreak.BREAK_ITEM; }
            case "REPAIR" -> { return OnBreak.REPAIR; }
            case "DROP_TO_INVENTORY" -> { return OnBreak.DROP_TO_INVENTORY; }
            default -> { return OnBreak.STAY_BROKEN; }
        }
    }
}
