package ru.lebedinets.mc.autochunkloader;

import io.arxila.javatuples.Trio;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Observer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.*;

public class EventHandlers implements Listener {
    private final Plugin plugin;
    private final ConfigManager configManager;
    private final ChunkManager chunkManager;

    private long lastCooldownTime = 0L;

    public EventHandlers(Plugin plugin, ConfigManager configMgr, ChunkManager chunkManager) {
        this.plugin = plugin;
        this.configManager = configMgr;
        this.chunkManager = chunkManager;
    }

    private void debugLog(String log) {
        if (configManager.getDebugLog()) {
            plugin.getLogger().info(log);
        }
    }

    private boolean checkLimits(World world) {
        return checkChunkLimit() && isWorldAllowed(world);
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
                        op.sendMessage(ChatColor.RED + "[AutoChunkLoaderAdvance] Force loaded chunks limit reached! (" +
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

    @EventHandler
    public void onMinecartMove(VehicleMoveEvent event) {
        if (configManager.getDisableMinecarts()) {
            return;
        }

        if (!isWorldAllowed(event.getVehicle().getWorld())) {
            return;
        }

        if (event.getVehicle() instanceof Minecart minecart) {

            // Do not load chunks when minecart has a player
            List<Entity> passengers = minecart.getPassengers();
            if (!passengers.isEmpty()) {
                Entity passenger = passengers.get(0);
                if (passenger instanceof Player) {
                    return;
                }
            }

            if (!checkChunkLimit()) {
                return;
            }

            debugLog("Minecart signal detected at " + minecart.getLocation());

            Chunk fromChunk = event.getFrom().getChunk();
            Chunk toChunk = event.getTo().getChunk();

            Trio<Integer, Integer, String> chunkKeyFrom = ChunkWithKey.getChunkKey(fromChunk);
            Trio<Integer, Integer, String> chunkKeyTo = ChunkWithKey.getChunkKey(toChunk);

            // Load and set force-loaded for chunks around the minecart
            if (chunkKeyFrom.equals(chunkKeyTo)) {
                // same chunk
                chunkManager.updateChunkTTL(chunkKeyFrom);
            } else {
                debugLog("Loading additional chunks...");
                // load new chunk
                chunkManager.updateChunkTTL(chunkKeyTo);
                // and erase old
                chunkManager.expireChunkTTL(chunkKeyFrom);
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

        Block redstoneBlock = event.getBlock();

        if (!isWorldAllowed(redstoneBlock.getWorld())) {
            return;
        }

        debugLog("Redstone signal detected at " + redstoneBlock.getLocation());

        Chunk chunk = redstoneBlock.getLocation().getChunk();

        // Load and set force-loaded for chunks around the redstone block
        chunkManager.updateChunkTTL(ChunkWithKey.getChunkKey(chunk));
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

            debugLog("Observer set detected at " + block.getLocation());

            Chunk eventChunk = block.getLocation().getChunk();
            chunkManager.incrementObserversInChunk(
                    ChunkWithKey.getChunkKey(eventChunk)
            );
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getBlockData() instanceof Observer) {
            if (configManager.getDisableObservers()) {
                return;
            }

            debugLog("Observer unset detected at " + block.getLocation());

            Chunk eventChunk = block.getLocation().getChunk();
            chunkManager.decrementObserversInChunk(ChunkWithKey.getChunkKey(eventChunk));
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

                debugLog("Observer piston push detected at " + block.getLocation());

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

                debugLog("Observer piston pull detected at " + block.getLocation());

                processObserverMoving(block, moveDirection);
            }
        }
    }

    public void processObserverMoving(Block observer, Vector direction) {
        // process moving observer by piston over chunks
        // for flying machines
        Location currLoc = observer.getLocation();
        Location nextLoc = currLoc.add(direction);

        Chunk fromChunk = observer.getChunk();
        Chunk toChunk = observer.getWorld().getChunkAt(nextLoc);

        Trio<Integer, Integer, String> chunkKeyFrom = ChunkWithKey.getChunkKey(fromChunk);
        Trio<Integer, Integer, String> chunkKeyTo = ChunkWithKey.getChunkKey(toChunk);


        if (chunkKeyFrom.equals(chunkKeyTo)) {
            // do nothing
            return;
        }

        chunkManager.incrementObserversInChunk(chunkKeyTo);
        chunkManager.decrementObserversInChunk(chunkKeyFrom);
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

            debugLog("Hopper pass detected at " + block.getLocation());

            Chunk chunk = block.getLocation().getChunk();
            chunkManager.updateChunkTTL(ChunkWithKey.getChunkKey(chunk));
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        ChunkSnapshot snapshot = event.getChunk().getChunkSnapshot(true, false, false);
        chunkManager.scanChunkSnapshotAsync(snapshot);
    }

    @EventHandler
    public void onCreatureSpawnEvent(CreatureSpawnEvent event) {
        double spawnRatio = configManager.getSpawnRatio();
        if (spawnRatio == 1.0) {
            return;
        }

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (
                reason == CreatureSpawnEvent.SpawnReason.DEFAULT ||
                reason == CreatureSpawnEvent.SpawnReason.NATURAL
        ) {
            Chunk chunk = event.getLocation().getChunk();
            if (chunkManager.shouldBeLoaded(ChunkWithKey.getChunkKey(chunk))) {
                if (Math.random() > spawnRatio) {
                    event.setCancelled(true);
                }
            }
        }
    }

    public int getLoadedChunksCount() {
        return chunkManager.getLoadedChunksCount();
    }

    public int getTemporaryLoadedChunksCount() {
        return chunkManager.getTemporaryLoadedChunksCount();
    }
    public int getLoadedChunksByObserversCount() {
        return chunkManager.getLoadedChunksByObserversCount();
    }

    public void resetCooldown() {
        lastCooldownTime = 0L;
    }
}
