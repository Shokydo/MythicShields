package io.mythicshields.service;

import io.mythicshields.MythicShieldsPlugin;
import io.mythicshields.model.ShieldConfig;
import io.lumine.mythic.api.skills.Skill;
import io.lumine.mythic.api.skills.SkillCaster;
import io.lumine.mythic.api.skills.SkillTrigger;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.skills.SkillMetadataImpl;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Мост к MythicMobs: регистрирует кастомные триггеры щита и выполняет скиллы
 * из секции {@code Skills} (как {@code Shield.Skills}, так и корневого
 * {@code Skills} предмета MythicMobs):
 *
 * <pre>
 * Shield:
 *   Skills:
 *   - particle{particle=crit;amount=40} @self ~onTakedShieldDamage{Resistance=1}
 *   - sound{s=item.shield.block} @self ~onTakedShieldDamage
 *   - sound{s=block.anvil.break} @self ~onCrackShield
 *   - sound{s=block.note_block.bass} @self ~onStartShieldCooldown
 *   - sound{s=block.amethyst_block.chime} @self ~onFullyShield
 * </pre>
 *
 * Строка после удаления триггера — обычная строка MythicMobs, поэтому
 * поддерживаются и именованные скиллы, и inline-механики.
 */
public final class SkillBridge {

    /** Триггеры, которые аддон регистрирует в MythicMobs. */
    public static final String TAKED_DAMAGE = "TakedShieldDamage";
    public static final String CRACK = "CrackShield";
    public static final String START_COOLDOWN = "StartShieldCooldown";
    public static final String FULLY = "FullyShield";

    public static final List<String> TRIGGERS = List.of(TAKED_DAMAGE, CRACK, START_COOLDOWN, FULLY);

    /**
     * Любой из поддерживаемых триггеров вместе с его фильтром.
     * Захватывает только скобки, принадлежащие триггеру — аргументы механики
     * (particle{...}) остаются нетронутыми.
     */
    private static final Pattern TRIGGER_PATTERN = Pattern.compile(
            "(?i)\\s*~\\s*(?:on)?(takedshielddamage|crackshield|startshieldcooldown|fullyshield)\\s*(?:\\{([^}]*)\\})?");

    private final MythicShieldsPlugin plugin;
    private final Map<String, SkillTrigger> triggers = new ConcurrentHashMap<>();
    /** строка скилла -> уже предупреждены, что она не разобралась. */
    private final Map<String, Boolean> warnedLines = new ConcurrentHashMap<>();
    /** inline-строка -> разобранный MetaSkill (кэш, чтобы не регистрировать заново на каждый удар). */
    private final Map<String, Skill> inlineCache = new ConcurrentHashMap<>();

