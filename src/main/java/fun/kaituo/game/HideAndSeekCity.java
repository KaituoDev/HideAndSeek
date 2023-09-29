package fun.kaituo.game;

import fun.kaituo.gameutils.GameUtils;
import fun.kaituo.gameutils.event.PlayerChangeGameEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class HideAndSeekCity extends JavaPlugin implements Listener {

    GameUtils gameUtils;
    World world;
    List<Player> players;

    public static HideAndSeekCityGame getGameInstance() {
        return HideAndSeekCityGame.getInstance();
    }

    public void onEnable() {
        players = new ArrayList<>();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(getGameInstance(), this);

        gameUtils = (GameUtils) Bukkit.getPluginManager().getPlugin("GameUtils");
        if (gameUtils == null) {
            getLogger().severe("No GameUtils instance found.");
            return;
        }
        gameUtils.registerGame(getGameInstance());
        world = gameUtils.getWorld();
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
        gameUtils.unregisterGame(getGameInstance());
    }
}
