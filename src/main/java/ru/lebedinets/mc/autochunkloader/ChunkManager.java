package ru.lebedinets.mc.autochunkloader;

import de.pauleff.jmcx.api.IChunk;
import de.pauleff.jmcx.api.IRegion;
import de.pauleff.jmcx.formats.anvil.AnvilReader;
import de.pauleff.jnbt.api.ICompoundTag;
import de.pauleff.jnbt.api.IListTag;
import io.arxila.javatuples.Pair;
import io.arxila.javatuples.Trio;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Observer;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;


class ItemTTLComparator implements Comparator<Pair<Long, Item>> {
    @Override
    public int compare(Pair<Long, Item> x, Pair<Long, Item> y) {
        return (int) (x.value0() - y.value0());
    }
}


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

    // sorted by timepoint ascending
    private final PriorityQueue<Pair<Long, Item>> itemsWithTTL = new PriorityQueue<>(new ItemTTLComparator());
    private BukkitTask checkItemsTTLTask = null;

    private final Plugin plugin;
    private final BukkitScheduler scheduler;
    private final ConfigManager configManager;

    public ChunkManager(Plugin plugin, BukkitScheduler scheduler, ConfigManager configManager) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.configManager = configManager;
    }

    public void reloadConfig() {
        this.updateAllChunksTTL();
        this.recalcPivots();
    }

    public boolean shouldBeLoaded(Trio<Integer, Integer, String> chunkKey) {
        return loadedChunks.containsKey(chunkKey);
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

    public void scanCurrentChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunkSnapshotAsync(chunk.getChunkSnapshot(), world.getMinHeight());
            }
        }
    }

    public void scanAllGeneratedChunks() {
        // run async in other thread
        Runnable runnable = () -> {
            Runtime rt = Runtime.getRuntime();
            for (World world : plugin.getServer().getWorlds()) {
                File regionDir = switch (world.getName()) {
                    case "world_nether" -> new File(world.getWorldFolder(), "DIM-1/region");
                    case "world_the_end" -> new File(world.getWorldFolder(), "DIM1/region");
                    default -> new File(world.getWorldFolder(), "region");
                };

                try {
                    Files.walk(regionDir.toPath())
                            .filter(path -> path.toString().endsWith(".mca"))
                            .forEach(path -> {

                                this.plugin.getLogger().info("Scanning for observers - " + path);
                                AnvilReader reader = null;
                                try {
                                    reader = new AnvilReader(path.toFile());
                                } catch (IOException e) {
                                    this.plugin.getLogger().warning(e.toString());
                                    return;
                                }

                                IRegion region = null;
                                try {
                                    region = reader.readRegion();
                                } catch (IOException e) {
                                    this.plugin.getLogger().warning(e.toString());
                                    return;
                                }
                                List<IChunk> chunks = region.getChunks();

                                for (IChunk chunk : chunks) {
                                    ICompoundTag nbt = null;
                                    try {
                                        nbt = chunk.getNBTData();
                                    } catch (IOException e) {
                                        this.plugin.getLogger().warning(e.toString());
                                        continue;
                                    }
                                    if (nbt == null) {
                                        continue;
                                    }
                                    IListTag sections = nbt.getList("sections");

                                    int observersChunkCounter = 0;
                                    for (int i = 0; i < sections.size(); i++) {
                                        ICompoundTag section = (ICompoundTag) sections.get(i);
                                        ICompoundTag blockStates = section.getCompound("block_states");
                                        if (blockStates == null) {
                                            continue;
                                        }
                                        IListTag palette = blockStates.getList("palette");

                                        // check for observer type in pallete
                                        int observersInPalette = -1;
                                        for (int j = 0; j < palette.size(); j++) {
                                            ICompoundTag block = (ICompoundTag) palette.get(j);
                                            if ("minecraft:observer".equals(block.getString("Name"))) {
                                                observersInPalette = j;
                                                break;
                                            }
                                        }

                                        if (observersInPalette < 0) {
                                            continue;
                                        }

                                        if (palette.size() == 1) {
                                            observersChunkCounter += 16 * 16 * 16;
                                            continue;
                                        }

                                        int maxIndexBits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                                        int indexMask = (1 << maxIndexBits) - 1;
                                        int groupSize = 64 / maxIndexBits;
                                        long[] blockData = blockStates.getLongArray("data");
                                        for (long blocksGroup : blockData) {
                                            for (int blockGroupNum = 0; blockGroupNum < groupSize; blockGroupNum++) {
                                                long index = blocksGroup & indexMask;
                                                if (index == observersInPalette) {
                                                    observersChunkCounter++;
                                                }

                                                blocksGroup >>= maxIndexBits;
                                            }
                                        }
                                    }

                                    if(observersChunkCounter > 0) {
                                        Trio<Integer, Integer, String> chunkKey = ChunkWithKey.getChunkKey(
                                                chunk.getX(), chunk.getZ(), world.getName()
                                        );
                                        this.updateObserversInChunk(chunkKey, observersChunkCounter);
                                        this.plugin.getLogger().info("Detected " + observersChunkCounter + " observers for " + chunkKey);
                                    }
                                }

                                try {
                                    reader.close();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                } catch (IOException e) {
                    this.plugin.getLogger().warning(e.toString());
                    continue;
                }
            }
        };

        scheduler.runTaskAsynchronously(plugin, runnable);
    }

    public void scanChunkSnapshotAsync(ChunkSnapshot chunkSnapshot, int worldMinY) {
        Runnable runnable = () -> {
            Trio<Integer, Integer, String> chunkKey = ChunkWithKey.getChunkKey(chunkSnapshot);
            debugLog("Scanning chunk " + chunkKey);
            // count observers
            int observersCounter = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int maxY = chunkSnapshot.getHighestBlockYAt(x, z);

                    for (int y = worldMinY; y <= maxY; y++) {
                        BlockData blockData = chunkSnapshot.getBlockData(x, y, z);
                        if (blockData instanceof Observer) {
                            observersCounter++;
                        }
                    }
                }
            }
            updateObserversInChunk(chunkKey, observersCounter);

            if (observersCounter > 0) {
                debugLog("Count " + observersCounter + " observers at " + chunkKey);
            }
        };

        scheduler.runTaskAsynchronously(plugin, runnable);
    }

    public void updateItemTTL(Item item) {
        List<Item> oneItemList = new LinkedList<>();
        oneItemList.add(item);
        updateItemsListTTL(oneItemList);
    }

    public void updateItemsListTTL(List<Item> items) {
        for (Item item : items) {
            Chunk chunk = item.getLocation().getChunk();
            if (!loadedChunks.containsKey(ChunkWithKey.getChunkKey(chunk))) {
                // item not in chunk loaded by plugin
                continue;
            }

            Pair<Long, Item> ttlPair = new Pair<>(System.currentTimeMillis() + configManager.getDroppedItemsLifetime(), item);
            itemsWithTTL.add(ttlPair);
        }

        rescheduleItemTTLTask();
    }

    public void rescheduleItemTTLTask() {
        if (this.checkItemsTTLTask != null) {
            this.checkItemsTTLTask.cancel();
            this.checkItemsTTLTask = null;
        }

        if (itemsWithTTL.isEmpty()) {
            return;
        }

        long nearestTimepoint = Objects.requireNonNull(itemsWithTTL.peek()).value0();
        // convert to ticks
        long delayToExpire = (nearestTimepoint - System.currentTimeMillis()) / 50;
        this.checkItemsTTLTask = scheduler.runTaskLater(this.plugin, this::removeExpiredItems, delayToExpire);
    }

    public void removeExpiredItems() {
        long currentTime = System.currentTimeMillis();

        while (!this.itemsWithTTL.isEmpty()) {
            Pair<Long, Item> ttlPair = this.itemsWithTTL.poll();
            long ttl = ttlPair.value0();
            if (ttl <= currentTime) {
                Item item = ttlPair.value1();
                item.remove();
            } else {
                this.itemsWithTTL.add(ttlPair);
                break;
            }
        }
        rescheduleItemTTLTask();
    }

    public void updateChunkTTL(Trio<Integer, Integer, String> chunkKey) {
        synchronized (temporaryLoadedChunks) {
            if (!temporaryLoadedChunks.containsKey(chunkKey)) {
                addPivot(chunkKey);
            }
            temporaryLoadedChunks.put(chunkKey, System.currentTimeMillis() + configManager.getUnloadDelay());
        }
    }

    public void updateAllChunksTTL() {
        for (Trio<Integer, Integer, String> chunkKey : this.temporaryLoadedChunks.keySet().stream().toList()) {
            updateChunkTTL(chunkKey);
        }
    }

    public void expireChunkTTL(Trio<Integer, Integer, String> chunkKey) {
        synchronized (temporaryLoadedChunks) {
            if (!temporaryLoadedChunks.containsKey(chunkKey)) {
                return;
            }
            temporaryLoadedChunks.remove(chunkKey);
            removePivot(chunkKey);
        }
    }

    public void unloadExpiredChunks() {
        long currentTime = System.currentTimeMillis();

        List<Trio<Integer, Integer, String>> expiredKeys;
        synchronized (temporaryLoadedChunks) {
            expiredKeys = temporaryLoadedChunks.entrySet().stream()
                    .filter(entry -> currentTime >= entry.getValue())
                    .map(Map.Entry::getKey)
                    .toList();
        }

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

    private void infoLog(String log) {
        plugin.getLogger().info(log);
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

    public String getLoadedChunksInfo() {
        StringBuilder info = new StringBuilder();
        loadedChunks.keySet()
                .stream()
                .collect(Collectors.groupingBy(Trio::value2))
                .forEach((key, value) -> {
                    info.append("World ").append(key).append(":\n");

                    value.forEach(chunkKey -> {
                        info.append("  [").append(chunkKey.value0()).append(", ").append(chunkKey.value1()).append("]\n");
                    });
                });

        return info.toString();
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
            this.scanChunkSnapshotAsync(chunk.getChunkSnapshot(true, false, false), chunk.getWorld().getMinHeight());
        }

        // load temporary
        plugin.getLogger().info("(Backup) Chunks with TTL: " + backup.temporary.length);
        for (Trio<Integer, Integer, String> chunkKeyWithTTL : backup.temporary) {
            updateChunkTTL(chunkKeyWithTTL);
        }
    }
}
