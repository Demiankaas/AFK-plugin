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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
    // For tracking combat: store the last damage time for each player.
    private final Map<Player, Long> lastDamageTimes = new HashMap<>();

    private BukkitTask autoAfkTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AFK Plugin is enabled!");

        // Load disabled players from config
        List<String> disabledList = getConfig().getStringList("disabled-players");
        autoDisabledNames.addAll(disabledList);

        // Load persisted AFK players from config and reset them (so no one remains in spectator mode after a reload)
        List<String> persistedAfk = getConfig().getStringList("afk-players");
        for (String playerName : persistedAfk) {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p != null) {
                p.setGameMode(GameMode.SURVIVAL);
                getLogger().info("Reset AFK status for " + playerName);
            }
        }
        getConfig().set("afk-players", new ArrayList<String>());
        saveConfig();

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
                            // Check if player is in combat; if so, skip auto-AFK.
                            long lastDamage = lastDamageTimes.getOrDefault(player, 0L);
                            int combatDuration = getConfig().getInt("combatlog.duration", 30) * 1000;
                            if (now - lastDamage < combatDuration) {
                                continue;
                            }
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
        // Check if the player was persisted as AFK from a previous session and reset them
        FileConfiguration config = getConfig();
        List<String> persistedAfk = config.getStringList("afk-players");
        if (persistedAfk.contains(player.getName())) {
            player.setGameMode(GameMode.SURVIVAL);
            persistedAfk.remove(player.getName());
            config.set("afk-players", persistedAfk);
            saveConfig();
            String resetMsg = config.getString("messages.login-reset.message", "§eYour AFK status has been reset upon login.");
            player.sendMessage(resetMsg);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        afkPlayers.remove(player);
        lastMoveTimes.remove(player);
        lastDamageTimes.remove(player);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        lastMoveTimes.put(player, System.currentTimeMillis());
        if (afkPlayers.contains(player) && hasMoved(event)) {
            setNotAfk(player, true);
        }
    }

    // When a player changes the held item slot (e.g., presses a number key)
    @EventHandler
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        lastMoveTimes.put(player, System.currentTimeMillis());
        if (afkPlayers.contains(player)) {
            setNotAfk(player, true);
        }
    }

    // Prevent spectator teleport if player is AFK (e.g., trying to TP via spectator mode)
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (afkPlayers.contains(player) && event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.setCancelled(true);
            player.sendMessage(getConfig().getString("messages.tp-restricted.message", "§cYou cannot teleport while AFK."));
            setNotAfk(player, true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            lastDamageTimes.put(player, System.currentTimeMillis());
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

        FileConfiguration config = getConfig();
        int combatDuration = config.getInt("combatlog.duration", 30) * 1000;
        long now = System.currentTimeMillis();
        long lastDamage = lastDamageTimes.getOrDefault(sender, 0L);

        // Prevent toggling AFK if the player was recently damaged.
        if (config.getBoolean("combatlog.enabled", true) && now - lastDamage < combatDuration) {
            String combatMsg = config.getString("combatlog.message", "§cYou cannot go AFK while in combat!");
            sender.sendMessage(combatMsg);
            return;
        }

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
            sender.sendMessage(config.getString("commands.disable-player-success", "Auto-AFK disabled for: {player}").replace("{player}", targetName));
            return;
        }

        // Subcommand: /afk enable <playerName>
        if (args.length >= 3 && args[1].equalsIgnoreCase("enable")) {
            String targetName = args[2];
            if (autoDisabledNames.remove(targetName)) {
                persistDisabledPlayers();
                sender.sendMessage(config.getString("commands.enable-player-success", "Auto-AFK enabled for: {player}").replace("{player}", targetName));
            } else {
                sender.sendMessage(config.getString("commands.player-not-disabled", "Player '{player}' was not disabled.").replace("{player}", targetName));
            }
            return;
        }

        // Subcommand: /afk auto <enable|disable> (for self)
        if (args.length >= 3 && args[1].equalsIgnoreCase("auto")) {
            String option = args[2];
            if (option.equalsIgnoreCase("disable")) {
                autoDisabledNames.add(sender.getName());
                persistDisabledPlayers();
                sender.sendMessage(config.getString("commands.auto-disable-self", "You have disabled auto-AFK for yourself."));
            } else if (option.equalsIgnoreCase("enable")) {
                autoDisabledNames.remove(sender.getName());
                persistDisabledPlayers();
                sender.sendMessage(config.getString("commands.auto-enable-self", "You have enabled auto-AFK for yourself."));
            } else {
                sender.sendMessage("§cUsage: /afk auto <enable|disable>");
            }
            return;
        }

        // Admin command: /afk <playerName>
        if (args.length == 2) {
            if (sender.hasPermission("afk.admin") || sender.isOp()) {
                String targetName = args[1];
                Player target = Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    sender.sendMessage("§cPlayer '" + targetName + "' is not online.");
                    return;
                }
                if (afkPlayers.contains(target)) {
                    setNotAfk(target, false);
                    sender.sendMessage("§aSet " + targetName + " to not AFK.");
                } else {
                    setAfk(target);
                    sender.sendMessage("§aSet " + targetName + " to AFK.");
                }
                return;
            } else {
                sender.sendMessage(config.getString("commands.no-permission", "§cYou do not have permission to use this command."));
                return;
            }
        }

        // Default toggle: /afk for self
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
        sender.sendMessage("§a" + getConfig().getString("commands.reload-success", "AFK plugin config reloaded!"));
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
        // Persist the AFK state by storing the player's name
        List<String> currentAfk = getConfig().getStringList("afk-players");
        if (!currentAfk.contains(player.getName())) {
            currentAfk.add(player.getName());
            getConfig().set("afk-players", currentAfk);
            saveConfig();
        }
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
        // Remove from persisted AFK state
        List<String> currentAfk = getConfig().getStringList("afk-players");
        if (currentAfk.contains(player.getName())) {
            currentAfk.remove(player.getName());
            getConfig().set("afk-players", currentAfk);
            saveConfig();
        }
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
            // Suggest subcommands
            if ("reload".startsWith(lower)) completions.add("reload");
            if ("disable".startsWith(lower)) completions.add("disable");
            if ("enable".startsWith(lower)) completions.add("enable");
            if ("auto".startsWith(lower)) completions.add("auto");
            // For admin: if no subcommand, suggest online player names.
            if ((sender.hasPermission("afk.admin") || sender.isOp()) &&
                    !(lower.startsWith("reload") || lower.startsWith("disable") || lower.startsWith("enable") || lower.startsWith("auto"))) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    completions.add(p.getName());
                }
            }
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
