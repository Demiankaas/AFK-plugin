package me.demian.afkplugin;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;

public class AfkPlugin extends JavaPlugin implements Listener {

    private final Set<Player> afkPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        // Zorg dat de standaard config wordt opgeslagen als er nog geen is
        saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AFK Plugin is enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("AFK Plugin is disabled!");
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (event.getMessage().equalsIgnoreCase("/afk")) {
            event.setCancelled(true);
            FileConfiguration config = getConfig();
            if (afkPlayers.contains(player)) {
                // Zet speler terug naar Survival en geef de "Je bent niet meer AFK" melding
                afkPlayers.remove(player);
                player.setGameMode(GameMode.SURVIVAL);
                String title = config.getString("messages.unafk.title", "§eJe bent niet meer AFK!");
                String subtitle = config.getString("messages.unafk.subtitle", "§eJe bent geen spectator meer.");
                int fadeIn = config.getInt("messages.unafk.fadeIn", 10);
                int stay = config.getInt("messages.unafk.stay", 30);
                int fadeOut = config.getInt("messages.unafk.fadeOut", 10);
                player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
            } else {
                // Zet speler in Spectator Mode en geef de "Je bent nu AFK" melding
                afkPlayers.add(player);
                player.setGameMode(GameMode.SPECTATOR);
                String title = config.getString("messages.afk.title", "§cJe bent nu AFK!");
                String subtitle = config.getString("messages.afk.subtitle", "§cBeweeg om terug te keren!");
                int fadeIn = config.getInt("messages.afk.fadeIn", 10);
                int stay = config.getInt("messages.afk.stay", 999999);
                int fadeOut = config.getInt("messages.afk.fadeOut", 10);
                player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (afkPlayers.contains(player) && hasMoved(event)) {
            // Zet speler terug naar Survival bij beweging en geef de "Je bent niet meer AFK" melding
            afkPlayers.remove(player);
            player.setGameMode(GameMode.SURVIVAL);
            FileConfiguration config = getConfig();
            String title = config.getString("messages.move.title", "§eJe bent niet meer AFK!");
            String subtitle = config.getString("messages.move.subtitle", "§eJe bent geen spectator meer.");
            int fadeIn = config.getInt("messages.move.fadeIn", 10);
            int stay = config.getInt("messages.move.stay", 60);
            int fadeOut = config.getInt("messages.move.fadeOut", 10);
            player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    private boolean hasMoved(PlayerMoveEvent event) {
        return event.getFrom().getX() != event.getTo().getX() ||
                event.getFrom().getY() != event.getTo().getY() ||
                event.getFrom().getZ() != event.getTo().getZ();
    }
}
