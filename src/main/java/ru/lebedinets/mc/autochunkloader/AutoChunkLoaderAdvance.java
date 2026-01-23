package ru.lebedinets.mc.autochunkloader;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.Objects;

public final class AutoChunkLoaderAdvance extends JavaPlugin {

    private ChunkManager chunkManager;

    @Override
    public void onEnable() {
        // Plugin startup logic
        getLogger().info("AutoChunkLoaderAdvance has been started!");

        ConfigManager configManager = new ConfigManager(this);
        BukkitScheduler scheduler = Bukkit.getScheduler();

        chunkManager = new ChunkManager(
                this, scheduler, configManager
        );

        EventHandlers eventHandlers = new EventHandlers(this, configManager, chunkManager);
        getServer().getPluginManager().registerEvents(eventHandlers, this);
        loadBackup();

        // Schedule a repeating task to check and unload chunks without minecarts
        scheduler.runTaskTimerAsynchronously(this, chunkManager::unloadExpiredChunks, 0, configManager.getUnloadPeriod());
        scheduler.runTaskTimerAsynchronously(this, this::saveBackup, configManager.getBackupPeriod(), configManager.getBackupPeriod());

        Commands commands = new Commands(this, configManager, chunkManager, eventHandlers);
        Objects.requireNonNull(getCommand("acl")).setExecutor(commands);
        Objects.requireNonNull(getCommand("autochunkloader")).setExecutor(commands);

        // bStats
        int pluginId = 19552;
        Metrics metrics = new Metrics(this, pluginId);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        saveBackup();
        getLogger().info("AutoChunkLoaderAdvance has been stopped!");
    }

    public void saveBackup() {
        getLogger().info("Start backup AutoChunkLoaderAdvance!");
        Backup backup = chunkManager.getBackupData();
        backup.dump(this);
    }

    public void loadBackup() {
        Backup backup = Backup.load(this);
        if (backup == null) {
            getLogger().info("Backup is empty.");
            return;
        }

        getLogger().info("Loading Backup...");
        chunkManager.applyBackupData(backup);
        getLogger().info("Backup loaded!");
    }
}
