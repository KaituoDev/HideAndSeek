package fun.kaituo.game;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import com.destroystokyo.paper.ParticleBuilder;
import fun.kaituo.gameutils.Game;
import fun.kaituo.gameutils.PlayerQuitData;
import fun.kaituo.gameutils.utils.GameItemStackTag;
import fun.kaituo.gameutils.utils.ItemStackBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.*;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("ConstantConditions")
public class HideAndSeekCityGame extends Game implements Listener {

    private static final HideAndSeekCityGame instance = new HideAndSeekCityGame((HideAndSeekCity) Bukkit.getPluginManager().getPlugin("HideAndSeekCity"));

    // GAME VARIABLES
    // player maps
    private final ArrayList<Player> seekers;
    private final ArrayList<Player> hiders;

    private final HashMap<UUID, HiderData> hiderDataMap;

    // scoreboards
    private final Scoreboard mainScoreboard;
    private final Scoreboard gameScoreboard;
    private final Objective mainObjective;
    private final Objective sneakTimeObjective;
    private final Team playersTeam;

    // CONSTANTS
    private final Location hiderStartLocation;
    private final Location seekerStartLocation;
    private final Location gameTimeSignLocation;
    private final Location hubTeleportLocation;

    private final ProtocolManager protocolManager;
    private final PacketListener equipmentPacketListener;

    private final ItemStack[] disguiseBlockItems;
    private final ItemStack tauntItem;
    private final ItemStack soundItem;
    private final ItemStack seekerWeaponItem;

    private int gameMainTaskId;
    private int catchingCountdownTaskId;
    private int fakeBlockDisplayTaskId;
    private AtomicInteger catchingCountdown;
    private boolean running;

