package ru.lebedinets.mc.autochunkloader;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class ConfigManager {
    private final Plugin plugin;
    private int chunkLoadRadius = 2; // Radius in chunks how much to load around minecart/signal
    private int maxLoadedChunks = 1000; // Amount of simultaneously force loaded chunks
    private boolean debugLog = false; // Print debug info
    private long unloadDelay = 30000L; // Delay in milliseconds before chunk unload
    private int unloadPeriod = 20; // Period in ticks (each second)
    private long warningCooldown = 30000L; // Cooldown after showing warning
    private long backupPeriod = 120000L; // Backup period
    private boolean disableWarnings = false;
    private boolean disableRedstone = false;
    private boolean disableObservers = false;
    private boolean disableHoppers = false;
    private boolean disableMinecarts = false;
    private Set<String> worlds;
    private String worldFilterMode;


    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        plugin.saveDefaultConfig();

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        addMissingConfigLines(config, Objects.requireNonNull(plugin.getConfig().getDefaults()));

        chunkLoadRadius = config.getInt("chunkLoadRadius");
        maxLoadedChunks = config.getInt("maxLoadedChunks");
        debugLog = config.getBoolean("debugLog");
        unloadDelay = config.getLong("unloadDelay");
        unloadPeriod = config.getInt("unloadPeriod");
        warningCooldown = config.getLong("warningCooldown");
        backupPeriod = config.getLong("backupPeriod");
        disableWarnings = config.getBoolean("disableWarnings");
        disableRedstone = config.getBoolean("disableRedstone");
        disableObservers = config.getBoolean("disableObservers");
        disableHoppers = config.getBoolean("disableHoppers");
        disableMinecarts = config.getBoolean("disableMinecarts");
        worlds = new HashSet<>(config.getStringList("worlds"));
        worldFilterMode = config.getString("worldFilterMode");
    }

    private void addMissingConfigLines(Configuration config, Configuration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (!config.isSet(key)) {
                config.set(key, defaults.get(key));
                plugin.saveConfig();
            }
        }
    }

    public void reloadConfig() {
        loadConfig();
    }

    public int getChunkLoadRadius() {
        return chunkLoadRadius;
    }

    public int getMaxLoadedChunks() {
        return maxLoadedChunks;
    }

    public long getUnloadDelay() {
        return unloadDelay;
    }

    public boolean getDebugLog() {
        return debugLog;
    }

    public int getUnloadPeriod() {
        return unloadPeriod;
    }

    public long getWarningCooldown() {
        return warningCooldown;
    }

    public long getBackupPeriod() {
        return backupPeriod;
    }

    public boolean getDisableWarnings() {
        return disableWarnings;
    }

    public boolean getDisableRedstone() {
        return disableRedstone;
    }

    public boolean getDisableObservers() {
        return disableObservers;
    }

    public boolean getDisableHoppers() {
        return disableHoppers;
    }

    public boolean getDisableMinecarts() {
        return disableMinecarts;
    }

    public String getWorldFilterMode() {
        return worldFilterMode;
    }

    public Set<String> getWorlds() {
        return worlds;
    }
}
