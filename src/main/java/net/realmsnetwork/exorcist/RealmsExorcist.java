package net.realmsnetwork.exorcist;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class RealmsExorcist extends JavaPlugin {
    private static final Pattern ERRORS = Pattern.compile("(?i)\\b(exception|error|fatal)\\b|could not load|failed to load|failed to enable|NoClassDefFoundError|ClassNotFoundException|UnsupportedClassVersionError|Could not pass event");

    @Override public void onEnable() {
        saveDefaultConfig();
        getLogger().info("The candles are lit. RealmsExorcist is watching for possessions.");
        if (getConfig().getBoolean("settings.scan-on-enable", true))
            getServer().getScheduler().runTaskLater(this, () -> scan(getServer().getConsoleSender(), false), 40L);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.GOLD + "RealmsExorcist v" + getDescription().getVersion());
            sender.sendMessage(ChatColor.YELLOW + "/exorcist scan " + ChatColor.GRAY + "- inspect plugins, runtime and configs");
            sender.sendMessage(ChatColor.YELLOW + "/exorcist logs " + ChatColor.GRAY + "- inspect latest.log");
            sender.sendMessage(ChatColor.YELLOW + "/exorcist doctor " + ChatColor.GRAY + "- server runtime report");
            sender.sendMessage(ChatColor.YELLOW + "/exorcist reload " + ChatColor.GRAY + "- reload config");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> scan(sender, true);
            case "logs" -> scanLogs(sender);
            case "doctor" -> doctor(sender);
            case "reload" -> {
                if (!sender.hasPermission("realmsexorcist.admin")) { sender.sendMessage(ChatColor.RED + "You lack permission."); return true; }
                reloadConfig(); sender.sendMessage(ChatColor.GREEN + "Config reloaded. Other files were untouched.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /exorcist help.");
        }
        return true;
    }

    private void scan(CommandSender sender, boolean verbose) {
        int errors = 0, warnings = 0, infos = 0;
        sender.sendMessage(ChatColor.GOLD + "☠ RealmsExorcist investigation started...");
        String java = System.getProperty("java.version");
        sender.sendMessage(ChatColor.GREEN + "[INFO] Runtime: " + Bukkit.getName() + " " + Bukkit.getBukkitVersion() + " | Java " + java);
        infos++;
        if (Runtime.version().feature() < 21) { sender.sendMessage(ChatColor.YELLOW + "[WARN] Java 21+ is recommended for modern 1.21 servers."); warnings++; }
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            for (String dep : p.getDescription().getDepend()) if (Bukkit.getPluginManager().getPlugin(dep) == null) {
                sender.sendMessage(ChatColor.RED + "[ERROR] " + p.getName() + " requires missing dependency " + dep); errors++;
            }
            for (String dep : p.getDescription().getSoftDepend()) if (Bukkit.getPluginManager().getPlugin(dep) == null) {
                sender.sendMessage(ChatColor.YELLOW + "[WARN] " + p.getName() + " optionally integrates with missing " + dep); warnings++;
            }
            if (!p.isEnabled()) { sender.sendMessage(ChatColor.RED + "[ERROR] " + p.getName() + " is present but disabled."); errors++; }
        }
        Runtime r = Runtime.getRuntime();
        long used = (r.totalMemory()-r.freeMemory())/1048576, max = r.maxMemory()/1048576;
        sender.sendMessage(ChatColor.GREEN + "[INFO] JVM memory: " + used + " MiB / " + max + " MiB (JVM-wide, not per-plugin)."); infos++;
        Path folder = Bukkit.getPluginsFolder().toPath();
        try (Stream<Path> s = Files.list(folder)) {
            long configs = s.filter(Files::isRegularFile).filter(p -> p.toString().toLowerCase(Locale.ROOT).matches(".*\\.ya?ml$")).count();
            sender.sendMessage(ChatColor.GREEN + "[INFO] Found " + configs + " top-level YAML configuration files."); infos++;
        } catch (IOException ex) { sender.sendMessage(ChatColor.YELLOW + "[WARN] Could not inspect plugins folder: " + ex.getMessage()); warnings++; }
        sender.sendMessage(ChatColor.GOLD + "Verdict: " + (errors == 0 ? ChatColor.GREEN + "No critical possessions" : ChatColor.RED + errors + " critical issue(s)") + ChatColor.GRAY + ", " + warnings + " warning(s).");
        if (verbose || getConfig().getBoolean("settings.show-clean-checks", false)) sender.sendMessage(ChatColor.DARK_GRAY + infos + " informational checks completed.");
        sender.sendMessage(ChatColor.DARK_GRAY + "Read-only diagnostics. Nothing was modified.");
    }

    private void doctor(CommandSender sender) {
        Runtime r = Runtime.getRuntime();
        sender.sendMessage(ChatColor.GOLD + "☠ Server confession");
        sender.sendMessage(ChatColor.GRAY + "Software: " + ChatColor.WHITE + Bukkit.getName() + " " + Bukkit.getVersion());
        sender.sendMessage(ChatColor.GRAY + "Java: " + ChatColor.WHITE + System.getProperty("java.version"));
        sender.sendMessage(ChatColor.GRAY + "Players: " + ChatColor.WHITE + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        sender.sendMessage(ChatColor.GRAY + "Plugins: " + ChatColor.WHITE + Bukkit.getPluginManager().getPlugins().length);
        sender.sendMessage(ChatColor.GRAY + "Memory: " + ChatColor.WHITE + (r.totalMemory()-r.freeMemory())/1048576 + " MiB used / " + r.maxMemory()/1048576 + " MiB max");
        sender.sendMessage(ChatColor.GRAY + "Worlds: " + ChatColor.WHITE + Bukkit.getWorlds().size());
    }

    private void scanLogs(CommandSender sender) {
        if (!getConfig().getBoolean("checks.recent-logs", true)) { sender.sendMessage(ChatColor.YELLOW + "Log scanning disabled in config."); return; }
        Path log = getDataFolder().toPath().getParent().getParent().resolve("logs/latest.log");
        if (!Files.isRegularFile(log)) { sender.sendMessage(ChatColor.RED + "Could not find logs/latest.log at " + log); return; }
        int limit = Math.max(100, getConfig().getInt("settings.scan-log-lines", 2500));
        int max = Math.max(1, getConfig().getInt("settings.max-log-findings", 30));
        sender.sendMessage(ChatColor.GOLD + "☠ Inspecting recent log entries...");
        try {
            List<String> lines = Files.readAllLines(log);
            int start = Math.max(0, lines.size()-limit), found = 0;
            for (int i=start; i<lines.size() && found<max; i++) if (ERRORS.matcher(lines.get(i)).find()) {
                sender.sendMessage(ChatColor.RED + "[LINE " + (i+1) + "] " + ChatColor.GRAY + lines.get(i)); found++;
            }
            sender.sendMessage(found == 0 ? ChatColor.GREEN + "No common error patterns found." : ChatColor.YELLOW + found + " suspicious entries. Review context before acting.");
        } catch (IOException ex) { sender.sendMessage(ChatColor.RED + "Log scan failed: " + ex.getMessage()); }
    }
}
