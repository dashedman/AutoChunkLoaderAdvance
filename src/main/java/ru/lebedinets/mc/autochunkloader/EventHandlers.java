package ru.lebedinets.mc.autochunkloader;

import io.arxila.javatuples.Trio;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Observer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EventHandlers implements Listener {
    private final Plugin plugin;
    private final ConfigManager configManager;
    private final BukkitScheduler scheduler;

    private long lastCooldownTime = 0L;
    private final Set<Trio<Integer, Integer, String>> loadedChunks = new HashSet<>();
    private final Map<Trio<Integer, Integer, String>, Long> temporaryLoadedChunks = new HashMap<>();
    private final Map<Trio<Integer, Integer, String>, Integer> observersCounter = new HashMap<>();

    public EventHandlers(Plugin plugin, ConfigManager configMgr, BukkitScheduler scheduler) {
        this.plugin = plugin;
        this.configManager = configMgr;
        this.scheduler = scheduler;
    }

    private boolean checkChunkLimit() {
        if (getLoadedChunksCount() > configManager.getMaxLoadedChunks()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCooldownTime >= configManager.getWarningCooldown()) {
                lastCooldownTime = currentTime;

                if (configManager.getDisableWarnings()) {
                    return false;
                }
                // Print a warning to the console
                plugin.getLogger().warning("Force loaded chunks limit reached! (" +configManager.getMaxLoadedChunks() + ")");
                // Notify ops
                for (Player op : Bukkit.getOnlinePlayers()) {
                    if (op.isOp()) {
                        op.sendMessage(ChatColor.RED + "[AutoChunkLoader] Force loaded chunks limit reached! (" +
                                configManager.getMaxLoadedChunks() + ")");
                    }
                }
            }
            return false;
        } else {
            return true;
        }
    }

    private boolean isWorldAllowed(World world) {
        Set<String> worlds = configManager.getWorlds();
        String worldFilterMode = configManager.getWorldFilterMode();
        String worldName = world.getName();

        if (worldFilterMode.equals("whitelist") && worlds.contains(worldName)) {
            return true;
        }

        return worldFilterMode.equals("blacklist") && !worlds.contains(worldName);
    }

    private void reviewRemovedLoadedChunk(Trio<Integer, Integer, String> chunkKey) {
        // chunk was removed from secondary registry
        // check and remove it from general registry
        if (temporaryLoadedChunks.containsKey(chunkKey) || observersCounter.containsKey(chunkKey)) {
            return;
        }
        loadedChunks.remove(chunkKey);
    }

    private void recalcChunkLoadState(ChunkWithKey chunk) {
        boolean currentForce = chunk.isForceLoaded();

        // check temporary chunks
        boolean shouldBeForce = loadedChunks.contains(chunk.getChunkKey());
        if (shouldBeForce != currentForce) {
            // something changed
            chunk.setForceLoaded(shouldBeForce);

            if (shouldBeForce) {
                // load chunk
                if (!chunk.isLoaded()) {
                    chunk.load();
                }
            } else {
                // chunk will be unloaded
                if (configManager.getDebugLog()) {
                    plugin.getLogger().info("Unloading chunk (" + chunk.getX() + ", " + chunk.getZ() + ")...");
                }
            }
        }
    }

    private void recalcChunkLoadStateByKey(Trio<Integer, Integer, String> chunkKey) {
        ChunkWithKey chunk = ChunkWithKey.getChunkByKey(plugin.getServer(), chunkKey);
        if (chunk == null) { return; }
        recalcChunkLoadState(chunk);
    }

    @EventHandler
    public void onMinecartMove(VehicleMoveEvent event) {
        if (configManager.getDisableMinecarts()) {
            return;
        }

        if (!isWorldAllowed(event.getVehicle().getWorld())) {
            return;
        }

        int chunkLoadRadius = configManager.getChunkLoadRadius();
        long unloadDelay = configManager.getUnloadDelay();

        if (event.getVehicle() instanceof Minecart) {
            Minecart minecart = (Minecart) event.getVehicle();

            // Do not load chunks when minecart has a player
            if (!minecart.getPassengers().isEmpty()) {
                Entity passenger = minecart.getPassengers().get(0);
                if (passenger instanceof Player) {
                    return;
                }
            }

            if (!checkChunkLimit()) {
                return;
            }

            if (configManager.getDebugLog()) {
                plugin.getLogger().info("Minecart signal detected at " + minecart.getLocation());
            }

            Chunk chunk = minecart.getLocation().getChunk();
            World world = chunk.getWorld();

            if (configManager.getDebugLog()) {
                plugin.getLogger().info("Loading additional chunks...");
            }

            // Load and set force-loaded for chunks around the minecart
            for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
                for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                    int targetX = chunk.getX() + x;
                    int targetZ = chunk.getZ() + z;

                    ChunkWithKey targetChunk = (ChunkWithKey) world.getChunkAt(targetX, targetZ);
                    updateChunkTTL(targetChunk);
                }
            }
        }
    }

    @EventHandler
    public void onRedstoneSignal(BlockRedstoneEvent event) {
        if (configManager.getDisableRedstone()) {
            return;
        }

        if (!checkChunkLimit()) {
            return;
        }

        int chunkLoadRadius = configManager.getChunkLoadRadius();

        Block redstoneBlock = event.getBlock();

        if (!isWorldAllowed(redstoneBlock.getWorld())) {
            return;
        }

        if (configManager.getDebugLog()) {
            plugin.getLogger().info("Redstone signal detected at " + redstoneBlock.getLocation());
        }

        World world = redstoneBlock.getWorld();
        Chunk centerChunk = redstoneBlock.getLocation().getChunk();

        // Load and set force-loaded for chunks around the redstone block
        for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
            for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                int targetX = centerChunk.getX() + x;
                int targetZ = centerChunk.getZ() + z;

                ChunkWithKey targetChunk = (ChunkWithKey) world.getChunkAt(targetX, targetZ);
                updateChunkTTL(targetChunk);
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (block.getBlockData() instanceof Observer) {
            if (configManager.getDisableObservers()) {
                return;
            }

            if (!checkChunkLimit()) {
                return;
            }

            Chunk eventChunk = block.getLocation().getChunk();
            addObserverToChunk(eventChunk);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getBlockData() instanceof Observer) {
            if (configManager.getDisableObservers()) {
                return;
            }

            Chunk eventChunk = block.getLocation().getChunk();
            removeObserverFromChunk(eventChunk);
        }
    }

    public void addObserverToChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int chunkLoadRadius = configManager.getChunkLoadRadius();

        // Load and set force-loaded for chunks around the redstone block
        for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
            for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                int targetX = chunk.getX() + x;
                int targetZ = chunk.getZ() + z;

                ChunkWithKey targetChunk = (ChunkWithKey) world.getChunkAt(targetX, targetZ);
                Trio<Integer, Integer, String> targetChunkKey = targetChunk.getChunkKey();

                Integer counter = observersCounter.get(targetChunkKey);
                if (counter == null) {
                    observersCounter.put(targetChunkKey, 1);
                    loadedChunks.add(targetChunkKey);
                    recalcChunkLoadState(targetChunk);
                } else {
                    observersCounter.put(targetChunkKey, counter + 1);
                }

            }
        }
    }

    public void removeObserverFromChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int chunkLoadRadius = configManager.getChunkLoadRadius();

        // Load and set force-loaded for chunks around the redstone block
        for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
            for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                int targetX = chunk.getX() + x;
                int targetZ = chunk.getZ() + z;

                ChunkWithKey targetChunk = (ChunkWithKey) world.getChunkAt(targetX, targetZ);
                Trio<Integer, Integer, String> targetChunkKey = targetChunk.getChunkKey();

                Integer counter = observersCounter.getOrDefault(targetChunkKey, 0);
                counter--;
                if (counter <= 0) {
                    observersCounter.remove(targetChunkKey);
                    reviewRemovedLoadedChunk(targetChunkKey);
                    recalcChunkLoadState(targetChunk);
                } else {
                    observersCounter.put(targetChunkKey, counter);
                }
            }
        }
    }

    @EventHandler
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        Vector moveDirection = event.getDirection().getDirection();
        for (Block block : event.getBlocks()) {
            if (block.getBlockData() instanceof Observer) {
                if (configManager.getDisableObservers()) {
                    continue;
                }
                processObserverMoving(block, moveDirection);
            }
        }
    }

    @EventHandler
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        Vector moveDirection = event.getDirection().getDirection();
        for (Block block : event.getBlocks()) {
            if (block.getBlockData() instanceof Observer) {
                if (configManager.getDisableObservers()) {
                    continue;
                }
                processObserverMoving(block, moveDirection);
            }
        }
    }

    public void processObserverMoving(Block observer, Vector direction) {
        // process moving observer by piston over chunks
        // for flying machines
        Location currLoc = observer.getLocation();
        Location nextLoc = currLoc.add(direction);

        Chunk curentChunk = observer.getChunk();
        Chunk nextChunk = observer.getWorld().getChunkAt(nextLoc);

        if (nextChunk.equals(curentChunk)) {
            // do nothing
            return;
        }

        addObserverToChunk(nextChunk);
        removeObserverFromChunk(curentChunk);
    }

    @EventHandler
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getInitiator().getHolder();
        if (holder instanceof BlockInventoryHolder) {
            // this is hopper or some that can pass items to somewhere
            if (configManager.getDisableHoppers()) {
                return;
            }

            Block block = ((BlockInventoryHolder) holder).getBlock();

            if (!isWorldAllowed(block.getWorld())) {
                return;
            }

            if (configManager.getDebugLog()) {
                plugin.getLogger().info("Hopper pass detected at " + block.getLocation());
            }

            World world = block.getWorld();
            Chunk centerChunk = block.getLocation().getChunk();

            int chunkLoadRadius = configManager.getChunkLoadRadius();
            // Load and set force-loaded for chunks around the redstone block
            for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
                for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                    int targetX = centerChunk.getX() + x;
                    int targetZ = centerChunk.getZ() + z;

                    ChunkWithKey targetChunk = (ChunkWithKey) world.getChunkAt(targetX, targetZ);
                    updateChunkTTL(targetChunk);
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        ChunkSnapshot snapshot = event.getChunk().getChunkSnapshot(true, false, false);
        scanChunkSnapshotAsync(snapshot, true);
    }

    public void scanChunkSnapshotAsync(ChunkSnapshot chunkSnapshot, boolean chunkLoadedAssured) {
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

            if (observersCounter > 0) {
                this.observersCounter.put(chunkKey, observersCounter);
                loadedChunks.add(chunkKey);

                if (!chunkLoadedAssured) {
                    // check and load chunk in main thread
                    scheduler.runTask(this.plugin, () -> this.recalcChunkLoadStateByKey(chunkKey));
                }
            } else {
                this.observersCounter.remove(chunkKey);
                reviewRemovedLoadedChunk(chunkKey);
            }
        };

        scheduler.runTaskAsynchronously(plugin, runnable);
    }

    public void updateChunkTTL(ChunkWithKey chunk) {
        Trio<Integer, Integer, String> chunkKey = chunk.getChunkKey();
        temporaryLoadedChunks.put(chunkKey, System.currentTimeMillis() + configManager.getUnloadDelay());
        loadedChunks.add(chunkKey);
        recalcChunkLoadState(chunk);
    }

    public void unloadExpiredChunks() {
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<Trio<Integer, Integer, String>, Long> entry : temporaryLoadedChunks.entrySet()) {
            long expirationTime = entry.getValue();

            if (currentTime >= expirationTime) {
                Trio<Integer, Integer, String> chunkKey = entry.getKey();

                temporaryLoadedChunks.remove(chunkKey);
                reviewRemovedLoadedChunk(chunkKey);

                int chunkX = chunkKey.value0();
                int chunkZ = chunkKey.value1();
                String chunkWorldName = chunkKey.value2();

                World world = plugin.getServer().getWorld(chunkWorldName);
                if (world == null) {
                    // world not exist, ignore
                    continue;
                }

                ChunkWithKey chunk = (ChunkWithKey) world.getChunkAt(chunkX, chunkZ);
                recalcChunkLoadState(chunk);
            }
        }
    }

    public int getLoadedChunksCount() {
        return loadedChunks.size();
    }

    public void resetCooldown() {
        lastCooldownTime = 0L;
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
        for (Trio<Integer, Integer, String> chunkKeyWithObservers : backup.observers) {
            ChunkWithKey chunk = ChunkWithKey.getChunkByKey(server, chunkKeyWithObservers);
            if (chunk == null) {
                continue;
            }
            this.scanChunkSnapshotAsync(
                    chunk.getChunkSnapshot(true, false, false),
                    false
            );
        }

        // load temporary
        for (Trio<Integer, Integer, String> chunkKeyWithObservers : backup.observers) {
            ChunkWithKey chunk = ChunkWithKey.getChunkByKey(server, chunkKeyWithObservers);
            if (chunk == null) {
                continue;
            }
            updateChunkTTL(chunk);
        }
    }
}
