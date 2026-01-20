package ru.lebedinets.mc.autochunkloader;

import io.arxila.javatuples.Trio;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Backup implements Serializable {
    public final Trio<Integer, Integer, String>[] observers;
    public final Trio<Integer, Integer, String>[] temporary;


    public Backup(
            Trio<Integer, Integer, String>[] observers,
            Trio<Integer, Integer, String>[] temporary
    ) {
        this.observers = observers;
        this.temporary = temporary;
    }

    private static File getBackupFile(Plugin plugin) {
        return new File(plugin.getDataFolder(), "backup.yml");
    }

    public boolean dump(Plugin plugin) {
        try {
            String filePath = getBackupFile(plugin).getAbsolutePath();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(Paths.get(filePath))));
            out.writeObject(this);
            out.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static Backup load(Plugin plugin) {
        try {
            String filePath = getBackupFile(plugin).getAbsolutePath();
            BukkitObjectInputStream in = new BukkitObjectInputStream(new GZIPInputStream(Files.newInputStream(Paths.get(filePath))));
            Backup backup = (Backup) in.readObject();
            in.close();
            return backup;
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
