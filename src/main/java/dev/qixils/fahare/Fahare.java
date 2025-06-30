package dev.qixils.fahare;

import cloud.commandframework.Command;
import cloud.commandframework.bukkit.CloudBukkitCapabilities;
import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.minecraft.extras.MinecraftExceptionHandler;
import cloud.commandframework.paper.PaperCommandManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationRegistry;
import net.kyori.adventure.util.UTF8ResourceBundleControl;
import org.bukkit.*;
import org.bukkit.packs.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.io.*;
import java.net.URL;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

public final class Fahare extends JavaPlugin implements Listener {

    private static final NamespacedKey REAL_OVERWORLD_KEY = NamespacedKey.minecraft("overworld");
    private static final Random RANDOM = new Random();
    private final NamespacedKey fakeOverworldKey = new NamespacedKey(this, "overworld");
    private final NamespacedKey limboWorldKey = new NamespacedKey(this, "limbo");
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private World limboWorld;
    private Path worldContainer;
    private @Nullable Path backupContainer;
    private boolean resetting = false;
    // config
    private boolean backup = true;
    private boolean autoReset = true;
    private boolean anyDeath = false;
    private int lives = 1;

    private static @NotNull World overworld() {
        return Objects.requireNonNull(Bukkit.getWorld(REAL_OVERWORLD_KEY), "Overworld not found");
    }

    private @NotNull World fakeOverworld() {
        return Objects.requireNonNullElseGet(Bukkit.getWorld(fakeOverworldKey), this::createFakeOverworld);
    }

    private @NotNull World createFakeOverworld() {
        // Create fake overworld
        WorldCreator creator = new WorldCreator(fakeOverworldKey).copy(overworld()).seed(RANDOM.nextLong());
        World world = Objects.requireNonNull(creator.createWorld(), "Could not load fake overworld");
        world.setDifficulty(overworld().getDifficulty());
        return world;
    }

