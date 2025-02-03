package fun.kaituo.hideandseekcity;

import fun.kaituo.gameutils.GameUtils;
import org.bukkit.Bukkit;
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
        gameUtils = (GameUtils) Bukkit.getPluginManager().getPlugin("GameUtils");
        if (gameUtils == null) {
            getLogger().severe("No GameUtils instance found.");
            return;
        }

        players = new ArrayList<>();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(getGameInstance(), this);

        gameUtils.registerGame(getGameInstance());
        world = gameUtils.getWorld();
    }

    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        HandlerList.unregisterAll((Plugin) this);
        for (Player p: Bukkit.getOnlinePlayers()) {
            if (gameUtils.getPlayerGame(p) == getGameInstance()) {
                Bukkit.dispatchCommand(p, "join Lobby");
            }
        }
        getGameInstance().onDisable();
        gameUtils.unregisterGame(getGameInstance());
    }
}
