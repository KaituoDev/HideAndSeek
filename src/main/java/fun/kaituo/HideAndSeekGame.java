package fun.kaituo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scoreboard.*;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static fun.kaituo.GameUtils.world;

@SuppressWarnings("ConstantConditions")
public class HideAndSeekGame extends Game implements Listener {

    private static final HideAndSeekGame instance = new HideAndSeekGame((HideAndSeek) Bukkit.getPluginManager().getPlugin("MyGame"));

    private final ArrayList<Player> seekers;
    private final ArrayList<Player> hiders;
    private final HashMap<UUID, Material> disguiseMaterials;
    private final HashMap<UUID, UUID> disguiseFallingBlocks;
    private final HashMap<UUID, Boolean> disguised;
    private final HashMap<UUID, Location> hiderPreviousLocations;
    
    private final Scoreboard mainScoreboard;
    private final Scoreboard gameScoreboard;
    private final Objective mainObjective;
    private final Objective sneakTimeObjective;
    private final Team hidersTeam;

    private final Location hiderStartLocation = new Location(world, -2036.5, 57.0, -20.5);
    private final Location seekerStartLocation = new Location(world, -2021.5, 64.0, 21.5);
    private final Location gameTimeSignLocation = new Location(world, -2006, 89, 0);

    private int gameMainTaskId;
    private int gameTimeExceedTaskId;
    private boolean running;

    private HideAndSeekGame(HideAndSeek plugin) {
        this.plugin = plugin;
        players = plugin.players;
        seekers = new ArrayList<>();
        hiders = new ArrayList<>();
        disguiseMaterials = new HashMap<>();
        disguiseFallingBlocks = new HashMap<>();
        disguised = new HashMap<>();
        hiderPreviousLocations = new HashMap<>();
        mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        gameScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        mainObjective = gameScoreboard.registerNewObjective("game_main", "dummy", "§b方块捉迷藏");
        mainObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        sneakTimeObjective = gameScoreboard.registerNewObjective("sneak_time", "minecraft.custom:minecraft.sneak_time", "Sneak Time");
        hidersTeam = gameScoreboard.registerNewTeam("hiders");
        running = false;
        initializeGame(plugin, "HideAndSeek", "§b方块捉迷藏",
                new Location(world, -1998.5, 92.0, 0.5), new BoundingBox(-2070, 30, -54, -1960, 124, 43));
        initializeButtons(new Location(world, -2006, 90, 1), BlockFace.EAST,
                new Location(world, -1999, 90, -5), BlockFace.SOUTH);
        initializeGameRunnable();
        removeSpectateButton();
    }


    public static HideAndSeekGame getInstance() {
        return instance;
    }

