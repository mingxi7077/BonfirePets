package com.bonfire.pets;

import com.bonfire.pets.command.BonfirePetsCommand;
import com.bonfire.pets.config.BonfirePetsConfig;
import com.bonfire.pets.runtime.BonfirePetsService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class BonfirePets extends JavaPlugin {

    private BonfirePetsService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BonfirePetsConfig config = BonfirePetsConfig.from(this, getConfig());
        if (!config.enabled()) {
            getLogger().warning("BonfirePets is disabled in config.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            service = new BonfirePetsService(this, config);
            service.initialize();
        } catch (Exception exception) {
            getLogger().severe("Failed to enable BonfirePets: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand command = getCommand("bpet");
        if (command != null) {
            BonfirePetsCommand executor = new BonfirePetsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("BonfirePets enabled with legacy source " + config.resolveLegacyPluginsDir());
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.close();
        }
    }

    public BonfirePetsService service() {
        return service;
    }
}

