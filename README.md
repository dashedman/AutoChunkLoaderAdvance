# ![logo](https://i.imgur.com/0UFlvHt.png) AutoChunkLoaderAdvance

_Fork of [AutoChunkLoader](https://github.com/mlebdmcdev/AutoChunkLoader) cause it has some bug, 
and doesn't support observers and hoppers._

A simple plugin that loads chunks around long railways and redstone signals, 
and keep chunks with observers and working hoppers (pipes and timers).

It can be used for transporting resources in chest minecarts through whole world, 
making large redstone circuits, making auto-farms on a long distance.

**Keep in mind with incorrect use this plugin can negatively affect server performance!**

---

**Download:**

- [Modrinth](https://modrinth.com/plugin/autochunkloaderadvance)

**Build:**

```commandline
mvn clean package 
```

**Features:**

 - Autoload chunks with **moving minecarts**.
 - Autoload chunks with **propagating redstone signal**.
 - Autoload chunks with **working hoppers** (when they move item to somewhere).
 - Keep chunks with **observers**.
 - Autoload with **observers moved by pistons** (flying machines).
 - Control **dropped items lifetime** on chunks affected by this plugin. (See in config: `droppedItemsLifetime`)

**Installation:**

 - Download jar file
 - Put this file into `/plugins` folder of your Spigot server
 - Restart server
 - Modify `/plugins/AutoChunkLoaderAdvance/config.yml` file for your needs


**Statistic:**

[![stats](https://bstats.org/signatures/bukkit/AutoChunkLoaderAdvance.svg)](https://bstats.org/plugin/bukkit/AutoChunkLoaderAdvance/29121)
