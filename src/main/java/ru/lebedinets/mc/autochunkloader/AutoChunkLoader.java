package ru.lebedinets.mc.autochunkloader;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.Objects;

public final class AutoChunkLoader extends JavaPlugin {

    private ConfigManager configManager;
    private EventHandlers eventHandlers;

    @Override
    public void onEnable() {
        // Plugin startup logic
        getLogger().info("AutoChunkLoader has been started!");

        configManager = new ConfigManager(this);
        BukkitScheduler scheduler = Bukkit.getScheduler();

        eventHandlers = new EventHandlers(this, configManager, scheduler);
        getServer().getPluginManager().registerEvents(eventHandlers, this);

        // Schedule a repeating task to check and unload chunks without minecarts
        scheduler.scheduleSyncRepeatingTask(this, eventHandlers::unloadExpiredChunks, 0, configManager.getUnloadPeriod());

        Commands commands = new Commands(this, configManager, eventHandlers);
        Objects.requireNonNull(getCommand("acl")).setExecutor(commands);
        Objects.requireNonNull(getCommand("autochunkloader")).setExecutor(commands);

        // bStats
        int pluginId = 19552;
        Metrics metrics = new Metrics(this, pluginId);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info("AutoChunkLoader has been stopped!");
    }
}