    @Override
    protected void initializeGameRunnable() {
        gameRunnable = () -> {
            players.addAll(getPlayersNearHub(12.5, 3.0, 10.5));
            seekers.addAll(
                    mainScoreboard.getEntryTeam("hnkseekers").getEntries().stream().map(
                            name -> Bukkit.getOnlinePlayers().stream().filter(
                                    p -> p.getName().equals(name)
                            ).filter(p -> players.contains(p))
                                    .findFirst()
                                    .orElseThrow()
                    ).toList()
            );
            hiders.addAll(
                    mainScoreboard.getEntryTeam("hnkhiders").getEntries().stream().map(
                            name -> Bukkit.getOnlinePlayers().stream().filter(
                                            p -> p.getName().equals(name)
                                    ).filter(p -> players.contains(p))
                                    .findFirst()
                                    .orElseThrow()
                    ).toList()
            );
            hiders.addAll(players.stream().filter(p -> !seekers.contains(p) && !hiders.contains(p)).toList());
            for (Player p: hiders) {
                Set<String> tags = p.getScoreboardTags();
                UUID uuid = p.getUniqueId();
                if (tags.contains("hnk1_spruce_planks")) {
                    disguiseMaterials.put(uuid, Material.SPRUCE_PLANKS);
                } else if (tags.contains("hnk1_anvil")) {
                    disguiseMaterials.put(uuid, Material.ANVIL);
                } else if (tags.contains("hnk1_beacon")) {
                    disguiseMaterials.put(uuid, Material.BEACON);
                } else if (tags.contains("hnk1_dark_prismarine")) {
                    disguiseMaterials.put(uuid, Material.DARK_PRISMARINE);
                } else if (tags.contains("hnk1_oak_leaves")) {
                    disguiseMaterials.put(uuid, Material.OAK_LEAVES);
                } else {
                    disguiseMaterials.put(uuid, Material.SPRUCE_PLANKS);
                }
                p.teleport(hiderStartLocation);
                p.setScoreboard(gameScoreboard);
                hidersTeam.addEntity(p);
                hiderPreviousLocations.put(p.getUniqueId(), hiderStartLocation);
                disguised.put(p.getUniqueId(), false);
            }
            for (Player p: seekers) {
                p.teleport(seekerStartLocation);
                p.setScoreboard(gameScoreboard);
            }
            startCountdown(5);
            running = true;
            for (int i = 5; i > 0; --i) {
                int finalI = i;
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    for (Player p: players) {
                        p.showTitle(
                                Title.title(
                                        Component.text("搜寻者还有 " + finalI + " 秒解禁", NamedTextColor.GREEN),
                                        null,
                                        Title.Times.times(
                                                Duration.ofSeconds(2),
                                                Duration.ofSeconds(16),
                                                Duration.ofSeconds(2)
                                        )
                                )
                        );
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0F, 1.0F);
                    }
                    for (Player p: seekers) {
                        p.teleport(hiderStartLocation);
                    }
                }, (60 - i)  * 20L);
            }
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                for (Player p: players) {
                    p.showTitle(
                            Title.title(
                                    Component.text("猎杀时刻！", NamedTextColor.YELLOW),
                                    null,
                                    Title.Times.times(
                                            Duration.ofSeconds(2),
                                            Duration.ofSeconds(16),
                                            Duration.ofSeconds(2)
                                    )
                            )
                    );
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0F, 2.0F);
                }
            }, 60 * 20L);
            gameMainTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                for (Player p: seekers) {
                    hiders.forEach(p1 -> p.hidePlayer(plugin, p1));
                    seekers.forEach(p1 -> p.showPlayer(plugin, p1));
                }
                for (Player p: hiders) {
                    UUID playerId = p.getUniqueId();
                    Location playerLoc = p.getLocation();
                    FallingBlock fallingBlock = getFallingBlock(disguiseFallingBlocks.get(playerId));
                    if (!disguised.get(playerId)) {
                        if (disguiseFallingBlocks.containsKey(playerId) && fallingBlock != null) {
                            fallingBlock.setTicksLived(1);
                            fallingBlock.teleport(p);
                        } else {
                            spawnDisguiseFallingBlock(p);
                        }
                    }
                    Score sneakTime = sneakTimeObjective.getScore(p.getName());
                    if (p.isSneaking() && exactLocationEqual(playerLoc, hiderPreviousLocations.get(playerId)) && !disguised.get(playerId)) {
                        switch (sneakTime.getScore()) {
                            case 100 -> {
                                p.sendActionBar(Component.text("⏺", NamedTextColor.RED)
                                        .append(Component.text("⏺⏺⏺⏺", NamedTextColor.GRAY))
                                );
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 0.8F);
                            }
                            case 200 -> {
                                p.sendActionBar(Component.text("⏺⏺", NamedTextColor.RED)
                                        .append(Component.text("⏺⏺⏺", NamedTextColor.GRAY))
                                );
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.1F);
                            }
                            case 300 -> {
                                p.sendActionBar(Component.text("⏺⏺⏺", NamedTextColor.YELLOW)
                                        .append(Component.text("⏺⏺", NamedTextColor.GRAY))
                                );
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.4F);
                            }
                            case 400 -> {
                                p.sendActionBar(Component.text("⏺⏺⏺⏺", NamedTextColor.YELLOW)
                                        .append(Component.text("⏺", NamedTextColor.GRAY))
                                );
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.7F);
                            }
                            case 500 -> {
                                p.sendActionBar(Component.text("⏺⏺⏺⏺⏺", NamedTextColor.GREEN));
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 2F);
                                seekers.forEach(
                                        p1 -> p1.sendBlockChange(
                                                playerLoc.getBlock().getLocation(),
                                                disguiseMaterials.get(playerId).createBlockData()
                                        )
                                );
                                getFallingBlock(disguiseFallingBlocks.get(playerId)).remove();
                                disguiseFallingBlocks.remove(playerId);
                                disguised.put(playerId, true);
                            }
                        }
                    } else {
                        sneakTime.setScore(0);
                    }
                    hiderPreviousLocations.put(playerId, playerLoc);
                }
                if (hiders.size() == 0) {
                    endGame();
                }
            }, 0, 1);
            gameTimeExceedTaskId = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    this::endGame,
                    (getGameTimeMinutesFromSign(gameTimeSignLocation) + 1) * 60 * 20L
            ).getTaskId();
        };
    }

    private void endGame() {

    }

    @Override
    protected void savePlayerQuitData(Player p) throws IOException {

    }


    @Override
    protected void rejoin(Player player) {

    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK &&
                blockLocationEqual(event.getClickedBlock().getLocation(), gameTimeSignLocation) &&
                !running) {
            int gameTimeMinutes = getGameTimeMinutesFromSign(gameTimeSignLocation);
            if (gameTimeMinutes <= 10) {
                gameTimeMinutes++;
            } else {
                gameTimeMinutes = 1;
            }
            ((Sign) event.getClickedBlock().getState()).setLine(0, "寻找时间为 " + gameTimeMinutes + " 分钟");
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!running) return;
        Player p = event.getPlayer();
        if (!hiders.contains(p)) return;
        if (disguised.get(p.getUniqueId())) {
            seekers.forEach(
                    p1 -> p1.sendBlockChange(
                            p.getLocation().getBlock().getLocation(),
                            Material.AIR.createBlockData()
                    )
            );
            disguised.put(p.getUniqueId(), false);
        }
    }

    private boolean blockLocationEqual(Location l1, Location l2) {
        return l1.getBlockX() == l2.getBlockX() && l1.getBlockY() == l2.getBlockY() && l1.getBlockZ() == l2.getBlockZ();
    }

    private boolean exactLocationEqual(Location l1, Location l2) {
        return l1.getX() == l2.getX() && l1.getY() == l2.getY() && l1.getZ() == l2.getZ();
    }

    private int getGameTimeMinutesFromSign(Location signLocation) {
        Sign sign = (Sign) signLocation.getBlock().getState();
        return Integer.parseInt(sign.getLine(0).split("\\s")[1]);
    }

    private FallingBlock getFallingBlock(UUID uuid) {
        return world.getEntitiesByClass(FallingBlock.class).stream().filter(
                e -> e.getUniqueId().equals(uuid))
                .findFirst().orElse(null);
    }

    private void spawnDisguiseFallingBlock(Player p) {
        Location playerLoc = p.getLocation();
        UUID playerId = p.getUniqueId();
        Location fallingBlockSafeLocation = new Location(world, playerLoc.getX(), playerLoc.getY() + 0.00001, playerLoc.getZ());
        FallingBlock fallingBlock = world.spawnFallingBlock(fallingBlockSafeLocation, disguiseMaterials.get(playerId).createBlockData());
        fallingBlock.setDropItem(false);
        fallingBlock.setHurtEntities(false);
        fallingBlock.setGravity(false);
        fallingBlock.setInvulnerable(true);
        fallingBlock.setSilent(true);
        fallingBlock.setTicksLived(1);
        disguiseFallingBlocks.put(playerId, fallingBlock.getUniqueId());
    }
}
