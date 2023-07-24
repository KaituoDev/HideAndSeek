package fun.kaituo;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import com.destroystokyo.paper.ParticleBuilder;
import fun.kaituo.utils.ItemStackBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static fun.kaituo.GameUtils.world;

@SuppressWarnings("ConstantConditions")
public class HideAndSeekGame extends Game implements Listener {

    private static final HideAndSeekGame instance = new HideAndSeekGame((HideAndSeek) Bukkit.getPluginManager().getPlugin("HideAndSeek"));

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
    private final Location hubTeleportLocation = new Location(world, -2000, 89, 0);

    private final ProtocolManager protocolManager;
    private final PacketListener equipmentPacketListener;

    private final ItemStack tauntItem;
    private final ItemStack soundItem;
    private final ItemStack seekerWeaponItem;

    private int gameMainTaskId;
    private int gameCountdownTaskId;
    private int gameFallingBlockTaskId;
    private AtomicInteger gameCountdown;
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
        mainObjective = gameScoreboard.registerNewObjective("game_main", "dummy", Component.text("方块捉迷藏", NamedTextColor.AQUA));
        mainObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        sneakTimeObjective = gameScoreboard.registerNewObjective("sneak_time", "minecraft.custom:minecraft.sneak_time", Component.text("Sneak Time"));
        hidersTeam = gameScoreboard.registerNewTeam("hiders");
        hidersTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        tauntItem = new ItemStackBuilder(Material.GOLD_NUGGET).setDisplayName("§r§e嘲讽").setLore("§r§5效果: 自己所在位置发出声音以及粒子效果，寻找时间减3秒", "§r§5CD: 15秒").build();
        soundItem = new ItemStackBuilder(Material.AMETHYST_SHARD).setDisplayName("§r§c发声").setLore("§r§5效果: 所有躲藏者发出声音", "§r§5CD: 30秒").build();
        seekerWeaponItem = new ItemStackBuilder(Material.DIAMOND_SWORD).setUnbreakable(true).build();
        running = false;
        initializeGame(plugin, "HideAndSeek", "§b方块捉迷藏",
                new Location(world, -1998.5, 92.0, 0.5), new BoundingBox(-2070, 30, -54, -1960, 124, 43));
        initializeButtons(new Location(world, -2006, 90, 1), BlockFace.EAST,
                new Location(world, -1999, 90, -5), BlockFace.SOUTH);
        initializeGameRunnable();
        removeSpectateButton();
        protocolManager = ProtocolLibrary.getProtocolManager();
        equipmentPacketListener = new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_EQUIPMENT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!event.getPacket().getType().equals(PacketType.Play.Server.ENTITY_EQUIPMENT)) return;
                if (!seekers.contains(event.getPlayer())) return;
                Entity e = event.getPacket().getEntityModifier(world).read(0);
                if (!(e instanceof Player p)) return;
                if (!hiders.contains(p)) return;
                PacketContainer container = event.getPacket();
                StructureModifier<List<Pair<EnumWrappers.ItemSlot, ItemStack>>> modifier = container.getSlotStackPairLists();
                EnumWrappers.ItemSlot slot = modifier.read(0).get(0).getFirst();
                modifier.write(0, List.of(new Pair<>(slot, new ItemStack(Material.AIR))));
            }
        };
    }


    public static HideAndSeekGame getInstance() {
        return instance;
    }

    @Override
    protected void initializeGameRunnable() {
        gameRunnable = () -> {
            players.addAll(getPlayersNearHub(12.5, 3.0, 10.5));
            seekers.addAll(
                    mainScoreboard.getTeam("hnkseekers").getEntries().stream().map(
                            name -> Bukkit.getOnlinePlayers().stream().filter(
                                    p -> p.getName().equals(name)
                            ).filter(p -> players.contains(p))
                                    .findFirst()
                                    .orElseThrow()
                    ).toList()
            );
            hiders.addAll(
                    mainScoreboard.getTeam("hnkhiders").getEntries().stream().map(
                            name -> Bukkit.getOnlinePlayers().stream().filter(
                                            p -> p.getName().equals(name)
                                    ).filter(p -> players.contains(p))
                                    .findFirst()
                                    .orElseThrow()
                    ).toList()
            );
            hiders.addAll(players.stream().filter(p -> !seekers.contains(p) && !hiders.contains(p)).toList());
            if (seekers.size() < 1 || hiders.size() < 1) {
                players.forEach(p -> p.sendMessage(Component.text("人数不足，无法开始游戏！", NamedTextColor.RED)));
                seekers.clear();
                hiders.clear();
                players.clear();
                return;
            }
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
                p.setBedSpawnLocation(hiderStartLocation, true);
                hidersTeam.addPlayer(p);
            }
            startCountdown(5);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p: players) {
                    p.setScoreboard(gameScoreboard);
                }
                for (Player p: seekers) {
                    p.teleport(seekerStartLocation);
                }
                for (Player p: hiders) {
                    p.teleport(hiderStartLocation);
                    hiderPreviousLocations.put(p.getUniqueId(), hiderStartLocation);
                    disguised.put(p.getUniqueId(), false);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 60 * 20, 4));
                }
                protocolManager.addPacketListener(equipmentPacketListener);
                running = true;
            }, 5 * 20L);
            AtomicInteger graceCountdown = new AtomicInteger(60);
            int graceCountdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                mainObjective.getScore("准备躲藏时间").setScore(graceCountdown.get());
                graceCountdown.getAndDecrement();
            }, 5 * 20L, 20L);
            for (int i = 5; i > 0; --i) {
                int finalI = i;
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                    for (Player p: players) {
                        p.showTitle(
                                Title.title(
                                        Component.text("搜寻者还有 " + finalI + " 秒解禁", NamedTextColor.GREEN),
                                        Component.empty(),
                                        Title.Times.times(
                                                Duration.ofMillis(2 * 50L),
                                                Duration.ofMillis(16 * 50L),
                                                Duration.ofMillis(2 * 50L)
                                        )
                                )
                        );
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0F, 1.0F);
                    }
                }, (65 - i)  * 20L);
            }
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                for (Player p: players) {
                    p.showTitle(
                            Title.title(
                                    Component.text("猎杀时刻！", NamedTextColor.YELLOW),
                                    Component.empty(),
                                    Title.Times.times(
                                            Duration.ofMillis(2 * 50L),
                                            Duration.ofMillis(16 * 50L),
                                            Duration.ofMillis(2 * 50L)
                                    )
                            )
                    );
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0F, 2.0F);
                }
                for (Player p: hiders) {
                    p.getInventory().addItem(tauntItem);
                }
                for (Player p: seekers) {
                    p.teleport(hiderStartLocation);
                    p.getInventory().addItem(soundItem);
                }
                Bukkit.getScheduler().cancelTask(graceCountdownTaskId);
                mainObjective.getScore("准备躲藏时间").resetScore();
            }, 65 * 20L);
            gameMainTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                for (Player p: seekers) {
                    if (!p.getInventory().contains(Material.DIAMOND_SWORD, 1))
                        p.getInventory().addItem(seekerWeaponItem);
                }
                for (Player p: hiders) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 5, 1, false, false));
                    UUID playerId = p.getUniqueId();
                    Location playerLoc = p.getLocation();
                    Score sneakTime = sneakTimeObjective.getScore(p.getName());
                    if (p.isSneaking() && getDistance(playerLoc, hiderPreviousLocations.get(playerId)) <= 0.2 && !disguised.get(playerId)) {
                        switch (sneakTime.getScore()) {
                            case 20 -> {
                                p.sendActionBar(Component.text("⏺", NamedTextColor.RED)
                                        .append(Component.text("⏺⏺⏺⏺", NamedTextColor.GRAY))
                                );
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 0.8F);
                            }
                            case 40 -> {
                                p.sendActionBar(Component.text("⏺⏺", NamedTextColor.RED)
                                        .append(Component.text("⏺⏺⏺", NamedTextColor.GRAY))
                                );
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.1F);
                            }
                            case 60 -> {
                                p.sendActionBar(Component.text("⏺⏺⏺", NamedTextColor.YELLOW)
                                        .append(Component.text("⏺⏺", NamedTextColor.GRAY))
                                );
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.4F);
                            }
                            case 80 -> {
                                p.sendActionBar(Component.text("⏺⏺⏺⏺", NamedTextColor.YELLOW)
                                        .append(Component.text("⏺", NamedTextColor.GRAY))
                                );
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.7F);
                            }
                            case 100 -> {
                                p.sendActionBar(Component.text("⏺⏺⏺⏺⏺", NamedTextColor.GREEN));
                                p.playSound(playerLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 2F);
                                getFallingBlock(disguiseFallingBlocks.get(playerId)).remove();
                                disguiseFallingBlocks.remove(playerId);
                                disguised.put(playerId, true);
                            }
                        }
                    } else {
                        sneakTime.setScore(0);
                    }
                    if (disguised.get(playerId)) {
                        p.sendActionBar(Component.text("伪装中", Style.style(NamedTextColor.DARK_AQUA, TextDecoration.BOLD)));
                        players.forEach(
                                p1 -> {
                                    if (!p1.getUniqueId().equals(playerId))
                                        p1.sendBlockChange(
                                                playerLoc.getBlock().getLocation(),
                                                disguiseMaterials.get(playerId).createBlockData()
                                        );
                                }
                        );
                    }
                    hiderPreviousLocations.put(playerId, playerLoc);
                }
                mainObjective.getScore("剩余躲藏者").setScore(hiders.size());
                if (hiders.size() == 0 || gameCountdown.get() == 0) {
                    endGame();
                }
            }, 5 * 20L, 1);
            taskIds.add(gameMainTaskId);
            gameFallingBlockTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                for (Player p: hiders) {
                    UUID playerId = p.getUniqueId();
                    Location playerLoc = p.getLocation();
                    FallingBlock fallingBlock = getFallingBlock(disguiseFallingBlocks.get(playerId));
                    if (!disguised.get(playerId)) {
                        if (disguiseFallingBlocks.containsKey(playerId) && fallingBlock != null) {
                            fallingBlock.setTicksLived(1);
                            fallingBlock.teleport(getFallingBlockSafeLocation(playerLoc));
                        } else {
                            spawnDisguiseFallingBlock(p);
                        }
                    }
                }
            }, 5 * 20L, 5);
            taskIds.add(gameFallingBlockTaskId);
            gameCountdown = new AtomicInteger(getGameTimeMinutesFromSign(gameTimeSignLocation) * 60);
            gameCountdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                mainObjective.getScore("剩余时间").setScore(gameCountdown.get());
                gameCountdown.getAndDecrement();
            }, 65 * 20L, 20L);
            taskIds.add(gameCountdownTaskId);
        };
    }

    private void endGame() {
        cancelGameTasks();
        mainObjective.getScore("剩余时间").resetScore();
        mainObjective.getScore("剩余躲藏者").resetScore();
        running = false;
        Component winMessage;
        if (hiders.size() > 0) {
            winMessage = Component.text("躲藏者获胜！", NamedTextColor.GOLD);
        } else {
            winMessage = Component.text("搜寻者获胜！", NamedTextColor.GOLD);
        }
        for (Player p: players) {
            p.showTitle(
                    Title.title(
                            winMessage,
                            Component.empty(),
                            Title.Times.times(
                                    Duration.ofMillis(5 * 50L),
                                    Duration.ofMillis(50 * 50L),
                                    Duration.ofMillis(5 * 50L)
                            )
                    )
            );
            spawnFireworks(p);
            p.getInventory().clear();
            p.setScoreboard(mainScoreboard);
        }
        seekers.forEach(p -> hiders.forEach(p1 -> p.showPlayer(plugin, p1)));
        disguiseFallingBlocks.forEach((pid, bid) -> getFallingBlock(bid).remove());
        seekers.clear();
        hiders.clear();
        disguised.clear();
        disguiseMaterials.clear();
        disguiseFallingBlocks.clear();
        protocolManager.removePacketListener(equipmentPacketListener);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p: players) {
                p.teleport(hubTeleportLocation);
            }
            players.clear();
        }, 3 * 20L);
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
            if (gameTimeMinutes < 10) {
                gameTimeMinutes++;
            } else {
                gameTimeMinutes = 1;
            }
            Sign sign = (Sign) gameTimeSignLocation.getBlock().getState();
            sign.line(0, Component.text("寻找时间为 " + gameTimeMinutes + " 分钟"));
            sign.update();
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK &&
                blockLocationEqual(event.getClickedBlock().getLocation(), startButtonLocation) &&
                !running) {
            startGame();
        } else if (event.getAction().isLeftClick() && running) {
            Player p = event.getPlayer();
            if (!seekers.contains(p)) return;
            RayTraceResult result = world.rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), 3, FluidCollisionMode.NEVER, true, 0, e1 -> !e1.getUniqueId().equals(p.getUniqueId()));
            if (result == null) return;
            Entity e = result.getHitEntity();
            if (e == null) return;
            if (!(e instanceof FallingBlock fb)) return;
            if (!disguiseFallingBlocks.containsValue(fb.getUniqueId())) return;
            Player hider = Bukkit.getPlayer(disguiseFallingBlocks.entrySet().stream().filter(entry -> entry.getValue().equals(fb.getUniqueId())).findFirst().orElseThrow().getKey());
            hider.damage(DamageCalculator.calculateDamage(p));
        } else if (event.getAction().isRightClick() && running) {
            Player p = event.getPlayer();
            ItemStack i = p.getInventory().getItemInMainHand();
            if (i.equals(tauntItem) && hiders.contains(p)) {
                world.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.4F, 1.0F);
                new ParticleBuilder(Particle.NOTE).location(p.getEyeLocation()).count(1).allPlayers().force(true).spawn();
                int countdownNow = gameCountdown.get();
                if (countdownNow > 0) gameCountdown.set(countdownNow < countdownNow ? 0 : countdownNow - 3);
                p.getInventory().remove(tauntItem);
                taskIds.add(Bukkit.getScheduler().runTaskLater(plugin, () -> p.getInventory().addItem(tauntItem), 15 * 20L).getTaskId());
            } else if (i.equals(soundItem) && seekers.contains(p)) {
                hiders.forEach(p1 -> world.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.3F, 1.0F));
                seekers.forEach(p1 -> p1.getInventory().remove(soundItem));
                taskIds.add(Bukkit.getScheduler().runTaskLater(plugin, () -> seekers.forEach(p1 -> p1.getInventory().addItem(soundItem)), 30 * 20L).getTaskId());
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!running) return;
        Player p = event.getPlayer();
        if (!hiders.contains(p)) return;
        if (getDistance(event.getFrom(), event.getTo()) <= 0.1) return;
        if (disguised.get(p.getUniqueId())) {
            players.forEach(
                    p1 -> p1.sendBlockChange(
                            p.getLocation().getBlock().getLocation(),
                            Material.AIR.createBlockData()
                    )
            );
            disguised.put(p.getUniqueId(), false);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!running) return;
        Player p = event.getPlayer();
        if (!hiders.contains(p)) return;
        p.getInventory().clear();
        hidersTeam.removePlayer(p);
        seekers.add(p);
        hiders.remove(p);
    }

    private boolean blockLocationEqual(Location l1, Location l2) {
        return l1.getBlockX() == l2.getBlockX() && l1.getBlockY() == l2.getBlockY() && l1.getBlockZ() == l2.getBlockZ();
    }

    private boolean exactLocationEqual(Location l1, Location l2) {
        return l1.getX() == l2.getX() && l1.getY() == l2.getY() && l1.getZ() == l2.getZ();
    }

    @SuppressWarnings("deprecation")
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
        Location fallingBlockSafeLocation = getFallingBlockSafeLocation(playerLoc);
        FallingBlock fallingBlock = world.spawnFallingBlock(fallingBlockSafeLocation, disguiseMaterials.get(playerId).createBlockData());
        fallingBlock.setDropItem(false);
        fallingBlock.setHurtEntities(false);
        fallingBlock.setGravity(false);
        fallingBlock.setInvulnerable(true);
        fallingBlock.setSilent(true);
        fallingBlock.setTicksLived(1);
        disguiseFallingBlocks.put(playerId, fallingBlock.getUniqueId());
    }

    private Location getFallingBlockSafeLocation(Location l) {
        return new Location(l.getWorld(), l.getX(), l.getY() + 0.00001, l.getZ());
    }

    private double getDistance(Location l1, Location l2) {
        return Math.sqrt(
                    Math.pow(l1.getX() - l2.getX(), 2) +
                    Math.pow(l1.getY() - l2.getY(), 2) +
                    Math.pow(l1.getZ() - l2.getZ(), 2)
        );
    }

    public void onDisable() {
        endGame();
        mainObjective.unregister();
        sneakTimeObjective.unregister();
    }
}
