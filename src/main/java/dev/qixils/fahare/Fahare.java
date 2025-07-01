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

    private class AddedDatapack {
        String name;
        float chance;

        public AddedDatapack(String name, float chance) {
            this.name = name;
            this.chance = chance;
        }
    }

    private static final Random RANDOM = new Random();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private Path worldContainer;
    private @Nullable Path backupContainer;
    private boolean resetting = false;
    
    // config
    private boolean backup = true;
    private boolean autoReset = true;
    private boolean anyDeath = false;
    private boolean autoRestart = false;
    private int lives = 1;
    private int datapackCount = 10;
    private List<AddedDatapack> addedDatapacks = new ArrayList<>();

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
        for (Locale locale : List.of(Locale.US)) {
            ResourceBundle bundle = ResourceBundle.getBundle("Fahare", locale, UTF8ResourceBundleControl.get());
            registry.registerAll(locale, bundle, false);
        }
        GlobalTranslator.translator().addSource(registry);

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
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        var config = getConfig();
        backup = config.getBoolean("backup", backup);
        autoReset = config.getBoolean("auto-reset", autoReset);
        anyDeath = config.getBoolean("any-death", anyDeath);
        autoRestart = config.getBoolean("auto-restart", autoRestart);
        lives = Math.max(1, config.getInt("lives", lives));
        datapackCount = config.getInt("datapackCount", datapackCount);
        for (Map<?, ?> entry : config.getMapList("added-datapacks")) {
            addedDatapacks.add(new AddedDatapack(
                (String) entry.get("name"),
                ((Number) entry.get("chance")).floatValue()
            ));
        }
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
    
    private void addDatapacks() {
        String datapackFolderPath = Bukkit.getServer().getWorldContainer().getAbsolutePath() + "/new_datapacks";
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
            
            ArrayList<String> datapackIDs = new ArrayList<>();
            
            JsonObject json;
            
            for (int i = 0; i < datapackCount; i++) {
                
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
                
                json = JsonParser.parseString(response.body()).getAsJsonObject();
                
                datapackIDs.add(json.getAsJsonArray("hits").get(0).getAsJsonObject().get("project_id").getAsString());
            }
            
            for (AddedDatapack pack : addedDatapacks) {
                if (Math.random() < pack.chance) {
                    datapackIDs.add(pack.name);
                }
            }
            
            for (String datapackID : datapackIDs) {

                // Build the full URL with facets
                url = String.format("https://api.modrinth.com/v2/project/%s/version?loaders=%s&game_versions=%s",
                        datapackID,
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
        
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), autoRestart ? "restart" : "stop");
    }

    public synchronized void reset() {
        if (resetting)
            return;
        
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
        
        addDatapacks();
    }

    public void resetCheck(boolean death, Player who) {
        if (!autoReset)
            return;
        if (anyDeath && death) {
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "kick @a " + who.getName() + " died :(");
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
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "kick @a Everybody died :(");
        reset();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        addDeathTo(player.getUniqueId());
        if (isAlive(player.getUniqueId()))
            return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            resetCheck(true, player);
        }, 1);
    }
    
    @Override
    public void onLoad() {
        try {
            File dir = new File("new_datapacks");
            if (dir.exists() && dir.isDirectory()) {
                File currentDir = new File(".");
                File[] folders = currentDir.listFiles((_dir, name) -> name.startsWith("world") && new File(_dir, name).isDirectory());

                if (folders != null) {
                    for (File folder : folders) {
                        System.out.println("Deleting: " + folder.getName());
                        deleteDirectoryRecursively(folder.toPath());
                    }
                }
                
                Path world = Paths.get("world");
                Path target = world.resolve("datapacks");
                Path temp = Paths.get("new_datapacks");

                // Ensure 'world' exists
                Files.createDirectories(world);

                // Now move 'new_datapacks' into 'world/datapacks'
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // lol
        }
    }
    
    private static void deleteDirectoryRecursively(Path path) throws IOException {
		if (!Files.exists(path)) return;

		Files.walk(path)
			.sorted((a, b) -> b.compareTo(a)) // Delete children before parent
			.forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException e) {
					System.err.println("Failed to delete " + p + ": " + e.getMessage());
				}
			});
	}
}
