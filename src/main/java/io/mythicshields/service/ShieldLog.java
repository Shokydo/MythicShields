package io.mythicshields.service;

import io.mythicshields.MythicShieldsPlugin;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/** Логирование в консоль + logs/mythicshields.log */
public final class ShieldLog {

    private static PrintWriter writer;
    private static boolean debug;

    public static synchronized void init(MythicShieldsPlugin plugin) {
        debug = plugin.getConfig().getBoolean("Debug", false);
        try {
            Path dir = plugin.getDataFolder().toPath().resolve("logs");
            Files.createDirectories(dir);
            Path log = dir.resolve("mythicshields.log");
            writer = new PrintWriter(Files.newBufferedWriter(log, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), true);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось открыть лог-файл: " + ex.getMessage());
        }
    }

    public static synchronized void info(String msg) {
        Bukkit.getLogger().info("[MythicShields] " + msg);
        write(msg);
    }

    public static synchronized void warn(String msg) {
        Bukkit.getLogger().warning("[MythicShields] " + msg);
        write("WARN: " + msg);
    }

    public static synchronized void debug(String msg) {
        if (debug) {
            Bukkit.getLogger().info("[MythicShields][DEBUG] " + msg);
            write("DEBUG: " + msg);
        }
    }

    private static void write(String msg) {
        if (writer == null) return;
        writer.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + msg);
    }
}
