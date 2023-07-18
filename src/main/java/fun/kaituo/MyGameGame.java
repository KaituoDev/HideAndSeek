package fun.kaituo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.io.IOException;

import static fun.kaituo.GameUtils.world;

public class MyGameGame extends Game implements Listener {

    private static final MyGameGame instance = new MyGameGame((MyGame) Bukkit.getPluginManager().getPlugin("MyGame"));


    private MyGameGame(MyGame plugin) {
        this.plugin = plugin;
        players = plugin.players;
        initializeGame(plugin, "MyGame", "§eMyGame",
                new Location(world, 0, 89, 0), new BoundingBox(0, 0, 0, 0, 0, 0));
        initializeButtons(new Location(world, 0,0,0), BlockFace.NORTH,
                new Location(world, 0,0,0), BlockFace.EAST);
        initializeGameRunnable(new BukkitRunnable() {
            @Override
            public void run() {

            }
        });
    }


    public static MyGameGame getInstance() {
        return instance;
    }


    @Override
    protected void savePlayerQuitData(Player p) throws IOException {

    }


    @Override
    protected void rejoin(Player player) {

    }
}
