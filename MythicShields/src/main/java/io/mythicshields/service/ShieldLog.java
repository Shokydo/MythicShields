package io.mythicshields.service;

import io.mythicshields.MythicShieldsPlugin;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Логирование в консоль + plugins/MythicShields/logs/mythicshields.log */
public final class ShieldLog {

    private static PrintWriter writer;
    private static boolean debug;

    private ShieldLog() {}

    public static synchronized void init(MythicShieldsPlugin plugin) {
        debug = plugin.getConfig().getBoolean("Debug", false);
        try {
            Path dir = plugin.getDataFolder().toPath().resolve("logs");
            Files.createDirectories(dir);
            writer = new PrintWriter(Files.newBufferedWriter(dir.resolve("mythicshields.log"),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND), true);
        } catch (IOException ex) {
            plugin.getLogger().warning("Cannot open log file: " + ex.getMessage());
        }
    }

    public static synchronized void info(String msg)  { log("INFO", msg); }
    public static synchronized void warn(String msg)  { log("WARN", msg); }

    public static synchronized void debug(String msg) {
        if (debug) log("DEBUG", msg);
    }

    private static void log(String level, String msg) {
        Bukkit.getLogger().info("[MythicShields][" + level + "] " + msg);
        if (writer != null) {
            writer.println(LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " [" + level + "] " + msg);
        }
    }
}