    @Override
    public void onEnable() {
        // Load config
        loadConfig();

        // Create backup folder
        worldContainer = Bukkit.getWorldContainer().toPath();
        backupContainer = worldContainer.resolve("fahare-backups");

        if (!Files.exists(backupContainer)) {
            try {
                Files.createDirectory(backupContainer);
            } catch (Exception e) {
                getComponentLogger().error(translatable("fhr.log.error.backup-folder"), e);
                backupContainer = null;
            }
        }

        // Register i18n
        TranslationRegistry registry = TranslationRegistry.create(new NamespacedKey(this, "translations"));
        registry.defaultLocale(Locale.US);
        for (Locale locale : List.of(Locale.US)) { // TODO: reflection
            ResourceBundle bundle = ResourceBundle.getBundle("Fahare", locale, UTF8ResourceBundleControl.get());
            registry.registerAll(locale, bundle, false);
        }
        GlobalTranslator.translator().addSource(registry);

        // Create limbo world
        WorldCreator creator = new WorldCreator(limboWorldKey)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generatorSettings("{\"biome\":\"minecraft:the_end\",\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}]}");
        limboWorld = creator.createWorld();

        // Create fake overworld
        World fakeOverworld = createFakeOverworld();

        // Register commands
        try {
            final PaperCommandManager<CommandSender> commandManager = PaperCommandManager.createNative(this, CommandExecutionCoordinator.simpleCoordinator());
            if (commandManager.hasCapability(CloudBukkitCapabilities.BRIGADIER)) {
                try {
                    commandManager.registerBrigadier();
                } catch (Exception ignored) {
                }
            }

            // Commands
            // TODO: help command
            // TODO: i18n descriptions
            Command.Builder<CommandSender> cmd = commandManager.commandBuilder("fahare");
            commandManager.command(cmd
                    .literal("reset")
                    .permission("fahare.reset")
                    .handler(c -> {
                        c.getSender().sendMessage(translatable("fhr.chat.resetting"));
                        reset();
                    }));

            // Exception handler
            new MinecraftExceptionHandler<CommandSender>()
                    .withDefaultHandlers()
                    .withDecorator(component -> component.colorIfAbsent(NamedTextColor.RED))
                    .apply(commandManager, sender -> sender);
        } catch (Exception e) {
            getComponentLogger().error(translatable("fhr.log.error.commands"), e);
        }

        // Register events and tasks
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            // Teleport players from real overworld
            Location destination = fakeOverworld.getSpawnLocation();
            for (Player player : overworld().getPlayers()) {
                player.teleport(destination);
            }
        }, 1, 1);
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        var config = getConfig();
        backup = config.getBoolean("backup", backup);
        autoReset = config.getBoolean("auto-reset", autoReset);
        anyDeath = config.getBoolean("any-death", anyDeath);
        lives = Math.max(1, config.getInt("lives", lives));
    }

    public int getDeathsFor(UUID player) {
        return deaths.getOrDefault(player, 0);
    }

    public void addDeathTo(UUID player) {
        deaths.put(player, getDeathsFor(player)+1);
    }

    public boolean isDead(UUID player) {
        return getDeathsFor(player) >= lives;
    }

    public boolean isAlive(UUID player) {
        return !isDead(player);
    }

    private void deleteNextWorld(List<World> worlds, @Nullable Path backupDestination) {
        // check if all worlds are deleted
        if (worlds.isEmpty()) {
            World overworld = fakeOverworld();
            Location spawn = overworld.getSpawnLocation();
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(spawn);
            }
            resetting = false;
            addRandomDatapack();
            return;
        }

        // check if worlds are ticking
        if (Bukkit.isTickingWorlds()) {
            Bukkit.getScheduler().runTaskLater(this, () -> deleteNextWorld(worlds, backupDestination), 1);
            return;
        }

        // get world data
        World world = worlds.remove(0);
        String worldName = world.getName();
        Component worldKey = text(worldName);
        WorldCreator creator = new WorldCreator(worldName, world.getKey());
        creator.copy(world).seed(RANDOM.nextLong());

        // unload world
        if (Bukkit.unloadWorld(world, backup)) {
            try {
                Path worldFolder = worldContainer.resolve(worldName);
                Component arg = text(worldFolder.toString());
                if (backupDestination != null) {
                    // Backup world
                    getComponentLogger().info(translatable("fhr.log.info.backup", arg));
                    Files.move(worldFolder, backupDestination.resolve(worldName));
                } else {
                    // Delete world
                    getComponentLogger().info(translatable("fhr.log.info.delete", arg));
                    IOUtils.deleteDirectory(worldFolder);
                }

                // create new world
                creator.createWorld();
                Bukkit.getServer().sendMessage(translatable("fhr.chat.success", worldKey));
            } catch (Exception e) {
                Component error = translatable("fhr.chat.error", NamedTextColor.RED, worldKey);
                Audience.audience(Bukkit.getOnlinePlayers()).sendMessage(error);
                getComponentLogger().warn(error, e);
            }
        } else {
            Bukkit.getServer().sendMessage(translatable("fhr.chat.error", NamedTextColor.RED, worldKey));
        }

        Bukkit.getScheduler().runTaskLater(this, () -> deleteNextWorld(worlds, backupDestination), 1);
    }
    
    private void addRandomDatapack() {
        String datapackFolderPath = Bukkit.getServer().getWorldContainer().getAbsolutePath() + "/world/datapacks";
        File datapackFolder = new File(datapackFolderPath);
        
        for (DataPack data_pack : Bukkit.getServer().getDataPackManager().getDataPacks()) {
            if (data_pack.getSource() == DataPack.Source.WORLD) {
                // System.out.println("Title: " + data_pack.getTitle());
                // System.out.println("Source: " + data_pack.getSource());
                // System.out.println("Data: " + data_pack);
                Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "datapack disable \"file/" + data_pack.getTitle() + "\"");
            }
        }
        
        // delete all existing .zip datapacks
		if (datapackFolder.isDirectory()) {
			File[] files = datapackFolder.listFiles();
			if (files != null) {
				for (File file : files) {
					if (file.isFile()) {
						file.delete();
					}
				}
			}
		}
        
        // add datapacks
        
        try {
            HttpClient client = HttpClient.newHttpClient();
            
            // Build the facets as a JSON string
            String facetsJson = "[[\"versions:1.21.6\"],[\"categories:datapack\"]]";
            String encodedFacets = URLEncoder.encode(facetsJson, StandardCharsets.UTF_8);

            // Build the full URL with facets
            String url = String.format("https://api.modrinth.com/v2/search?limit=1&facets=%s",
                    encodedFacets
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .GET()
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            int numberOfHits = JsonParser.parseString(response.body()).getAsJsonObject().get("total_hits").getAsInt();
            
            int datapacksToAdd = 10;
            
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            
            for (Player player : players) {
                player.removeResourcePacks();
            }
            
            for (int i = 0; i < datapacksToAdd; i++) {
                
                int searchIndex = (int)(Math.random() * numberOfHits);

                // Build the full URL with facets
                url = String.format("https://api.modrinth.com/v2/search?limit=1&offset=%s&facets=%s",
                        Integer.toString(searchIndex),
                        encodedFacets
                );

                request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .GET()
                    .build();

                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                
                // System.out.println(json.getAsJsonArray("hits").get(0));

                // Build the full URL with facets
                url = String.format("https://api.modrinth.com/v2/project/%s/version?loaders=%s&game_versions=%s",
                        json.getAsJsonArray("hits").get(0).getAsJsonObject().get("project_id").getAsString(),
                        URLEncoder.encode("[\"datapack\"]", StandardCharsets.UTF_8),
                        URLEncoder.encode("[\"1.21.6\"]", StandardCharsets.UTF_8)
                );

                request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .GET()
                    .build();

                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                
                json = JsonParser.parseString("{foo:" + response.body() + "}").getAsJsonObject();
                
                String fileURL = json.get("foo").getAsJsonArray().get(0).getAsJsonObject().get("files").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString();
                // System.out.println(fileURL);
                
                for (Player player : players) {
                    player.addResourcePack(UUID.randomUUID(), fileURL, (byte[]) null, null, true);
                }
                
                // System.out.println("Status code: " + response.statusCode());
                // System.out.println("Response body:\n" + response.body());
                
                try (InputStream in = new URL(fileURL).openStream()) {
                    // Extract the file name from the URL
                    String fileName = Paths.get(new URL(fileURL).getPath()).getFileName().toString();

                    // Create full target path
                    Path targetPath = Paths.get(datapackFolderPath, fileName);

                    // Create directories if they don't exist
                    Files.createDirectories(Paths.get(datapackFolderPath));

                    // Copy input stream to the target path
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);

                    System.out.println("File downloaded to: " + targetPath);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.out.println("Failed to get datapacks");
        }
        
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload");
    }

    public synchronized void reset() {
        if (resetting)
            return;
        if (limboWorld == null)
            return;
        deaths.clear();
        // teleport all players to limbo
        Location destination = new Location(limboWorld, 0, 100, 0);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setLevel(0);
            player.setExp(0);
            player.teleport(destination);
            player.setHealth(20);
            player.setFoodLevel(20);
            player.setSaturation(5);
        }
        // check if worlds are ticking
        if (Bukkit.isTickingWorlds()) {
            Bukkit.getScheduler().runTaskLater(this, this::reset, 1);
            return;
        }
        resetting = true;
        // calculate backup folder
        Path backupDestination = null;
        if (backup && backupContainer != null) {
            String baseName = ISO_LOCAL_DATE.format(LocalDate.now());
            int attempt = 1;
            do {
                String name = baseName + '-' + attempt++;
                backupDestination = backupContainer.resolve(name);
            } while (Files.exists(backupDestination));
            try {
                Files.createDirectory(backupDestination);
            } catch (Exception e) {
                getComponentLogger().error(translatable("fhr.log.error.backup-subfolder", text(backupDestination.toString())), e);
                backupDestination = null;
            }
        }
        // unload and delete worlds
        List<World> worlds = Bukkit.getWorlds().stream()
                .filter(w -> !w.getKey().equals(limboWorldKey) && !w.getKey().equals(REAL_OVERWORLD_KEY))
                .collect(Collectors.toList());
        deleteNextWorld(worlds, backupDestination);
    }

    public void resetCheck(boolean death) {
        if (!autoReset)
            return;
        if (anyDeath && death) {
            reset();
            return;
        }
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty())
            return;
        for (Player player : players) {
            if (isAlive(player.getUniqueId()))
                return;
        }
        reset();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        addDeathTo(player.getUniqueId());
        if (isAlive(player.getUniqueId()))
            return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            player.setGameMode(GameMode.SPECTATOR);
            player.spigot().respawn();
            resetCheck(true);
        }, 1);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPortal(EntityPortalEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        World toWorld = to.getWorld();
        if (toWorld == null) return;
        if (!toWorld.getKey().equals(REAL_OVERWORLD_KEY)) return;

        // check if player is coming from the end, and if so just send them to spawn
        if (event.getPortalType() == PortalType.ENDER)
            event.setTo(fakeOverworld().getSpawnLocation());
            // else just update the world
        else
            to.setWorld(fakeOverworld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location to = event.getTo();
        World toWorld = to.getWorld();
        if (toWorld == null) return;
        if (!toWorld.getKey().equals(REAL_OVERWORLD_KEY)) return;

        // check if player is coming from the end, and if so just send them to spawn
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL)
            event.setTo(fakeOverworld().getSpawnLocation());
            // else just update the world
        else
            to.setWorld(fakeOverworld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getKey().equals(REAL_OVERWORLD_KEY))
            player.teleport(fakeOverworld().getSpawnLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Location destination = event.getRespawnLocation();
        if (destination.getWorld().getKey().equals(REAL_OVERWORLD_KEY))
            event.setRespawnLocation(fakeOverworld().getSpawnLocation());
    }
}
