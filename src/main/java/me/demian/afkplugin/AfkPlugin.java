package me.demian.afkplugin;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AfkPlugin extends JavaPlugin implements Listener, TabCompleter {

    private final Set<Player> afkPlayers = new HashSet<>();
    private final Map<Player, Long> lastMoveTimes = new HashMap<>();
    // Store disabled players by name so that their setting persists.
    private final Set<String> autoDisabledNames = new HashSet<>();
    // Task for auto-AFK check
    private BukkitTask autoAfkTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AFK Plugin is enabled!");

        // Load disabled players from config
        List<String> disabledList = getConfig().getStringList("disabled-players");
        autoDisabledNames.addAll(disabledList);

        // Initialize lastMoveTimes for online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            lastMoveTimes.put(player, System.currentTimeMillis());
        }

        setupAutoAfkTask();

        // Set this class as the tab completer for the /afk command
        if (getCommand("afk") != null) {
            getCommand("afk").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (autoAfkTask != null) {
            autoAfkTask.cancel();
        }
        getLogger().info("AFK Plugin is disabled!");
    }

    private void setupAutoAfkTask() {
        FileConfiguration config = getConfig();
        boolean autoAfkEnabled = config.getBoolean("auto-afk.enabled", true);
        int timeoutSeconds = config.getInt("auto-afk.timeout", 60);

        if (autoAfkTask != null) {
            autoAfkTask.cancel();
        }

        if (autoAfkEnabled) {
            autoAfkTask = new BukkitRunnable() {
                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (!afkPlayers.contains(player) && !autoDisabledNames.contains(player.getName())) {
                            long lastMove = lastMoveTimes.getOrDefault(player, now);
                            if (now - lastMove >= timeoutSeconds * 1000L) {
                                setAfk(player);
                            }
                        }
                    }
                }
            }.runTaskTimer(this, 20L, 20L); // Run every second (20 ticks)
            getLogger().info("Auto-AFK task started with a timeout of " + timeoutSeconds + " seconds.");
        } else {
            getLogger().info("Auto-AFK is disabled in the config.");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lastMoveTimes.put(player, System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        afkPlayers.remove(player);
        lastMoveTimes.remove(player);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        lastMoveTimes.put(player, System.currentTimeMillis());
        if (afkPlayers.contains(player) && hasMoved(event)) {
            setNotAfk(player, true);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        String message = event.getMessage().trim();
        String[] args = message.split("\\s+");

        if (args.length == 0) return;
        if (!args[0].equalsIgnoreCase("/afk")) return;

        event.setCancelled(true);

        // Subcommand: /afk reload
        if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
            reloadAfkPlugin(sender);
            return;
        }

        // Subcommand: /afk disable <playerName>
        if (args.length >= 3 && args[1].equalsIgnoreCase("disable")) {
            String targetName = args[2];
            autoDisabledNames.add(targetName);
            persistDisabledPlayers();
            String msg = getConfig().getString("commands.disable-player-success", "Auto-AFK disabled for: {player}");
            sender.sendMessage(msg.replace("{player}", targetName));
            return;
        }

        // Subcommand: /afk enable <playerName>
        if (args.length >= 3 && args[1].equalsIgnoreCase("enable")) {
            String targetName = args[2];
            if (autoDisabledNames.remove(targetName)) {
                persistDisabledPlayers();
                String msg = getConfig().getString("commands.enable-player-success", "Auto-AFK enabled for: {player}");
                sender.sendMessage(msg.replace("{player}", targetName));
            } else {
                String msg = getConfig().getString("commands.player-not-disabled", "Player '{player}' was not disabled.");
                sender.sendMessage(msg.replace("{player}", targetName));
            }
            return;
        }

        // Subcommand: /afk auto <enable|disable> (for self)
        if (args.length >= 3 && args[1].equalsIgnoreCase("auto")) {
            String option = args[2];
            if (option.equalsIgnoreCase("disable")) {
                autoDisabledNames.add(sender.getName());
                persistDisabledPlayers();
                String msg = getConfig().getString("commands.auto-disable-self", "You have disabled auto-AFK for yourself.");
                sender.sendMessage(msg);
            } else if (option.equalsIgnoreCase("enable")) {
                autoDisabledNames.remove(sender.getName());
                persistDisabledPlayers();
                String msg = getConfig().getString("commands.auto-enable-self", "You have enabled auto-AFK for yourself.");
                sender.sendMessage(msg);
            } else {
                sender.sendMessage("§cUsage: /afk auto <enable|disable>");
            }
            return;
        }

        // Default toggle: /afk
        if (afkPlayers.contains(sender)) {
            setNotAfk(sender, false);
        } else {
            setAfk(sender);
        }
    }

    private void reloadAfkPlugin(Player sender) {
        reloadConfig();
        autoDisabledNames.clear();
        List<String> disabledList = getConfig().getStringList("disabled-players");
        autoDisabledNames.addAll(disabledList);
        setupAutoAfkTask();
        String msg = getConfig().getString("commands.reload-success", "AFK plugin config reloaded!");
        sender.sendMessage("§a" + msg);
    }

    private void setAfk(Player player) {
        afkPlayers.add(player);
        player.setGameMode(GameMode.SPECTATOR);
        FileConfiguration config = getConfig();
        String title = config.getString("messages.afk.title", "§cYou are now AFK!");
        String subtitle = config.getString("messages.afk.subtitle", "§cMove to return!");
        int fadeIn = config.getInt("messages.afk.fadeIn", 10);
        int stay = config.getInt("messages.afk.stay", 999999);
        int fadeOut = config.getInt("messages.afk.fadeOut", 10);
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    private void setNotAfk(Player player, boolean viaMove) {
        afkPlayers.remove(player);
        player.setGameMode(GameMode.SURVIVAL);
        FileConfiguration config = getConfig();
        String key = viaMove ? "messages.move" : "messages.unafk";
        String title = config.getString(key + ".title", "§eYou are no longer AFK!");
        String subtitle = config.getString(key + ".subtitle", "§eYou are no longer in spectator mode.");
        int fadeIn = config.getInt(key + ".fadeIn", 10);
        int stay = config.getInt(key + ".stay", viaMove ? 60 : 30);
        int fadeOut = config.getInt(key + ".fadeOut", 10);
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    private boolean hasMoved(PlayerMoveEvent event) {
        return event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ();
    }

    /**
     * Persists the autoDisabledNames set into the config.
     */
    private void persistDisabledPlayers() {
        List<String> disabledList = new ArrayList<>(autoDisabledNames);
        getConfig().set("disabled-players", disabledList);
        saveConfig();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("afk")) {
            return completions;
        }
        if (args.length == 1) {
            String lower = args[0].toLowerCase(Locale.ROOT);
            if ("reload".startsWith(lower)) completions.add("reload");
            if ("disable".startsWith(lower)) completions.add("disable");
            if ("enable".startsWith(lower)) completions.add("enable");
            if ("auto".startsWith(lower)) completions.add("auto");
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("disable") || args[0].equalsIgnoreCase("enable")) {
                String current = args[1].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(current)) {
                        completions.add(p.getName());
                    }
                }
            } else if (args[0].equalsIgnoreCase("auto")) {
                String current = args[1].toLowerCase(Locale.ROOT);
                if ("enable".startsWith(current)) completions.add("enable");
                if ("disable".startsWith(current)) completions.add("disable");
            }
        }
        return completions;
    }
}
