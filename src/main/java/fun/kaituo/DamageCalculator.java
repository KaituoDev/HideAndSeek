package fun.kaituo;

import com.google.common.collect.ImmutableList;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

public final class DamageCalculator {
    private static final ImmutableList<Material> movementAffectingMaterials = ImmutableList.of(
            Material.CAVE_VINES,
            Material.CAVE_VINES_PLANT,
            Material.LADDER,
            Material.SCAFFOLDING,
            Material.TWISTING_VINES,
            Material.TWISTING_VINES_PLANT,
            Material.VINE,
            Material.WEEPING_VINES,
            Material.WEEPING_VINES_PLANT,
            Material.WATER,
            Material.LAVA,
            Material.COBWEB,
            Material.POWDER_SNOW
    );

    private static final ImmutableList<Material> trapdoors = ImmutableList.of(
            Material.ACACIA_TRAPDOOR,
            Material.IRON_TRAPDOOR,
            Material.CRIMSON_TRAPDOOR,
            Material.JUNGLE_TRAPDOOR,
            Material.BIRCH_TRAPDOOR,
            Material.DARK_OAK_TRAPDOOR,
            Material.OAK_TRAPDOOR,
            Material.SPRUCE_TRAPDOOR,
            Material.WARPED_TRAPDOOR
    );

    private DamageCalculator() {}

    @SuppressWarnings("ConstantConditions")
    public static double calculateDamage(Player p) {
        double damage = p.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).getValue();
        ItemStack i = p.getInventory().getItemInMainHand();
        damage *= 0.2 + Math.pow((p.getAttackCooldown() * p.getCooldownPeriod() + 0.5) / p.getCooldownPeriod(), 2) * 0.8;
        int sharpness = i.getEnchantmentLevel(Enchantment.DAMAGE_ALL);
        damage += 0.5 * sharpness + 0.5;
        if (canCrit(p)) damage *= 1.5;
        return damage;
    }

    @SuppressWarnings("deprecation")
    public static boolean canCrit(Player p) {
        return p.getFallDistance() > 0 &&
                !p.isOnGround() &&
                !movementGetsAffected(p) &&
                !p.isInsideVehicle() &&
                p.getActivePotionEffects().stream().noneMatch(e ->
                                e.getType().equals(PotionEffectType.BLINDNESS) ||
                                e.getType().equals(PotionEffectType.SLOW_FALLING)) &&
                !p.isSneaking() &&
                !p.isSprinting() &&
                p.getAttackCooldown() > 0.848;
    }

    public static boolean movementGetsAffected(Player p) {
        Material in = p.getLocation().getBlock().getType();
        if (trapdoors.contains(in)) {
            Location l = p.getLocation();
            Location l1 = new Location(l.getWorld(), l.getBlockX(), l.getBlockY() - 1, l.getBlockZ());
            if (!l1.getBlock().getType().equals(Material.LADDER)) return false;
            TrapDoor b = (TrapDoor) l.getBlock().getBlockData();
            Ladder b1 = (Ladder) l1.getBlock().getBlockData();
            return b.isOpen() && b.getFacing().equals(b1.getFacing());
        }
        return movementAffectingMaterials.contains(in);
    }
}
