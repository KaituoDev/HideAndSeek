package fun.kaituo;

import fun.kaituo.event.PlayerChangeGameEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

import static fun.kaituo.GameUtils.unregisterGame;
import static fun.kaituo.GameUtils.world;

public class HideAndSeek extends JavaPlugin implements Listener {

    List<Player> players;

    public static HideAndSeekGame getGameInstance() {
        return HideAndSeekGame.getInstance();
    }

    public void onEnable() {
        players = new ArrayList<>();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(getGameInstance(), this);
        GameUtils.registerGame(getGameInstance());
    }

    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        HandlerList.unregisterAll((Plugin) this);
        if (players.size() > 0) {
            for (Player p : players) {
                p.teleport(new Location(world, 0.5, 89.0, 0.5));
                Bukkit.getPluginManager().callEvent(new PlayerChangeGameEvent(p, getGameInstance(), null));
            }
        }
        getGameInstance().onDisable();
        unregisterGame(getGameInstance());
    }
}
