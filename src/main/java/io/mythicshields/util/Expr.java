package io.mythicshields.util;

import io.mythicshields.service.ShieldLog;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Выражение-значение: число, строка с арифметикой и <player.x> / <caster.var.*>
 * плейсхолдерами.
 * Ошибки парсинга → безопасное значение по умолчанию.
 */
public class Expr {

    private final String path;
    private final double defaultVal;
    private String raw;
    private boolean custom;

    public Expr(String path, double defaultVal) {
        this.path = path;
        this.defaultVal = defaultVal;
        this.raw = String.valueOf(defaultVal);
    }

    public Expr(String path, String defaultStr) {
        this.path = path;
        this.defaultVal = 0;
        this.raw = defaultStr == null ? "" : defaultStr;
    }

    public static Expr of(String raw) {
        Expr e = new Expr("inline", 0);
        e.raw = raw == null ? "0" : raw;
        return e;
    }

    public void load(ConfigurationSection sec, String sub) {
        String v = sec.getString(sub);
        if (v != null) {
            raw = v;
            custom = true;
        } else if (sec.contains(sub)) {
            raw = String.valueOf(sec.get(sub));
            custom = true;
        }
    }

    public boolean hasCustom() {
        return custom;
    }

    /** Есть ли в выражении плейсхолдеры-переменные вида <имя>. */
    public boolean hasVars() {
        return raw != null && raw.matches(".*<[^<>]+>.*");
    }

    /**
     * Подставляет плейсхолдеры игрока и вычисляет арифметику. При ошибке — дефолт.
     */
    public double eval(Player p, double fallbackOverride) {
        return eval(p, fallbackOverride, null);
    }

    /**
     * То же + подстановка контекстных переменных вида <damage>, <resist> и т.п.
     */
    public double eval(Player p, double fallbackOverride, java.util.Map<String, Double> vars) {
        String s = resolve(p, vars);
        try {
            return evalArith(s);
        } catch (Exception ex) {
            ShieldLog.warn("Не удалось вычислить '" + raw + "' (" + path + ") для " +
                    (p != null ? p.getName() : "?") + " → " + fallbackOverride);
            return fallbackOverride;
        }
    }

    public double eval(Player p) {
        return eval(p, defaultVal);
    }

    /** Строковое значение выражения (для сообщений). */
    public String evalStr(Player p) {
        return resolve(p, null);
    }

    public String resolve(Player p) {
        return resolve(p, null);
    }

    public String resolve(Player p, java.util.Map<String, Double> vars) {
        String s = raw;
        if (vars != null) {
            for (var en : vars.entrySet()) {
                s = s.replace("<" + en.getKey() + ">", String.valueOf(en.getValue()));
            }
        }
        if (p != null) {
            s = s.replace("<player.name>", p.getName());
            s = s.replace("<player.level>", String.valueOf(p.getLevel()));
            s = s.replace("<player.health>", String.format("%.1f", p.getHealth()));
            // MythicMobs-переменные <caster.var.x> берём из player data (MMOCore/Mythic
            // переменные недоступны напрямую — парсим через PAPI если есть)
        }
        return s;
    }

    /** Простой рекурсивный спуск для + - * / со скобками. */
    private static double evalArith(String s) throws Exception {
        // Вырезаем любые оставшиеся <...> плейсхолдеры, чтобы не ломать парсер
        return new Parser(s.replaceAll("<[^>]*>", "0").replaceAll("\\s+", "")).parseExpr();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        double parseExpr() throws Exception {
            double v = parseTerm();
            while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                char op = s.charAt(i++);
                double r = parseTerm();
                v = op == '+' ? v + r : v - r;
            }
            return v;
        }

        double parseTerm() throws Exception {
            double v = parseAtom();
            while (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                char op = s.charAt(i++);
                double r = parseAtom();
                v = op == '*' ? v * r : v / r;
            }
            return v;
        }

        double parseAtom() throws Exception {
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double v = parseExpr();
                if (i >= s.length() || s.charAt(i) != ')')
                    throw new Exception("Ожидалась )");
                i++;
                return v;
            }
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '-'))
                i++;
            if (start == i)
                throw new Exception("Ожидалось число в позиции " + i);
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
