package ru.lebedinets.mc.autochunkloader;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Observer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EventHandlers implements Listener {
    private final Plugin plugin;
    private final ConfigManager configManager;

    private long lastCooldownTime = 0L;
    private final Set<Chunk> loadedChunks = new HashSet<>();
    private final Map<Chunk, Long> temporaryLoadedChunks = new HashMap<>();
    private final Map<Chunk, Integer> observersCounter = new HashMap<>();

    public EventHandlers(Plugin plugin, ConfigManager configMgr) {
        this.plugin = plugin;
        this.configManager = configMgr;
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

    private void reviewRemovedLoadedChunk(Chunk chunk) {
        // chunk was removed from secondary registry
        // check and remove it from general registry
        if (temporaryLoadedChunks.containsKey(chunk) || observersCounter.containsKey(chunk)) {
            return;
        }
        loadedChunks.remove(chunk);
    }

    private void recalcChunkLoadState(Chunk chunk) {
        boolean currentForce = chunk.isForceLoaded();

        // check temporary chunks
        boolean shouldBeForce = loadedChunks.contains(chunk);
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

                    Chunk targetChunk = world.getChunkAt(targetX, targetZ);
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

                Chunk targetChunk = world.getChunkAt(targetX, targetZ);
                updateChunkTTL(targetChunk);
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (block instanceof Observer) {
            if (configManager.getDisableObservers()) {
                return;
            }

            if (!checkChunkLimit()) {
                return;
            }

            Chunk eventChunk = block.getLocation().getChunk();
            World world = eventChunk.getWorld();
            int chunkLoadRadius = configManager.getChunkLoadRadius();

            // Load and set force-loaded for chunks around the redstone block
            for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
                for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                    int targetX = eventChunk.getX() + x;
                    int targetZ = eventChunk.getZ() + z;

                    Chunk targetChunk = world.getChunkAt(targetX, targetZ);
                    Integer counter = observersCounter.get(targetChunk);
                    if (counter == null) {
                        observersCounter.put(targetChunk, 1);
                        loadedChunks.add(targetChunk);
                        recalcChunkLoadState(targetChunk);
                    } else {
                        observersCounter.put(targetChunk, counter + 1);
                    }

                }
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block instanceof Observer) {
            if (configManager.getDisableObservers()) {
                return;
            }

            Chunk eventChunk = block.getLocation().getChunk();
            World world = eventChunk.getWorld();
            int chunkLoadRadius = configManager.getChunkLoadRadius();

            // Load and set force-loaded for chunks around the redstone block
            for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
                for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                    int targetX = eventChunk.getX() + x;
                    int targetZ = eventChunk.getZ() + z;

                    Chunk targetChunk = world.getChunkAt(targetX, targetZ);
                    Integer counter = observersCounter.getOrDefault(targetChunk, 0);
                    counter--;
                    if (counter <= 0) {
                        observersCounter.remove(targetChunk);
                        reviewRemovedLoadedChunk(targetChunk);
                        recalcChunkLoadState(targetChunk);
                    } else {
                        observersCounter.put(targetChunk, counter);
                    }
                }
            }
        }
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

                    Chunk targetChunk = world.getChunkAt(targetX, targetZ);
                    updateChunkTTL(targetChunk);
                }
            }
        }
    }

    public void updateChunkTTL(Chunk chunk) {
        temporaryLoadedChunks.put(chunk, System.currentTimeMillis() + configManager.getUnloadDelay());
        loadedChunks.add(chunk);
        recalcChunkLoadState(chunk);
    }

    public void unloadExpiredChunks() {
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<Chunk, Long> entry : temporaryLoadedChunks.entrySet()) {
            Chunk chunk = entry.getKey();
            long expirationTime = entry.getValue();

            if (currentTime >= expirationTime) {
                temporaryLoadedChunks.remove(chunk);
                reviewRemovedLoadedChunk(chunk);
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
}
