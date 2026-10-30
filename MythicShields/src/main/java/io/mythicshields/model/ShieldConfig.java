package io.mythicshields.model;

import io.mythicshields.util.Expr;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Конфигурация одного щита (секция Shield предмета).
 */
public class ShieldConfig {

    public enum OnBreakMode { BREAK_ITEM, STAY_BROKEN, DROP_TO_INVENTORY }
    public enum ResourceType { DURABILITY, HP, VARIABLE }

    private final String itemId;
    private final ConfigurationSection sec;
    private final FileConfiguration defaults;

    public ShieldConfig(String itemId, ConfigurationSection sec, FileConfiguration defaults) {
        this.itemId = itemId;
        this.sec = sec;
        this.defaults = defaults;
    }

    public static ShieldConfig load(String itemId, ConfigurationSection shield, FileConfiguration global) {
        return new ShieldConfig(itemId, shield, global);
    }

    public String getItemId() { return itemId; }

    private double num(String path, double def) {
        if (sec.isSet(path)) return sec.getDouble(path, def);
        ConfigurationSection d = defaults.getConfigurationSection("Defaults." + path.substring(0, path.indexOf('.')));
        if (d != null && d.isSet(path.substring(path.indexOf('.') + 1))) return d.getDouble(path.substring(path.indexOf('.') + 1), def);
        return def;
    }

    private String str(String path, String def) {
        return sec.getString(path, def);
    }

    public boolean isEnabled() { return sec.getBoolean("Enabled", false); }

    /* Durability */
    public Expr maxDurability()   { return Expr.of(str("Durability.Max", String.valueOf(num("Durability.Max", 100)))); }
    public Expr costPerBlock()    { return Expr.of(str("Durability.CostPerBlock", String.valueOf(num("Durability.CostPerBlock", 2)))); }
    public Expr breakThreshold()  { return Expr.of(str("Durability.BreakThreshold", String.valueOf(num("Durability.BreakThreshold", 0)))); }
    public OnBreakMode onBreak() {
        String s = str("Durability.OnBreak", "STAY_BROKEN").toUpperCase();
        try { return OnBreakMode.valueOf(s); } catch (Exception e) { return OnBreakMode.STAY_BROKEN; }
    }

    /* Resource */
    public ResourceType resourceType() {
        String s = str("Resource.Type", "DURABILITY").toUpperCase();
        try { return ResourceType.valueOf(s); } catch (Exception e) { return ResourceType.DURABILITY; }
    }
    public Expr resourceCost()    { return Expr.of(str("Resource.CostPerBlock", "1")); }
    public Expr minResource()     { return Expr.of(str("Resource.MinResource", "1")); }
    public boolean requireOnBlock() { return sec.getBoolean("Resource.RequireOnBlock", true); }
    public String variableName()  { return str("Resource.Variable", "shield_resource"); }

    /* Regen */
    public Expr regenAmount()     { return Expr.of(str("Regen.Amount", String.valueOf(num("Regen.Amount", 0.5)))); }
    public int regenInterval()    { return (int) num("Regen.Interval", 40); }
    public int regenDelay()       { return (int) num("Regen.DelayAfterBlock", 60); }
    public boolean regenWhileBlocking() { return sec.getBoolean("Regen.RegenWhileBlocking", false); }

    /* Raise cooldown */
    public int raiseCooldownTicks()     { return (int) num("RaiseCooldown.Ticks", 0); }
    public int raiseCooldownBreakTicks(){ return (int) num("RaiseCooldown.OnBreakTicks", 100); }
    public String raiseCooldownMessage(){ return str("RaiseCooldown.Message", null); }

    /* Blocking / damage */
    public Expr damageReduction()       { return Expr.of(str("Blocking.DamageReduction", String.valueOf(num("Blocking.DamageReduction", 0.3)))); }
    public Expr damageMultiplier()      { return Expr.of(str("Blocking.DamageMultiplier", String.valueOf(num("Blocking.DamageMultiplier", 1.0)))); }
    public Expr knockbackResistance()   { return Expr.of(str("Blocking.KnockbackResistance", String.valueOf(num("Blocking.KnockbackResistance", 0.5)))); }
    public ConfigurationSection resistances() { return sec.getConfigurationSection("Blocking.Resistances"); }

    /* AbilityBlock */
    public boolean blockAllWhileBlocking() { return sec.getBoolean("AbilityBlock.BlockAllWhileBlocking", false); }
    public java.util.List<String> blockedSkills() { return sec.getStringList("AbilityBlock.BlockedSkills"); }
    public int abilityCooldownAfterBlock() { return (int) num("AbilityBlock.CooldownAfterBlock", 0); }
}
