package ru.lebedinets.mc.autochunkloader;

import io.arxila.javatuples.Trio;
import org.bukkit.*;

import java.util.Objects;

public interface ChunkWithKey extends Chunk {
    // inspired by Paper
    // idea taken from https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/org/bukkit/Chunk.java
    // to not move from spigot on paper only
    // added chunk's world name

    // Paper start
    /**
     * @return The Chunks X and Z coordinates with world name
     */
    default Trio<Integer, Integer, String> getChunkKey() {
        return getChunkKey(this);
    }

    static Trio<Integer, Integer, String> getChunkKey(Chunk chunk) {
        return getChunkKey(chunk.getX(), chunk.getZ(), chunk.getWorld().getName());
    }

    /**
     * @param loc Location to get chunk key
     * @return Location's chunk coordinates with world name
     */
    static Trio<Integer, Integer, String> getChunkKey(Location loc) {
        return getChunkKey(
                (int) Math.floor(loc.getX()) >> 4,
                (int) Math.floor(loc.getZ()) >> 4,
                Objects.requireNonNull(loc.getWorld()).getName()
        );
    }

    /**
     * @param snapshot ChunkSnapshot to get chunk key
     * @return ChunkSnapshot's chunk coordinates with world name
     */
    static Trio<Integer, Integer, String> getChunkKey(ChunkSnapshot snapshot) {
        return getChunkKey(
                snapshot.getX(),
                snapshot.getZ(),
                snapshot.getWorldName()
        );
    }

    /**
     * @param x X Coordinate
     * @param z Z Coordinate
     * @return Chunk coordinates with world name
     */
    static Trio<Integer, Integer, String> getChunkKey(int x, int z, String world) {
        return new Trio<>(x, z, world);
    }

    static Chunk getChunkByKey(Server server, Trio<Integer, Integer, String> key) {
        String worldName = key.value2();
        World world = server.getWorld(worldName);
        if (world == null) {
            return null;
        }

        int chunkX = key.value0();
        int chunkZ = key.value1();
        return world.getChunkAt(chunkX, chunkZ);
    }
}
