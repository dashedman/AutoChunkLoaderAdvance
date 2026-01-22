package ru.lebedinets.mc.autochunkloader;

import io.arxila.javatuples.Trio;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.*;

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

    private Plugin plugin;
    private int chunkLoadRadius;

    public ChunkManager(Plugin plugin, int chunkLoadRadius) {
        this.plugin = plugin;
        this.chunkLoadRadius = chunkLoadRadius;
    }

    public void updateChunkLoadRadius(int chunkLoadRadius) {
        this.chunkLoadRadius = chunkLoadRadius;
        this.recalcPivots();
    }

    public void incrementObserversInChunk(Trio<Integer, Integer, String> chunkKey) {
        updateObserversInChunk(chunkKey, observersCounter.getOrDefault(chunkKey, 0) + 1);
    }

    public void decrementObserversInChunk(Trio<Integer, Integer, String> chunkKey, int observersNumber) {
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

    public void addPivot(Trio<Integer, Integer, String> chunkKey) {
        Server server = plugin.getServer();
        Chunk chunk = ChunkWithKey.getChunkByKey(server, chunkKey);
        World world = Objects.requireNonNull(chunk).getWorld();
        String worldName = world.getName();

        for (int x = -chunkLoadRadius; x <= chunkLoadRadius; x++) {
            for (int z = -chunkLoadRadius; z <= chunkLoadRadius; z++) {
                int targetX = chunk.getX() + x;
                int targetZ = chunk.getZ() + z;

                Trio<Integer, Integer, String> targetKey = ChunkWithKey.getChunkKey(targetX, targetZ, worldName);

                int alreadyPivots = loadedChunks.getOrDefault(targetKey, 0);
                if (alreadyPivots == 0) {
                    // first pivot, need to enforce
                    Chunk targetChunk = world.getChunkAt(targetX, targetZ);
                    insureChunkForceAndLoadState(targetChunk, true);
                }
                loadedChunks.put(targetKey, alreadyPivots + 1);
            }
        }
    }

    public void removePivot(Trio<Integer, Integer, String> chunkKey) {

    }

    public void changePivot(Trio<Integer, Integer, String> chunkKey, boolean increase) {
        Server server = plugin.getServer();
        Chunk chunk = ChunkWithKey.getChunkByKey(server, chunkKey);
        World world = Objects.requireNonNull(chunk).getWorld();
        String worldName = world.getName();

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
                        Chunk targetChunk = world.getChunkAt(targetX, targetZ);
                        insureChunkForceAndLoadState(targetChunk, true);
                    }
                    loadedChunks.put(targetKey, alreadyPivots + 1);
                } else {
                    // remove pivot
                    if (alreadyPivots <= 1) {
                        // last pivot removed
                        loadedChunks.remove(targetKey);
                        Chunk targetChunk = world.getChunkAt(targetX, targetZ);
                        insureChunkForceAndLoadState(targetChunk, false);
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
}