    private HideAndSeekCityGame(HideAndSeekCity plugin) {
        this.plugin = plugin;

        players = plugin.players;
        seekers = new ArrayList<>();
        hiders = new ArrayList<>();

        hiderDataMap = new HashMap<>();

        mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        gameScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        mainObjective = gameScoreboard.registerNewObjective("game_main", Criteria.DUMMY, Component.text("方块捉迷藏", NamedTextColor.AQUA));
        mainObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        sneakTimeObjective = gameScoreboard.registerNewObjective("sneak_time", Criteria.statistic(Statistic.SNEAK_TIME), Component.text("Sneak Time"));
        playersTeam = gameScoreboard.registerNewTeam("players");
        playersTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        playersTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        playersTeam.setCanSeeFriendlyInvisibles(false);
        playersTeam.setAllowFriendlyFire(true);

        hiderStartLocation = new Location(world, -2036.5, 57.0, -20.5);
        seekerStartLocation = new Location(world, -2021.5, 64.0, 21.5);
        gameTimeSignLocation = new Location(world, -2006, 89, 0);
        hubTeleportLocation = new Location(world, -2000, 89, 0);

        running = false;

        initializeGame(plugin, "HideAndSeekCity", "§b方块捉迷藏-城市",
                new Location(world, -1998.5, 92.0, 0.5));
        initializeButtons(new Location(world, -2006, 90, 1), BlockFace.EAST,
                new Location(world, -1999, 90, -5), BlockFace.SOUTH);
        initializeGameRunnable();
        removeSpectateButton();

        disguiseBlockItems = new ItemStack[] {
                new ItemStack(Material.SPRUCE_PLANKS),
                new ItemStack(Material.ANVIL),
                new ItemStack(Material.BEACON),
                new ItemStack(Material.DARK_PRISMARINE),
                new ItemStack(Material.OAK_LEAVES)
        };
        tauntItem = new ItemStackBuilder(Material.GOLD_NUGGET).setDisplayName("§r§e嘲讽").setLore("§r§5效果: 自己所在位置发出声音以及粒子效果，寻找时间减3秒", "§r§5CD: 15秒").setGameItemStackTag(gameUtils.getNamespacedKey(), new GameItemStackTag()).build();
        soundItem = new ItemStackBuilder(Material.AMETHYST_SHARD).setDisplayName("§r§c发声").setLore("§r§5效果: 所有躲藏者发出声音", "§r§5CD: 30秒").setGameItemStackTag(gameUtils.getNamespacedKey(), new GameItemStackTag()).build();
        seekerWeaponItem = new ItemStackBuilder(Material.DIAMOND_SWORD).setUnbreakable(true).setGameItemStackTag(gameUtils.getNamespacedKey(), new GameItemStackTag()).build();

        protocolManager = ProtocolLibrary.getProtocolManager();
        equipmentPacketListener = new PacketAdapter(
                plugin,
                ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_EQUIPMENT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                if (!seekers.contains(event.getPlayer())) return;  // not a seeker receiving this packet
                int entityId = packet.getIntegers().read(0);
                List<Integer> hiderIds = hiders.stream().map(Entity::getEntityId).toList();
                if (!hiderIds.contains(entityId)) return;
                packet.getSlotStackPairLists().modify(0, equipments -> {
                    for (Pair<EnumWrappers.ItemSlot, ItemStack> pair : equipments) {
                        ItemStack itemStack = new ItemStack(Material.AIR);
                        pair.setSecond(itemStack);
                    }
                    return equipments;
                });
            }
        };
    }


    public static HideAndSeekCityGame getInstance() {
        return instance;
    }

    protected void initializeGameRunnable() {
        gameRunnable = () -> {
            // add players and assign init roles
            players.addAll(getPlayersNearHub(12.5, 3.0, 10.5));
            seekers.addAll(
                    mainScoreboard.getTeam("hnk1seekers").getEntries().stream()
                            .map(Bukkit::getPlayer)
                            .filter(Objects::nonNull)
                            .toList()
            );
            hiders.addAll(
                    mainScoreboard.getTeam("hnk1hiders").getEntries().stream()
                            .map(Bukkit::getPlayer)
                            .filter(Objects::nonNull)
                            .toList()
            );
            hiders.addAll(players.stream().filter(p -> !seekers.contains(p) && !hiders.contains(p)).toList());

            // check if there's enough players
            if (seekers.isEmpty() || hiders.isEmpty()) {
                players.forEach(p -> p.sendMessage(Component.text("人数不足，无法开始游戏！", NamedTextColor.RED)));
                seekers.clear();
                hiders.clear();
                players.clear();
                return;
            }

            gameUUID = UUID.randomUUID();

            // ======== BEFORE HIDING =======
            for (Player hider: hiders) {
                // init hider data
                UUID hiderId = hider.getUniqueId();
                HiderData hiderData = new HiderData();
                hiderData.previousLocation = hiderStartLocation;
                hiderData.disguised = false;
                hiderData.disguiseMaterial = Material.SPRUCE_PLANKS;
                hiderData.fakeBlockDisplayId = null;    // will be modified later
                hiderDataMap.put(hiderId, hiderData);
            }
            players.forEach(playersTeam::addPlayer);
            startCountdown(5);  // game start countdown

            // ======== HIDING ========
            taskIds.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p: players) {
                    p.setScoreboard(gameScoreboard);
                }
                for (Player seeker: seekers) {
                    seeker.teleport(seekerStartLocation);
                    seeker.setRespawnLocation(seekerStartLocation, true);
                }
                for (Player hider: hiders) {
                    hider.teleport(hiderStartLocation);
                    hider.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 5 * 20, 4));
                    hider.setRespawnLocation(hiderStartLocation, true);
                    Inventory inventory = hider.getInventory();
                    int index = 2;
                    for (ItemStack blockItem: disguiseBlockItems) {
                        inventory.setItem(index, blockItem);
                        index++;
                    }
                }
                protocolManager.addPacketListener(equipmentPacketListener); // hiders fully invisible
                running = true;
            }, 5 * 20L).getTaskId());
            // hiding countdown
            AtomicInteger graceCountdown = new AtomicInteger(60);
            int graceCountdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                mainObjective.getScore("准备躲藏时间").setScore(graceCountdown.get());
                graceCountdown.getAndDecrement();
            }, 5 * 20L, 20L);
            taskIds.add(graceCountdownTaskId);

            // ======== BEFORE CATCHING ========
            // before catching countdown
            for (int i = 5; i > 0; --i) {
                int finalI = i;
                taskIds.add(Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
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
                }, (65 - i)  * 20L).getTaskId());
            }
            taskIds.add(Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                for (Player p: players) {
                    p.showTitle(
                            Title.title(
                                    Component.text("开始追捕！", NamedTextColor.YELLOW),
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

                // player state update
                for (Player hider: hiders) {
                    hider.getInventory().setItem(0, tauntItem);
                    hider.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 1, false, false));
                }
                for (Player seeker: seekers) {
                    seeker.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 4));
                    seeker.teleport(hiderStartLocation);
                    seeker.setRespawnLocation(hiderStartLocation, true);
                    seeker.getInventory().addItem(soundItem);
                    seeker.getInventory().addItem(seekerWeaponItem);
                }

                // clean grace countdown
                Bukkit.getScheduler().cancelTask(graceCountdownTaskId);
                taskIds.remove((Object) graceCountdownTaskId);
                mainObjective.getScore("准备躲藏时间").resetScore();
            }, 65 * 20L).getTaskId());

            // ======== CATCHING ========
            // game main task
            gameMainTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                // sneak for 5 sec to disguise
                for (Player hider: hiders) {
                    UUID hiderId = hider.getUniqueId();
                    Location hiderLoc = hider.getLocation();
                    HiderData hiderData = hiderDataMap.get(hiderId);
                    Score sneakTime = sneakTimeObjective.getScore(hider.getName());
                    if (hider.isSneaking() && getDistance(hiderLoc, hiderData.previousLocation) <= 0.2 && !hiderData.disguised) {
                        switch (sneakTime.getScore()) {
                            case 20 -> {
                                hider.sendActionBar(Component.text("⏺", NamedTextColor.RED)
                                        .append(Component.text("⏺⏺⏺⏺", NamedTextColor.GRAY))
                                );
                                hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 0.8F);
                            }
                            case 40 -> {
                                hider.sendActionBar(Component.text("⏺⏺", NamedTextColor.RED)
                                        .append(Component.text("⏺⏺⏺", NamedTextColor.GRAY))
                                );
                                hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.1F);
                            }
                            case 60 -> {
                                hider.sendActionBar(Component.text("⏺⏺⏺", NamedTextColor.YELLOW)
                                        .append(Component.text("⏺⏺", NamedTextColor.GRAY))
                                );
                                hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.4F);
                            }
                            case 80 -> {
                                hider.sendActionBar(Component.text("⏺⏺⏺⏺", NamedTextColor.YELLOW)
                                        .append(Component.text("⏺", NamedTextColor.GRAY))
                                );
                                hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.7F);
                            }
                            case 100 -> {
                                hider.sendActionBar(Component.text("⏺⏺⏺⏺⏺", NamedTextColor.GREEN));
                                hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 2F);
                                hiderData.disguised = true;
                            }
                        }
                    } else {
                        sneakTime.setScore(0);
                    }
                    if (hiderData.disguised) {
                        hider.sendActionBar(Component.text("伪装中", Style.style(NamedTextColor.DARK_AQUA, TextDecoration.BOLD)));
                        players.forEach(
                                p1 -> {
                                    if (!p1.getUniqueId().equals(hiderId))
                                        p1.sendBlockChange(
                                                hiderLoc.getBlock().getLocation(),
                                                hiderData.disguiseMaterial.createBlockData()
                                        );
                                }
                        );
                    }
                    hiderData.previousLocation = hiderLoc;
                }
                mainObjective.getScore("剩余躲藏者").setScore(hiders.size());
                if (hiders.isEmpty() || catchingCountdown.get() == 0) {
                    endGame(false);
                }
            }, 5 * 20L, 1);
            taskIds.add(gameMainTaskId);
            // create or teleport fake blocks
            fakeBlockDisplayTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                for (Player hider: hiders) {
                    UUID hiderId = hider.getUniqueId();
                    Location hiderLoc = hider.getLocation();
                    HiderData hiderData = hiderDataMap.get(hiderId);
                    BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(hiderId);
                    if (fakeBlockDisplay == null) {
                        fakeBlockDisplay = spawnFakeBlockDisplay(hider);
                        hiderData.fakeBlockDisplayId = fakeBlockDisplay.getUniqueId();
                    }
                    if (hiderData.disguised) {
                        fakeBlockDisplay.teleport(offsetLocation(hiderLoc.getBlock().getLocation(), 0.00125, 0.00125, 0.00125));
                        fakeBlockDisplay.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 0), new Vector3f(0.9975f, 0.9975f, 0.9975f), new AxisAngle4f(0, 0, 0, 0)));
                    } else {
                        fakeBlockDisplay.teleport(offsetLocation(removePitchYaw(hiderLoc), -0.5, 0, -0.5));
                    }
                }
            }, 5 * 20L, 1);
            taskIds.add(fakeBlockDisplayTaskId);
            catchingCountdown = new AtomicInteger(getGameTimeMinutesFromSign(gameTimeSignLocation) * 60);
            catchingCountdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                mainObjective.getScore("剩余时间").setScore(catchingCountdown.get());
                catchingCountdown.getAndDecrement();
            }, 65 * 20L, 20L);
            taskIds.add(catchingCountdownTaskId);
        };
    }

    private void endGame(boolean disabling) {
        // clean tasks
        cancelGameTasks();

        // clean scoreboards
        mainObjective.getScore("剩余时间").resetScore();
        mainObjective.getScore("剩余躲藏者").resetScore();

        running = false;

        // show win effects
        Component winMessage;
        if (!hiders.isEmpty()) {
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
            p.clearActivePotionEffects();
        }

        // show hiders
        seekers.forEach(p -> hiders.forEach(p1 -> p.showPlayer(plugin, p1)));
        hiders.forEach(p -> {
            BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(p.getUniqueId());
            if (fakeBlockDisplay != null) {
                fakeBlockDisplay.remove();
            }
        });
        hiderDataMap.forEach((id, data) -> {
            if (data.disguised) {
                Player hider = Bukkit.getPlayer(id);
                players.forEach(
                        p1 -> p1.sendBlockChange(
                                hider.getLocation().getBlock().getLocation(),
                                Material.AIR.createBlockData()
                        )
                );
            }
        });

        // clean player maps
        seekers.clear();
        hiders.clear();
        hiderDataMap.clear();
        protocolManager.removePacketListener(equipmentPacketListener);
        if (!disabling) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p : players) {
                    p.teleport(hubTeleportLocation);
                }
                players.clear();
            }, 3 * 20L);
        }
    }

    @Override
    protected void quit(Player p) {
        if (!players.contains(p)) return;
        PlayerQuitData quitData = new PlayerQuitData(p, this, gameUUID);
        quitData.getData().put("role", hiders.contains(p) ? "hider": "seeker");
        gameUtils.setPlayerQuitData(p.getUniqueId(), quitData);
        playersTeam.removePlayer(p);
        players.remove(p);
        hiders.remove(p);
        seekers.remove(p);
    }

    @Override
    protected boolean rejoin(Player p) {
        if (!running) {
            p.sendMessage(Component.text("游戏已经结束！").color(NamedTextColor.RED));
            return false;
        }
        PlayerQuitData quitData = gameUtils.getPlayerQuitData(p.getUniqueId());
        if (!quitData.getGameUUID().equals(gameUUID)) {
            p.sendMessage(Component.text("游戏已经结束！").color(NamedTextColor.RED));
            return false;
        }
        switch ((String) quitData.getData().get("role")) {
            case "hider" -> hiders.add(p);
            case "seeker" -> seekers.add(p);
            default -> {
                p.sendMessage(Component.text("重新加入失败！").color(NamedTextColor.RED));
                return false;
            }
        }
        quitData.restoreBasicData(p);
        players.add(p);
        playersTeam.addPlayer(p);
        p.setScoreboard(gameScoreboard);
        gameUtils.setPlayerQuitData(p.getUniqueId(), null);
        return true;
    }

    @Override
    protected boolean join(Player p) {
        p.setRespawnLocation(hubTeleportLocation, true);
        p.teleport(hubTeleportLocation);
        return true;
    }

    @Override
    protected void forceStop() {
        if (!running) return;
        for (Player p : players) {
            p.sendTitlePart(TitlePart.TITLE, Component.text("游戏被强行终止").color(NamedTextColor.RED));
        }
        endGame(false);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK &&
                blockLocationEqual(event.getClickedBlock().getLocation(), gameTimeSignLocation) &&
                !running) {
            // change game time
            Player p = event.getPlayer();
            int gameTimeMinutes = getGameTimeMinutesFromSign(gameTimeSignLocation);
            if (p.isSneaking()) {
                if (gameTimeMinutes > 1) {
                    gameTimeMinutes--;
                } else {
                    gameTimeMinutes = 10;
                }
            } else {
                if (gameTimeMinutes < 10) {
                    gameTimeMinutes++;
                } else {
                    gameTimeMinutes = 1;
                }
            }
            Sign sign = (Sign) gameTimeSignLocation.getBlock().getState();
            sign.getSide(Side.FRONT).line(0, Component.text("寻找时间为 " + gameTimeMinutes + " 分钟"));
            sign.update();
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK &&
                blockLocationEqual(event.getClickedBlock().getLocation(), startButtonLocation) &&
                !running) {
            // click button to start game
            startGame();
        } else if (event.getAction().isLeftClick() && running) {
            // seeker attack logic (because hiders are, well, hidden!)
            Player seeker = event.getPlayer();
            if (!seekers.contains(seeker)) return;
            RayTraceResult result = world.rayTrace(seeker.getEyeLocation(), seeker.getEyeLocation().getDirection(), 3, FluidCollisionMode.NEVER, true, 0, e1 -> !e1.getUniqueId().equals(seeker.getUniqueId()));
            if (result == null) return;
            Block hitBlock = result.getHitBlock();
            Entity hitEntity = result.getHitEntity();
            Player hider;
            if (hitBlock != null) {
                hider = hiders.stream().filter(oneHider -> blockLocationEqual(oneHider.getLocation(), hitBlock.getLocation())).findFirst().orElse(null);
            } else if (hitEntity != null) {
                if (hitEntity instanceof Player hitPlayer) {
                    if (!hiders.contains(hitPlayer)) return;
                    hider = hitPlayer;
                } else if (hitEntity instanceof BlockDisplay fakeBlockDisplay) {
                    Map.Entry<UUID, HiderData> hiderEntry = hiderDataMap.entrySet().stream().filter(entry -> entry.getValue().fakeBlockDisplayId.equals(fakeBlockDisplay.getUniqueId())).findFirst().orElse(null);
                    if (hiderEntry == null) return;
                    hider = Bukkit.getPlayer(hiderEntry.getKey());
                } else return;
            } else return;
            if (hider == null) return;
            seeker.attack(hider);
        } else if (event.getAction().isRightClick() && running) {
            // various props
            Player player = event.getPlayer();
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.equals(tauntItem) && hiders.contains(player)) {
                // hiders taunt (-3s)
                world.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.4F, 1.0F);
                new ParticleBuilder(Particle.NOTE).location(player.getEyeLocation()).count(1).receivers(players).force(true).spawn();
                BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(player.getUniqueId());
                if (fakeBlockDisplay != null) {
                    fakeBlockDisplay.setGlowing(true);
                }
                taskIds.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    BlockDisplay fakeBlockDisplayAfter = getFakeBlockDisplay(player.getUniqueId());
                    if (fakeBlockDisplayAfter != null) {
                        fakeBlockDisplayAfter.setGlowing(false);
                    }
                }, 3 * 20L).getTaskId());
                int countdownNow = catchingCountdown.get();
                catchingCountdown.set(countdownNow < 3 ? 0 : countdownNow - 3);
                player.getInventory().remove(tauntItem);
                taskIds.add(Bukkit.getScheduler().runTaskLater(plugin, () -> player.getInventory().addItem(tauntItem), 15 * 20L).getTaskId());
            } else if (item.equals(soundItem) && seekers.contains(player)) {
                // seekers make hiders make sound and particles
                hiders.forEach(p1 -> world.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.3F, 1.0F));
                seekers.forEach(p1 -> p1.getInventory().remove(soundItem));
                taskIds.add(Bukkit.getScheduler().runTaskLater(plugin, () -> seekers.forEach(p1 -> p1.getInventory().addItem(soundItem)), 30 * 20L).getTaskId());
            } else if (hiders.contains(player)) {
                // hiders change disguise material
                HiderData hiderData = hiderDataMap.get(player.getUniqueId());
                List<Material> blockMaterials = List.of(
                        Material.SPRUCE_PLANKS,
                        Material.ANVIL,
                        Material.BEACON,
                        Material.DARK_PRISMARINE,
                        Material.OAK_LEAVES
                );
                if (blockMaterials.contains(item.getType())) {
                    hiderData.disguiseMaterial = item.getType();
                    BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(player.getUniqueId());
                    if (fakeBlockDisplay != null) fakeBlockDisplay.remove();
                }
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!running) return;
        Player p = event.getPlayer();
        if (!hiders.contains(p)) return;
        HiderData hiderData = hiderDataMap.get(p.getUniqueId());
        if (!hiderData.disguised) return;
        if (getDistance(event.getFrom(), event.getTo()) <= 0.1) return;
        players.forEach(
                p1 -> p1.sendBlockChange(
                        p.getLocation().getBlock().getLocation(),
                        Material.AIR.createBlockData()
                )
        );
        hiderData.disguised = false;
        BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(p.getUniqueId());
        if (fakeBlockDisplay != null) {
            fakeBlockDisplay.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 0), new Vector3f(0.999f, 0.999f, 0.999f), new AxisAngle4f(0, 0, 0, 0)));
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!running) return;
        Player p = event.getPlayer();
        if (!hiders.contains(p)) return;
        BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(p.getUniqueId());
        if (fakeBlockDisplay != null) {
            fakeBlockDisplay.remove();
        }
        p.getInventory().clear();
        playersTeam.removePlayer(p);
        seekers.add(p);
        hiders.remove(p);
        p.getInventory().addItem(seekerWeaponItem);
    }

    @EventHandler
    public void onPlayerInventoryClick(InventoryClickEvent event) {
        if (!running) return;
        event.setCancelled(true);
    }

    private boolean blockLocationEqual(Location l1, Location l2) {
        return l1.getBlockX() == l2.getBlockX() && l1.getBlockY() == l2.getBlockY() && l1.getBlockZ() == l2.getBlockZ();
    }

    @SuppressWarnings("deprecation")
    private int getGameTimeMinutesFromSign(Location signLocation) {
        Sign sign = (Sign) signLocation.getBlock().getState();
        return Integer.parseInt(sign.getLine(0).split("\\s")[1]);
    }

    private BlockDisplay getFakeBlockDisplay(UUID hiderId) {
        if (hiders.stream().map(Player::getUniqueId).noneMatch(id -> id.equals(hiderId))) {
            return null;
        }
        HiderData data = hiderDataMap.get(hiderId);
        UUID fakeBlockDisplayId = data.fakeBlockDisplayId;
        return world.getEntitiesByClass(BlockDisplay.class)
                .stream()
                .filter(blockDisplay -> blockDisplay.getUniqueId().equals(fakeBlockDisplayId))
                .findFirst()
                .orElse(null);
    }

    private BlockDisplay spawnFakeBlockDisplay(Player hider) {
        Location hiderLoc = hider.getLocation();
        UUID hiderId = hider.getUniqueId();
        HiderData hiderData = hiderDataMap.get(hiderId);
        BlockDisplay fakeBlockDisplay = (BlockDisplay) world.spawnEntity(hiderLoc, EntityType.BLOCK_DISPLAY);
        fakeBlockDisplay.setBlock(hiderData.disguiseMaterial.createBlockData());
        hiderData.fakeBlockDisplayId = fakeBlockDisplay.getUniqueId();
        return fakeBlockDisplay;
    }

    private double getDistance(Location l1, Location l2) {
        return Math.sqrt(
                    Math.pow(l1.getX() - l2.getX(), 2) +
                    Math.pow(l1.getY() - l2.getY(), 2) +
                    Math.pow(l1.getZ() - l2.getZ(), 2)
        );
    }

    public void onDisable() {
        endGame(true);
        mainObjective.unregister();
        sneakTimeObjective.unregister();
    }

    private Location removePitchYaw(Location location) {
        return new Location(world, location.getX(), location.getY(), location.getZ());
    }

    @SuppressWarnings("SameParameterValue")
    private Location offsetLocation(Location baseLocation, double dx, double dy, double dz) {
        return new Location(baseLocation.getWorld(), baseLocation.getX() + dx, baseLocation.getY() + dy, baseLocation.getZ() + dz);
    }

    private static class HiderData {
        Material disguiseMaterial;
        UUID fakeBlockDisplayId;
        boolean disguised;
        Location previousLocation;
    }
}
