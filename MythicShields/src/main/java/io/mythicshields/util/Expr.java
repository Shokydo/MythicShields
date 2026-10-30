package io.mythicshields.util;

import io.mythicshields.service.ShieldLog;

/**
 * Выражение: число или арифметика ("80 + tier*20").
 * Плейсхолдеры <caster.var.*> заменяются вызывающим кодом до вычисления.
 */
public final class Expr {

    private final String raw;

    private Expr(String raw) { this.raw = raw == null ? "0" : raw; }

    public static Expr of(String raw) { return new Expr(raw); }

    public String raw() { return raw; }

    /** Подставляет замены и вычисляет. При ошибке возвращает fallback и пишет в лог. */
    public double eval(java.util.Map<String, Double> vars, double fallback) {
        String s = raw;
        if (vars != null) {
            for (var e : vars.entrySet()) {
                s = s.replace("<caster.var." + e.getKey() + ">", String.valueOf(e.getValue()));
                s = s.replace("<skill.var." + e.getKey() + ">", String.valueOf(e.getValue()));
                s = s.replace("<target.var." + e.getKey() + ">", String.valueOf(e.getValue()));
                s = s.replace("<" + e.getKey() + ">", String.valueOf(e.getValue()));
            }
        }
        // Любые оставшиеся <...> плейсхолдеры вырезаем — иначе арифметика сломается
        s = s.replaceAll("<[^>]*>", "0");
        try {
            return new Parser(s).parse();
        } catch (Exception ex) {
            ShieldLog.warn("Expr eval failed for '" + raw + "' -> fallback " + fallback);
            return fallback;
        }
    }

    public int evalInt(java.util.Map<String, Double> vars, int fallback) {
        return (int) Math.round(eval(vars, fallback));
    }

    // ------- минимальный рекурсивный парсер арифметики -------

    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) { this.s = s.replaceAll("\\s+", ""); }

        double parse() throws Exception {
            double v = expr();
            if (i < s.length()) throw new Exception("Unexpected char '" + s.charAt(i) + "' at " + i);
            return v;
        }

        private double expr() throws Exception {
            double v = term();
            while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                char op = s.charAt(i++);
                double r = term();
                v = op == '+' ? v + r : v - r;
            }
            return v;
        }

        private double term() throws Exception {
            double v = atom();
            while (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                char op = s.charAt(i++);
                double r = atom();
                v = op == '*' ? v * r : v / r;
            }
            return v;
        }

        private double atom() throws Exception {
            if (s.charAt(i) == '(') {
                i++;
                double v = expr();
                if (i >= s.length() || s.charAt(i) != ')') throw new Exception("expected )");
                i++;
                return v;
            }
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (start == i) throw new Exception("number expected at " + i);
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
