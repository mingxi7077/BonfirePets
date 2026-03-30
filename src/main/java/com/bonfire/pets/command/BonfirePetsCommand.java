package com.bonfire.pets.command;

import com.bonfire.pets.BonfirePets;
import com.bonfire.pets.model.GiveResult;
import com.bonfire.pets.model.LegacyImportResult;
import com.bonfire.pets.model.RollbackResult;
import com.bonfire.pets.model.ValidationReport;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class BonfirePetsCommand implements CommandExecutor, TabCompleter {

    private final BonfirePets plugin;

    public BonfirePetsCommand(BonfirePets plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(plugin.service().config().adminPermission())) {
            sender.sendMessage("[BonfirePets] No permission.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            case "import" -> handleImport(sender);
            case "validate" -> handleValidate(sender);
            case "rollback" -> handleRollback(sender, args);
            case "give" -> handleGive(sender, args);
            case "debug" -> handleDebug(sender, args);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleImport(CommandSender sender) {
        sender.sendMessage("[BonfirePets] Import started asynchronously.");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LegacyImportResult result = plugin.service().runImport(sender.getName());
                respond(sender, "[BonfirePets] Import " + result.migrationRecord().status()
                        + " record=" + result.migrationRecord().recordId()
                        + " pets=" + result.petCount()
                        + " categories=" + result.categoryCount()
                        + " players=" + result.playerCount()
                        + " assets=" + result.assetCount()
                        + " warnings=" + result.warningCount()
                        + " errors=" + result.errorCount());
            } catch (Exception exception) {
                respond(sender, "[BonfirePets] Import failed: " + exception.getMessage());
            }
        });
        return true;
    }

    private boolean handleValidate(CommandSender sender) {
        sender.sendMessage("[BonfirePets] Validation started asynchronously.");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ValidationReport report = plugin.service().runValidation();
                List<String> lines = new ArrayList<>();
                lines.add("[BonfirePets] Validation finished pets=" + report.petCount()
                        + " categories=" + report.categoryCount()
                        + " players=" + report.playerCount()
                        + " warnings=" + report.warningCount()
                        + " errors=" + report.errorCount());
                lines.add("[BonfirePets] BetterModel present=" + report.betterModelProbe().pluginPresent()
                        + " api=" + report.betterModelProbe().apiAvailable()
                        + " models=" + report.betterModelProbe().loadedModelCount()
                        + " message=" + safe(report.betterModelProbe().message()));
                lines.add("[BonfirePets] MythicMobs usage=" + report.mythicMobsScanSummary().occurrenceCounts());
                if (report.reportPath() != null && !report.reportPath().isBlank()) {
                    lines.add("[BonfirePets] Report=" + report.reportPath());
                }
                plugin.service().sampleIssues(report).forEach(issue ->
                        lines.add("[BonfirePets] " + issue.severity() + " " + issue.code() + " " + issue.reference() + " :: " + issue.message()));
                respond(sender, lines);
            } catch (Exception exception) {
                respond(sender, "[BonfirePets] Validation failed: " + exception.getMessage());
            }
        });
        return true;
    }

    private boolean handleRollback(CommandSender sender, String[] args) {
        String target = args.length >= 2 ? args[1] : "latest";
        sender.sendMessage("[BonfirePets] Rollback started asynchronously.");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                RollbackResult result = plugin.service().runRollback(target, sender.getName());
                respond(sender, "[BonfirePets] " + result.message()
                        + " record=" + result.migrationRecord().recordId()
                        + " snapshot=" + result.snapshotId()
                        + " pets=" + result.petCount()
                        + " categories=" + result.categoryCount()
                        + " players=" + result.playerCount());
            } catch (Exception exception) {
                respond(sender, "[BonfirePets] Rollback failed: " + exception.getMessage());
            }
        });
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("[BonfirePets] Usage: /bpet give <player> <petId>");
            return true;
        }
        sender.sendMessage("[BonfirePets] Grant started asynchronously.");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                OfflinePlayer player = Bukkit.getOfflinePlayer(args[1]);
                GiveResult result = plugin.service().givePet(player.getUniqueId(), player.getName() == null ? args[1] : player.getName(), args[2], sender.getName());
                respond(sender, "[BonfirePets] " + result.message()
                        + " player=" + result.playerName()
                        + " uuid=" + result.playerUuid()
                        + " owned=" + result.ownedPetCount()
                        + " activeAssigned=" + result.activeAssigned());
            } catch (Exception exception) {
                respond(sender, "[BonfirePets] Give failed: " + exception.getMessage());
            }
        });
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("[BonfirePets] Usage: /bpet debug <mount|tracker> [player|uuid]");
            return true;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        String input = args.length >= 3 ? args[2] : sender.getName();
        sender.sendMessage("[BonfirePets] Debug probe started asynchronously.");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID targetUuid = resolveUuid(input);
                List<String> lines = switch (mode) {
                    case "mount" -> plugin.service().debugMount(targetUuid, input);
                    case "tracker" -> plugin.service().debugTracker(targetUuid, input);
                    default -> List.of("[BonfirePets] Usage: /bpet debug <mount|tracker> [player|uuid]");
                };
                respond(sender, lines);
            } catch (Exception exception) {
                respond(sender, "[BonfirePets] Debug failed: " + exception.getMessage());
            }
        });
        return true;
    }

    private UUID resolveUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(input);
        return player.getUniqueId();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("[BonfirePets] 可用命令帮助：");
        sender.sendMessage("[BonfirePets] /bpet help - 查看这份中文帮助说明");
        sender.sendMessage("[BonfirePets] /bpet import - 导入旧版 MCPets 数据到 BonfirePets");
        sender.sendMessage("[BonfirePets] /bpet validate - 校验旧资源、BetterModel 与 MythicMobs 兼容情况");
        sender.sendMessage("[BonfirePets] /bpet rollback [recordId|latest] - 按导入记录或 latest 回滚当前数据");
        sender.sendMessage("[BonfirePets] /bpet give <player> <petId> - 给指定玩家发放宠物并在必要时设为当前宠物");
        sender.sendMessage("[BonfirePets] /bpet debug mount [player|uuid] - 查看坐骑挂载相关调试信息");
        sender.sendMessage("[BonfirePets] /bpet debug tracker [player|uuid] - 查看 BetterModel tracker 调试信息");
    }

    private void respond(CommandSender sender, String line) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(line));
    }

    private void respond(CommandSender sender, List<String> lines) {
        Bukkit.getScheduler().runTask(plugin, () -> lines.forEach(sender::sendMessage));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("help", "import", "validate", "rollback", "give", "debug");
        }
        if (args.length == 2 && "debug".equalsIgnoreCase(args[0])) {
            return List.of("mount", "tracker");
        }
        return List.of();
    }
}
