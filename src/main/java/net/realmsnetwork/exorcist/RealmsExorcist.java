package net.realmsnetwork.exorcist;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RealmsExorcist extends JavaPlugin {
    private static final Pattern LOG_FAILURE = Pattern.compile(
            "(?i)(\\b(exception|error|fatal)\\b|\\bwarn(?:ing)?\\b|could not load|failed to (?:load|enable|disable)|no such method|no class def found|classnotfoundexception|unsupportedclassversionerror|could not pass event|[a-z0-9_.]+exception:)"
    );
    private static final Pattern EXCEPTION_NAME = Pattern.compile("([\\w.$]+(?:Exception|Error))(?::\\s*(.*))?");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("RealmsExorcist enabled. Diagnostics are ready.");
        if (getConfig().getBoolean("settings.scan-on-enable", true)) {
            getServer().getScheduler().runTaskLater(this,
                    () -> runScan(getServer().getConsoleSender(), "all"), 40L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("realmsexorcist.use")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> runScan(sender, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "all");
            case "logs" -> scanLogs(sender);
            case "doctor" -> doctor(sender);
            case "repair" -> repair(sender, args);
            case "reload" -> {
                if (!requireAdmin(sender)) return true;
                reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "RealmsExorcist configuration reloaded.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /exorcist help.");
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "RealmsExorcist " + getDescription().getVersion());
        sender.sendMessage(ChatColor.YELLOW + "/exorcist scan [all|plugins|configs|compatibility|proxy] " + ChatColor.GRAY + "- run diagnostics");
        sender.sendMessage(ChatColor.YELLOW + "/exorcist logs " + ChatColor.GRAY + "- group recent log failures");
        sender.sendMessage(ChatColor.YELLOW + "/exorcist doctor " + ChatColor.GRAY + "- show server runtime details");
        sender.sendMessage(ChatColor.YELLOW + "/exorcist repair own-config confirm " + ChatColor.GRAY + "- restore this plugin's defaults");
        sender.sendMessage(ChatColor.YELLOW + "/exorcist reload " + ChatColor.GRAY + "- reload this plugin's config");
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("realmsexorcist.admin")) return true;
        sender.sendMessage(ChatColor.RED + "You need realmsexorcist.admin for that action.");
        return false;
    }

    private void runScan(CommandSender sender, String scope) {
        if (!Set.of("all", "plugins", "configs", "compatibility", "proxy").contains(scope)) {
            sender.sendMessage(ChatColor.RED + "Unknown scan scope. Use all, plugins, configs, compatibility or proxy.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "RealmsExorcist diagnostic report");
        int issues = 0;
        if (scope.equals("all") || scope.equals("plugins")) issues += scanPlugins(sender);
        if (scope.equals("all") || scope.equals("compatibility")) issues += scanCompatibility(sender);
        if (scope.equals("all") || scope.equals("configs")) issues += scanConfigurations(sender);
        if (scope.equals("all") || scope.equals("proxy")) issues += scanProxy(sender);
        if (scope.equals("all") || scope.equals("plugins")) issues += scanMemory(sender);
        sender.sendMessage(ChatColor.GOLD + "Report complete: " + (issues == 0
                ? ChatColor.GREEN + "no critical findings"
                : ChatColor.YELLOW + issues + " finding(s)") + ChatColor.GRAY + ". No server files were changed.");
    }

    private int scanPlugins(CommandSender sender) {
        if (!getConfig().getBoolean("checks.dependencies", true)) return 0;
        sender.sendMessage(ChatColor.AQUA + "Plugin dependency and state checks");
        int findings = 0;
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            PluginDescriptionFile description = plugin.getDescription();
            if (!plugin.isEnabled()) {
                sender.sendMessage(ChatColor.RED + "[ERROR] " + plugin.getName() + " is installed but disabled.");
                findings++;
            }
            for (String dependency : description.getDepend()) {
                Plugin target = Bukkit.getPluginManager().getPlugin(dependency);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "[ERROR] " + plugin.getName() + " requires missing plugin " + dependency + ".");
                    findings++;
                } else if (!target.isEnabled()) {
                    sender.sendMessage(ChatColor.RED + "[ERROR] " + plugin.getName() + " requires " + dependency + ", but it is disabled.");
                    findings++;
                }
            }
            for (String dependency : description.getSoftDepend()) {
                Plugin target = Bukkit.getPluginManager().getPlugin(dependency);
                if (target == null) {
                    sender.sendMessage(ChatColor.YELLOW + "[WARN] " + plugin.getName() + " has an unavailable optional integration: " + dependency + ".");
                    findings++;
                }
            }
        }
        sender.sendMessage(ChatColor.GRAY + "Checked " + Bukkit.getPluginManager().getPlugins().length + " installed plugins.");
        return findings;
    }

    private int scanCompatibility(CommandSender sender) {
        if (!getConfig().getBoolean("checks.compatibility", true)) return 0;
        sender.sendMessage(ChatColor.AQUA + "Plugin metadata compatibility checks");
        int findings = 0;
        String serverApi = Bukkit.getBukkitVersion().split("-")[0];
        int serverMinor = minorVersion(serverApi);
        Map<String, List<String>> commandOwners = new LinkedHashMap<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            PluginDescriptionFile description = plugin.getDescription();
            String api = description.getAPIVersion();
            if (api != null && !api.isBlank()) {
                int pluginMinor = minorVersion(api);
                if (pluginMinor > serverMinor && serverMinor > 0) {
                    sender.sendMessage(ChatColor.RED + "[ERROR] " + plugin.getName() + " declares API " + api
                            + ", newer than server API " + serverApi + ".");
                    findings++;
                }
            }
            for (String commandName : description.getCommands().keySet()) {
                commandOwners.computeIfAbsent(commandName.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                        .add(plugin.getName());
            }
        }
        for (Map.Entry<String, List<String>> entry : commandOwners.entrySet()) {
            if (entry.getValue().size() > 1) {
                sender.sendMessage(ChatColor.YELLOW + "[WARN] Command label '" + entry.getKey()
                        + "' is declared by multiple plugins: " + String.join(", ", entry.getValue()) + ".");
                findings++;
            }
        }
        sender.sendMessage(ChatColor.GRAY + "Compatibility checks use declared plugin metadata; they cannot prove runtime compatibility.");
        return findings;
    }

    private int minorVersion(String version) {
        Matcher matcher = Pattern.compile("1\\.(\\d+)").matcher(version);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private int scanConfigurations(CommandSender sender) {
        if (!getConfig().getBoolean("checks.configuration-files", true)) return 0;
        sender.sendMessage(ChatColor.AQUA + "YAML configuration validation");
        Path pluginsFolder = getDataFolder().toPath().getParent();
        if (pluginsFolder == null || !Files.isDirectory(pluginsFolder)) {
            sender.sendMessage(ChatColor.YELLOW + "[WARN] Plugins directory could not be located.");
            return 1;
        }
        int maxConfigFiles = Math.max(1, Math.min(1000, getConfig().getInt("limits.max-config-files", 200)));
        long maxConfigBytes = Math.max(64L, Math.min(4096L, getConfig().getLong("limits.max-config-size-kib", 512))) * 1024L;
        List<Path> yamlFiles = new ArrayList<>();
        try {
            Files.walkFileTree(pluginsFolder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                    return yamlFiles.size() >= maxConfigFiles ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                            && (name.endsWith(".yml") || name.endsWith(".yaml"))) yamlFiles.add(file);
                    return yamlFiles.size() >= MAX_CONFIG_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            sender.sendMessage(ChatColor.YELLOW + "[WARN] Could not enumerate configuration files: " + ex.getMessage());
            return 1;
        }

        int invalid = 0;
        int skipped = 0;
        for (Path file : yamlFiles) {
            try {
                long size = Files.size(file);
                if (size > maxConfigBytes) {
                    skipped++;
                    sender.sendMessage(ChatColor.YELLOW + "[SKIP] " + relative(pluginsFolder, file) + " exceeds the "
                            + (maxConfigBytes / 1024 / 1024) + " MiB validation limit.");
                    continue;
                }
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                options.setMaxAliasesForCollections(20);
                options.setCodePointLimit((int) MAX_CONFIG_BYTES);
                Yaml yaml = new Yaml(new SafeConstructor(options));
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for (Object ignored : yaml.loadAll(reader)) {
                        // Parsing each document is intentional; SafeConstructor prevents arbitrary object construction.
                    }
                }
            } catch (YAMLException ex) {
                invalid++;
                Mark mark = ex.getProblemMark();
                String location = mark == null ? "" : " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
                sender.sendMessage(ChatColor.RED + "[INVALID YAML] " + relative(pluginsFolder, file) + location
                        + ": " + safeMessage(ex.getMessage()));
            } catch (IOException ex) {
                invalid++;
                sender.sendMessage(ChatColor.RED + "[READ ERROR] " + relative(pluginsFolder, file) + ": " + ex.getMessage());
            } catch (RuntimeException ex) {
                invalid++;
                sender.sendMessage(ChatColor.RED + "[PARSE ERROR] " + relative(pluginsFolder, file) + ": " + safeMessage(ex.getMessage()));
            }
        }
        sender.sendMessage((invalid == 0 ? ChatColor.GREEN : ChatColor.YELLOW) + "Validated " + (yamlFiles.size() - skipped)
                + " YAML file(s): " + invalid + " invalid, " + skipped + " skipped.");
        return invalid;
    }

    private String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private String safeMessage(String message) {
        if (message == null) return "No parser detail available";
        String oneLine = message.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 220 ? oneLine.substring(0, 217) + "..." : oneLine;
    }

    private int scanProxy(CommandSender sender) {
        if (!getConfig().getBoolean("checks.proxy", true)) return 0;
        sender.sendMessage(ChatColor.AQUA + "Proxy configuration checks");
        Path root = getDataFolder().toPath().getParent() == null ? null : getDataFolder().toPath().getParent().getParent();
        if (root == null) return 0;
        Path paperGlobal = root.resolve("config/paper-global.yml");
        Path paperWorld = root.resolve("config/paper-world-defaults.yml");
        Path spigot = root.resolve("spigot.yml");
        Path bukkit = root.resolve("bukkit.yml");
        boolean found = false;
        int findings = 0;
        for (Path path : List.of(paperGlobal, paperWorld, spigot, bukkit)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
            found = true;
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String lower = content.toLowerCase(Locale.ROOT);
                if (path.equals(bukkit) && lower.matches("(?s).*\\bbungeecord\\s*:\\s*true\\b.*")) {
                    sender.sendMessage(ChatColor.GREEN + "[INFO] Bukkit BungeeCord forwarding is enabled in bukkit.yml.");
                }
                if (path.equals(paperGlobal) && lower.contains("velocity:")) {
                    sender.sendMessage(ChatColor.GRAY + "[INFO] Paper Velocity settings were found in config/paper-global.yml. Verify the forwarding secret and proxy mode manually; secret values are never displayed.");
                }
                if (path.equals(spigot) && lower.contains("bungeecord:")) {
                    sender.sendMessage(ChatColor.GRAY + "[INFO] Spigot BungeeCord settings were found in spigot.yml.");
                }
            } catch (IOException ex) {
                sender.sendMessage(ChatColor.YELLOW + "[WARN] Could not read " + root.relativize(path) + ".");
                findings++;
            }
        }
        if (!found) sender.sendMessage(ChatColor.GRAY + "No common Paper/Spigot proxy configuration files were found at the server root.");
        sender.sendMessage(ChatColor.GRAY + "Proxy checks are advisory and do not inspect or print forwarding secrets.");
        return findings;
    }

    private int scanMemory(CommandSender sender) {
        if (!getConfig().getBoolean("checks.memory", true)) return 0;
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576;
        long max = runtime.maxMemory() / 1_048_576;
        double percent = max == 0 ? 0 : used * 100.0 / max;
        ChatColor color = percent >= 90 ? ChatColor.RED : percent >= 75 ? ChatColor.YELLOW : ChatColor.GREEN;
        sender.sendMessage(color + "[MEMORY] JVM heap: " + used + " MiB / " + max + " MiB (" + String.format(Locale.US, "%.1f", percent)
                + "%). This is process-wide, not per-plugin.");
        return percent >= 95 ? 1 : 0;
    }

    private int scanLogs(CommandSender sender) {
        if (!getConfig().getBoolean("checks.recent-logs", true)) {
            sender.sendMessage(ChatColor.YELLOW + "Log scanning is disabled in config.yml.");
            return 0;
        }
        Path root = getDataFolder().toPath().getParent().getParent();
        Path log = root.resolve("logs/latest.log");
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            sender.sendMessage(ChatColor.RED + "Could not find logs/latest.log.");
            return 1;
        }
        int lineLimit = Math.max(100, getConfig().getInt("settings.scan-log-lines", 2500));
        int maxFindings = Math.max(1, getConfig().getInt("settings.max-log-findings", 30));
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - lineLimit);
            Map<String, Integer> grouped = new LinkedHashMap<>();
            Map<String, String> samples = new HashMap<>();
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!LOG_FAILURE.matcher(line).find()) continue;
                String key = groupKey(line);
                grouped.merge(key, 1, Integer::sum);
                samples.putIfAbsent(key, line);
            }
            sender.sendMessage(ChatColor.AQUA + "Recent log findings (grouped by error signature)");
            int shown = 0;
            for (Map.Entry<String, Integer> entry : grouped.entrySet()) {
                if (shown++ >= maxFindings) break;
                String sample = samples.get(entry.getKey());
                sender.sendMessage(ChatColor.YELLOW + "[" + entry.getValue() + " occurrence(s)] "
                        + ChatColor.GRAY + safeMessage(sample));
            }
            sender.sendMessage(grouped.isEmpty() ? ChatColor.GREEN + "No common error patterns found in the scanned log window."
                    : ChatColor.GOLD + grouped.size() + " distinct signature(s) found across " + (lines.size() - start) + " lines.");
            sender.sendMessage(ChatColor.GRAY + "Log lines can contain private data. Review them before sharing.");
            return grouped.size();
        } catch (IOException ex) {
            sender.sendMessage(ChatColor.RED + "Log scan failed: " + ex.getMessage());
            return 1;
        }
    }

    private String groupKey(String line) {
        Matcher matcher = EXCEPTION_NAME.matcher(line);
        if (matcher.find()) return matcher.group(1) + (matcher.group(2) == null ? "" : ": " + matcher.group(2));
        String normalized = line.replaceAll("\\d{2}:\\d{2}:\\d{2}", "<time>")
                .replaceAll("@[0-9a-fA-F]+", "@<id>")
                .replaceAll("\\b\\d+\\b", "<n>");
        return normalized.length() > 180 ? normalized.substring(0, 180) : normalized;
    }

    private void doctor(CommandSender sender) {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576;
        long max = runtime.maxMemory() / 1_048_576;
        sender.sendMessage(ChatColor.GOLD + "RealmsExorcist server report");
        sender.sendMessage(ChatColor.GRAY + "Software: " + ChatColor.WHITE + Bukkit.getName() + " " + Bukkit.getVersion());
        sender.sendMessage(ChatColor.GRAY + "Minecraft: " + ChatColor.WHITE + Bukkit.getBukkitVersion());
        sender.sendMessage(ChatColor.GRAY + "Java: " + ChatColor.WHITE + System.getProperty("java.version"));
        sender.sendMessage(ChatColor.GRAY + "Players: " + ChatColor.WHITE + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        sender.sendMessage(ChatColor.GRAY + "Plugins: " + ChatColor.WHITE + Bukkit.getPluginManager().getPlugins().length);
        sender.sendMessage(ChatColor.GRAY + "Heap: " + ChatColor.WHITE + used + " MiB used / " + max + " MiB max");
        sender.sendMessage(ChatColor.GRAY + "Worlds: " + ChatColor.WHITE + Bukkit.getWorlds().size());
        if (Bukkit.getServer().getClass().getName().contains("paper")) {
            double[] tps = Bukkit.getTPS();
            sender.sendMessage(ChatColor.GRAY + "TPS: " + ChatColor.WHITE + String.format(Locale.US, "%.2f / %.2f / %.2f", tps[0], tps[1], tps[2]));
        }
    }

    private void repair(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length != 3 || !args[1].equalsIgnoreCase("own-config") || !args[2].equalsIgnoreCase("confirm")) {
            sender.sendMessage(ChatColor.YELLOW + "This repair only restores RealmsExorcist's own config.yml.");
            sender.sendMessage(ChatColor.GRAY + "It creates a timestamped backup first. Run /exorcist repair own-config confirm to proceed.");
            return;
        }
        Path config = getDataFolder().toPath().resolve("config.yml");
        Path backup = getDataFolder().toPath().resolve("config.yml.backup-" + LocalDateTime.now().format(BACKUP_TIME));
        try {
            if (Files.exists(config)) Files.copy(config, backup);
            try (var input = getResource("config.yml") == null ? null : getResource("config.yml")) {
                if (input == null) {
                    sender.sendMessage(ChatColor.RED + "Bundled default config.yml is missing. No changes were made.");
                    return;
                }
                Files.copy(input, config, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "Restored RealmsExorcist's default config.yml.");
            if (Files.exists(backup)) sender.sendMessage(ChatColor.GRAY + "Backup: " + backup.getFileName());
        } catch (IOException ex) {
            sender.sendMessage(ChatColor.RED + "Repair failed: " + ex.getMessage());
            sender.sendMessage(ChatColor.GRAY + "If a backup was created, it was left untouched.");
        }
    }
}
