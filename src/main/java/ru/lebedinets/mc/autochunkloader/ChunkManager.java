package ru.lebedinets.mc.autochunkloader;

import io.arxila.javatuples.Trio;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Observer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class ChunkManager {
    // manage loading and forcing chunks
    // every chunk that forced bound to pivot chunks
    // pivot chunks has a reason to be forced

    // Pivot Counters for chunks
    private final Map<Trio<Integer, Integer, String>, Integer> loadedChunks = new HashMap<>();
    // Temporary pivots initiated by some events
    private final Map<Trio<Integer, Integer, String>, Long> temporaryLoadedChunks = new HashMap<>();
    // Pivots that contains observers, counter for observers
    private final Map<Trio<Integer, Integer, String>, Integer> observersCounter = new HashMap<>();
    private final Map<Trio<Integer, Integer, String>, BukkitTask> loadingTasks = new HashMap<>();

    private Plugin plugin;
    private BukkitScheduler scheduler;
    private ConfigManager configManager;

    public ChunkManager(Plugin plugin, BukkitScheduler scheduler, ConfigManager configManager) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.configManager = configManager;
    }

    public void reloadConfig() {
        this.updateAllChunksTTL();
        this.recalcPivots();
    }

    public void incrementObserversInChunk(Trio<Integer, Integer, String> chunkKey) {
        updateObserversInChunk(chunkKey, observersCounter.getOrDefault(chunkKey, 0) + 1);
    }

    public void decrementObserversInChunk(Trio<Integer, Integer, String> chunkKey) {
        updateObserversInChunk(chunkKey, observersCounter.getOrDefault(chunkKey, 0) - 1);
    }

    public void updateObserversInChunk(Trio<Integer, Integer, String> chunkKey, int observersNumber) {
        if (observersCounter.containsKey(chunkKey)) {
            // observers already was
            if (observersNumber > 0 ) {
                // just change counter
                observersCounter.put(chunkKey, observersNumber);
            } else {
                // removed last observer, remove pivot
                observersCounter.remove(chunkKey);
                removePivot(chunkKey);
            }
        } else if (observersNumber > 0) {
            // new observer
            observersCounter.put(chunkKey, observersNumber);
            addPivot(chunkKey);
        }
    }

    public void scanChunkSnapshotAsync(ChunkSnapshot chunkSnapshot) {
        Runnable runnable = () -> {
            // count observers
            int observersCounter = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int maxY = chunkSnapshot.getHighestBlockYAt(x, z);
                    for (int y = 0; y <= maxY; y++) {
                        BlockData blockData = chunkSnapshot.getBlockData(x, y, z);
                        if (blockData instanceof Observer) {
                            observersCounter++;
                        }
                    }
                }
            }
            Trio<Integer, Integer, String> chunkKey = ChunkWithKey.getChunkKey(chunkSnapshot);
            updateObserversInChunk(chunkKey, observersCounter);

            if (observersCounter > 0) {
                debugLog("Count " + observersCounter + " observers at " + chunkKey);
            }
        };

        scheduler.runTaskAsynchronously(plugin, runnable);
    }

    public void updateChunkTTL(Trio<Integer, Integer, String> chunkKey) {
        if (!temporaryLoadedChunks.containsKey(chunkKey)) {
            addPivot(chunkKey);
            insureChunkForceAndLoadStateTask(chunkKey);
        }
        temporaryLoadedChunks.put(chunkKey, System.currentTimeMillis() + configManager.getUnloadDelay());
    }

    public void updateAllChunksTTL() {
        for (Trio<Integer, Integer, String> chunkKey : this.temporaryLoadedChunks.keySet()) {
            updateChunkTTL(chunkKey);
        }
    }

    public void expireChunkTTL(Trio<Integer, Integer, String> chunkKey) {
        if (!temporaryLoadedChunks.containsKey(chunkKey)) {
            return;
        }
        temporaryLoadedChunks.remove(chunkKey);
        removePivot(chunkKey);
        insureChunkForceAndLoadStateTask(chunkKey);
    }

    public void unloadExpiredChunks() {
        long currentTime = System.currentTimeMillis();

        List<Trio<Integer, Integer, String>> expiredKeys = temporaryLoadedChunks.entrySet().stream()
                .filter(entry -> currentTime >= entry.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        for (Trio<Integer, Integer, String> chunkKey : expiredKeys) {
            expireChunkTTL(chunkKey);
        }
    }

    public void addPivot(Trio<Integer, Integer, String> chunkKey) {
        changePivot(chunkKey, true);
    }

    public void removePivot(Trio<Integer, Integer, String> chunkKey) {
        changePivot(chunkKey, false);
    }

    public void changePivot(Trio<Integer, Integer, String> chunkKey, boolean increase) {
        Server server = plugin.getServer();
        Chunk chunk = ChunkWithKey.getChunkByKey(server, chunkKey);
        World world = Objects.requireNonNull(chunk).getWorld();
        String worldName = world.getName();
        int chunkLoadRadius = configManager.getChunkLoadRadius();

        for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
            for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                int targetX = chunk.getX() + x;
                int targetZ = chunk.getZ() + z;

                Trio<Integer, Integer, String> targetKey = ChunkWithKey.getChunkKey(targetX, targetZ, worldName);
                int alreadyPivots = loadedChunks.getOrDefault(targetKey, 0);

                if (increase) {
                    // add pivot
                    if (alreadyPivots == 0) {
                        // first pivot, need to enforce
                        insureChunkForceAndLoadStateTask(targetKey);
                    }
                    loadedChunks.put(targetKey, alreadyPivots + 1);
                } else {
                    // remove pivot
                    if (alreadyPivots <= 1) {
                        // last pivot removed
                        loadedChunks.remove(targetKey);
                        insureChunkForceAndLoadStateTask(targetKey);
                    } else {
                        // just decrease
                        loadedChunks.put(targetKey, alreadyPivots + 1);
                    }
                }

            }
        }
    }

    public void recalcPivots() {
        loadedChunks.clear();
        for (Trio<Integer, Integer, String> chunkKey : observersCounter.keySet()) {
            addPivot(chunkKey);
        }
        for (Trio<Integer, Integer, String> chunkKey : temporaryLoadedChunks.keySet()) {
            addPivot(chunkKey);
        }
    }

    private void insureChunkForceAndLoadStateTask(Trio<Integer, Integer, String> chunkKey) {
        if (loadingTasks.containsKey(chunkKey)) {
            return;
        }

        Runnable runnable = () -> {
            loadingTasks.remove(chunkKey);
            Chunk chunk = ChunkWithKey.getChunkByKey(plugin.getServer(), chunkKey);
            if (chunk == null) { return; }
            boolean shouldBeForce = loadedChunks.containsKey(chunkKey);
            insureChunkForceAndLoadState(chunk, shouldBeForce);
        };

        BukkitTask task = scheduler.runTask(plugin, runnable);
        loadingTasks.put(chunkKey, task);
    }

    private void insureChunkForceAndLoadState(Chunk chunk, boolean shouldBeForce) {
        boolean currentForce = chunk.isForceLoaded();

        if (shouldBeForce != currentForce) {
            // something changed
            chunk.setForceLoaded(shouldBeForce);

            if (shouldBeForce) {
                // load chunk
                if (!chunk.isLoaded()) {
                    chunk.load();
                }
            }
        }
    }

    private void debugLog(String log) {
        if (configManager.getDebugLog()) {
            plugin.getLogger().info(log);
        }
    }

    public int getLoadedChunksCount() {
        return loadedChunks.size();
    }

    public int getTemporaryLoadedChunksCount() {
        return temporaryLoadedChunks.size();
    }
    public int getLoadedChunksByObserversCount() {
        return observersCounter.size();
    }

    public Backup getBackupData() {
        return new Backup(
                observersCounter.keySet().toArray(new Trio[0]),
                temporaryLoadedChunks.keySet().toArray(new Trio[0])
        );
    }

    public void applyBackupData(Backup backup) {
        Server server = plugin.getServer();
        // load observers
        plugin.getLogger().info("(Backup) Scanning for observers: " + backup.observers.length);
        for (Trio<Integer, Integer, String> chunkKeyWithObservers : backup.observers) {
            Chunk chunk = ChunkWithKey.getChunkByKey(server, chunkKeyWithObservers);
            if (chunk == null) {
                continue;
            }
            this.scanChunkSnapshotAsync(chunk.getChunkSnapshot(true, false, false));
        }

        // load temporary
        plugin.getLogger().info("(Backup) Chunks with TTL: " + backup.temporary.length);
        for (Trio<Integer, Integer, String> chunkKeyWithTTL : backup.temporary) {
            updateChunkTTL(chunkKeyWithTTL);
        }
    }
}