    public SkillBridge(MythicShieldsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Регистрирует триггеры в MythicMobs (вызывается один раз на onEnable). */
    public void register() {
        for (String name : TRIGGERS) {
            try {
                // Reuse an already registered trigger after a plugin reload instead
                // of failing on MythicMobs' static trigger registry.
                SkillTrigger tr = SkillTrigger.get(name);
                if (tr == null) {
                    tr = SkillTrigger.create(name);
                    tr.register();
                }
                triggers.put(name, tr);
                ShieldLog.info("Триггер ~on" + name + " зарегистрирован в MythicMobs");
            } catch (Throwable t) {
                ShieldLog.warn("Не удалось зарегистрировать триггер ~on" + name + ": "
                        + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            }
        }
    }

    /**
     * Скиллы на урон в щит.
     * @param resist    текущий резист в момент удара (0..1)
     * @param damage    урон удара до уменьшения
     * @param damager   атакующий (может быть null)
     * @param fullBlock удар поглощён щитом целиком (итоговый урон <= 0)
     */
    public void fireShieldSkills(Player p, ShieldConfig cfg, double resist, double damage,
                                 Entity damager, boolean fullBlock) {
        if (cfg == null || !cfg.hasSkills()) return;
        // BlockingService deduplicates the HIGH/MONITOR callbacks by the damage
        // event object. Do not time-deduplicate here: two separate fast hits must
        // each run their configured skill.
        run(p, cfg, TAKED_DAMAGE, resist, damage, damager, fullBlock);
    }

    /**
     * Скиллы на событие щита (без фильтра резиста):
     * {@link #CRACK}, {@link #START_COOLDOWN}, {@link #FULLY}.
     */
    public void fireTrigger(Player p, ShieldConfig cfg, String triggerName) {
        if (cfg == null || !cfg.hasSkills()) return;
        run(p, cfg, triggerName, 0.0, 0.0, null, false);
    }

    private void run(Player p, ShieldConfig cfg, String triggerName, double resist, double damage,
                     Entity damager, boolean fullBlock) {
        MythicBukkit mythic = MythicBukkit.inst();
        var skillManager = mythic.getSkillManager();

        // Кастер: игрок. MM хранит профиль игрока (PlayerData implements SkillCaster).
        SkillCaster caster = null;
        try {
            caster = skillManager.getCaster(BukkitAdapter.adapt(p));
        } catch (Throwable ignored) {
        }
        if (caster == null) {
            try {
                caster = mythic.getPlayerManager().getProfile(BukkitAdapter.adapt(p));
            } catch (Throwable ignored) {
            }
        }
        if (caster == null) {
            ShieldLog.warn("MythicMobs не создал профиль игрока " + p.getName() + " — скиллы щита пропущены");
            return;
        }

        io.lumine.mythic.api.adapters.AbstractEntity self = BukkitAdapter.adapt(p);
        Location origin = p.getLocation();
        SkillTrigger usedTrigger = triggers.get(triggerName);
        if (usedTrigger == null) {
            try {
                usedTrigger = SkillTrigger.get(triggerName);
            } catch (Throwable ignored) {
            }
        }
        if (usedTrigger == null) {
            ShieldLog.warn("Триггер ~on" + triggerName + " не зарегистрирован — скилл игрока "
                    + p.getName() + " пропущен");
            return;
        }

        for (String rawLine : cfg.skills) {
            ParsedSkill parsed = parseSkillLine(rawLine, triggerName, resist, fullBlock);
            if (parsed == null) continue;

            Skill skill = resolveSkill(skillManager, parsed);
            if (skill == null) continue;

            // Цель: @self — сам игрок; @damager/@trigger/@attacker — атакующий (если есть)
            io.lumine.mythic.api.adapters.AbstractEntity targetEntity = self;
            String dir = parsed.targeter().toLowerCase(Locale.ROOT);
            boolean wantDamager = dir.startsWith("damager") || dir.startsWith("trigger") || dir.startsWith("attacker");
            if (wantDamager && damager != null) {
                targetEntity = BukkitAdapter.adapt(damager);
            }

            SkillMetadataImpl meta = new SkillMetadataImpl(usedTrigger, caster, targetEntity,
                    BukkitAdapter.adapt(origin), new java.util.HashSet<>(), new java.util.HashSet<>(), 1.0f);
            meta.setEntityTarget(targetEntity);
            meta.setOrigin(BukkitAdapter.adapt(origin));
            try {
                meta.setMetadata("shield_resist", resist);
                meta.setMetadata("shield_damage", damage);
                meta.setMetadata("shield_trigger", triggerName);
            } catch (Throwable ignored) {
            }

            try {
                skill.execute(meta);
            } catch (Throwable t) {
                ShieldLog.warn("Ошибка выполнения скилла щита '" + parsed.line() + "': "
                        + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
                continue;
            }
            ShieldLog.debug("Скилл щита выполнен: ~on" + triggerName + " '" + parsed.line() + "'"
                    + (TAKED_DAMAGE.equals(triggerName)
                        ? " (resist " + fmt(resist) + (fullBlock ? ", полный блок" : "") + ")"
                        : ""));
        }
    }

    private boolean isMechanic(io.lumine.mythic.core.skills.SkillExecutor skillManager, String name) {
        if (name == null) return false;
        try {
            for (String mechanic : skillManager.getMechanics().keySet()) {
                if (mechanic.equalsIgnoreCase(name)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Находит исполняемый скилл: сохранённый MetaSkill по имени или inline-строку
     * (`particle{...} @self` и т.п.).
     */
    private Skill resolveSkill(io.lumine.mythic.core.skills.SkillExecutor skillManager, ParsedSkill parsed) {
        Skill cached = inlineCache.get(parsed.line());
        if (cached != null) return cached;

        // A mechanic and a saved MetaSkill can share a name. If the token is a
        // registered Mythic mechanic (particle, sound, skill, etc.), parse the
        // full line as an inline skill instead of accidentally resolving a
        // MetaSkill with the same name.
        boolean inlineMechanic = isMechanic(skillManager, parsed.name());
        if (!inlineMechanic) {
            try {
                var named = skillManager.getSkill(parsed.name());
                if (named.isPresent()) return named.get();
            } catch (Throwable ignored) {
            }
        }
        try {
            Skill inline = skillManager.getSkill(plugin.getDataFolder(), List.of(parsed.line())).orElse(null);
            if (inline != null) {
                inlineCache.put(parsed.line(), inline);
                ShieldLog.debug("Inline-скилл щита разобран: '" + parsed.line() + "'");
                return inline;
            }
        } catch (Throwable t) {
            if (warnedLines.putIfAbsent(parsed.line(), true) == null) {
                ShieldLog.warn("Не удалось разобрать строку скилла щита '" + parsed.line() + "': "
                        + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            }
            return null;
        }

        if (warnedLines.putIfAbsent(parsed.line(), true) == null) {
            ShieldLog.warn("Скилл щита не найден и не разобран как inline-механика: '" + parsed.line() + "'");
        }
        return null;
    }

    /* ------------------------------------------------------- парсинг строки */

    private record ParsedSkill(String name, String line, String targeter, String trigger) {
    }

    /**
     * Разбирает строку скилла. Возвращает null, если строка не содержит
     * запрошенный триггер (тогда она для этого события не выполняется)
     * либо не проходит фильтр.
     */
    private ParsedSkill parseSkillLine(String rawLine, String requestedTrigger, double resist, boolean fullBlock) {
        if (rawLine == null) return null;
        String line = rawLine.trim();
        if (line.isEmpty()) return null;

        Matcher m = TRIGGER_PATTERN.matcher(line);
        if (!m.find()) return null; // без триггера аддон строку не выполняет

        String trigger = normalizeTrigger(m.group(1));
        if (!trigger.equals(requestedTrigger)) return null;

        // Фильтр берём только из скобок самого триггера, аргументы механики не трогаем
        Double filter = m.group(2) == null ? null : extractResistance(m.group(2));

        // Убираем триггер вместе с его фильтром, остальное — обычная строка MM
        String rest = (line.substring(0, m.start()) + " " + line.substring(m.end())).trim();
        if (rest.isEmpty()) return null;

        if (filter != null && TAKED_DAMAGE.equals(trigger) && !filterMatches(filter, resist, fullBlock)) {
            ShieldLog.debug("Скилл щита пропущен: Resistance=" + fmt(filter)
                    + ", фактический резист " + fmt(resist) + (fullBlock ? " (полный блок)" : "")
                    + " — строка: " + line);
            return null;
        }

        String name = firstToken(rest);
        String targeter = "self";
        int at = rest.indexOf('@');
        if (at >= 0) {
            String t = rest.substring(at + 1).trim();
            if (!t.isEmpty()) targeter = t;
            if (targeter.startsWith("@")) targeter = targeter.substring(1);
        }
        if (name.isEmpty()) return null;
        return new ParsedSkill(name, rest, targeter, trigger);
    }

    private static String normalizeTrigger(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (s.startsWith("on")) s = s.substring(2);
        return switch (s) {
            case "takedshielddamage" -> TAKED_DAMAGE;
            case "crackshield" -> CRACK;
            case "startshieldcooldown" -> START_COOLDOWN;
            case "fullyshield" -> FULLY;
            default -> raw == null ? "" : raw;
        };
    }

    /** Первый токен: до пробела, '{' или '@'. */
    private static String firstToken(String s) {
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '{' || c == '@') { end = i; break; }
        }
        return s.substring(0, end).trim();
    }

    /** Ищет значение Resistance=... внутри {…}; 20 => 0.2, 100 => 1. */
    private Double extractResistance(String bracesContent) {
        if (bracesContent == null) return null;
        for (String part : bracesContent.split(",|;")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (!key.equals("resistance") && !key.equals("resist")) continue;
            try {
                double v = Double.parseDouble(part.substring(eq + 1).trim().replace(',', '.'));
                if (v > 1.0) v /= 100.0;   // проценты
                return Math.max(0.0, Math.min(1.0, v));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * Сопоставление фильтра с реальностью.
     * Resistance=1 — удар полностью поглощён щитом (полный блок) либо резист 100%;
     * остальные значения — точное совпадение с текущим резистом (±0.005).
     */
    private boolean filterMatches(double filter, double resist, boolean fullBlock) {
        if (filter >= 0.999) return fullBlock || resist >= 0.999;
        return Math.abs(resist - filter) <= 0.005;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
