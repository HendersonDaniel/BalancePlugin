package com.balanceplugin;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import com.adminplugin.AdminPlugin;
import com.adminplugin.StaffRank;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.NamespacedKey;

public class BalanceListener implements Listener {

    // lunge wasn't in the 1.21.4 API yet — have to look it up by key at runtime
    private static final NamespacedKey LUNGE_KEY        = NamespacedKey.minecraft("lunge");
    private static final NamespacedKey RECIPES_DOUBLED_KEY = new NamespacedKey("balanceplugin", "recipes_doubled");
    private static Enchantment lungeEnchantment() { return Enchantment.getByKey(LUNGE_KEY); }

    private final JavaPlugin plugin;

    private static final Set<EntityType> BLOCKED_SPAWNER_TYPES = EnumSet.of(
            EntityType.VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN
    );

    private static final Set<Material> BLOCKED_SPAWN_EGGS = EnumSet.of(
            Material.VILLAGER_SPAWN_EGG,
            Material.ZOMBIFIED_PIGLIN_SPAWN_EGG
    );

    private static final Set<Material> AXE_MATERIALS = EnumSet.of(
            Material.WOODEN_AXE,
            Material.STONE_AXE,
            Material.IRON_AXE,
            Material.GOLDEN_AXE,
            Material.DIAMOND_AXE,
            Material.NETHERITE_AXE
    );

    private static final Set<Material> DIAMOND_NETHERITE_ARMOR = EnumSet.of(
            Material.DIAMOND_HELMET,   Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.NETHERITE_HELMET,   Material.NETHERITE_CHESTPLATE,
            Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS
    );

    // axes were way too strong in 1.9+ — this brings them roughly in line with swords
    // non-crit: axe ends up at ~72% of sword damage
    // crit: axe is ~115% of sword crit — still slightly better but not oppressive
    private static final double AXE_NORMAL_MULT = 0.58;
    private static final double AXE_CRIT_MULT   = 0.92;

    public BalanceListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ── 1. Remove gold ore above y=64 on new chunk generation ────────────────

    private static final java.util.Random GOLD_RAND = new java.util.Random();
    // Vanilla badlands produce ~10x normal gold underground.
    // Remove 80% of it to bring it down to ~2x normal.
    private static final double BADLANDS_GOLD_REMOVE_CHANCE = 0.80;

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChunkPopulate(ChunkPopulateEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return;

        Chunk chunk = event.getChunk();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    Material type = block.getType();

                    if (y >= 65) {
                        // Above y=64: remove all gold everywhere
                        if (type == Material.GOLD_ORE) {
                            block.setType(replacementFor(block), false);
                        }
                    } else {
                        // Below y=65: thin badlands gold to ~2x normal
                        if (type == Material.GOLD_ORE || type == Material.DEEPSLATE_GOLD_ORE) {
                            Biome biome = block.getBiome();
                            if (isBadlands(biome) && GOLD_RAND.nextDouble() < BADLANDS_GOLD_REMOVE_CHANCE) {
                                block.setType(y < 0 ? Material.DEEPSLATE : Material.STONE, false);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isBadlands(Biome biome) {
        return biome == Biome.BADLANDS || biome == Biome.ERODED_BADLANDS || biome == Biome.WOODED_BADLANDS;
    }

    private Material replacementFor(Block block) {
        if (isBadlands(block.getBiome())) return Material.TERRACOTTA;
        return Material.STONE;
    }

    // ── 2. Block villager and zombie piglin spawners ──────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnEggOnSpawner(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.SPAWNER) return;
        ItemStack item = event.getItem();
        if (item != null && BLOCKED_SPAWN_EGGS.contains(item.getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Villager and Zombie Piglin spawners are not allowed on this server.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        if (BLOCKED_SPAWNER_TYPES.contains(event.getEntityType())) {
            event.setCancelled(true);
        }
    }

    // ── 3. Remove Mending from the game ──────────────────────────────────────
    // mending made gear essentially indestructible — not fun for an economy server

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(event.getCaught() instanceof Item caught)) return;
        ItemStack item = caught.getItemStack().clone();
        if (removeMending(item)) caught.setItemStack(item);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType() == Material.AIR) return;

        HumanEntity viewer = event.getView().getPlayer();
        boolean creative = viewer instanceof Player p && isAdminCreative(p);
        ItemStack left = event.getInventory().getItem(0);

        if (hasMending(result) && (left == null || !hasMending(left))) {
            event.setResult(null);
            if (viewer instanceof Player p) p.sendMessage(ChatColor.RED + "Mending has been disabled on this server.");
            return;
        }

        if (hasSweeping(result) && (left == null || !hasSweeping(left))) {
            event.setResult(null);
            if (viewer instanceof Player p) p.sendMessage(ChatColor.RED + "Sweeping Edge has been disabled on this server.");
            return;
        }

        if (!creative && hasLunge(result) && (left == null || !hasLunge(left))) {
            event.setResult(null);
            if (viewer instanceof Player p) p.sendMessage(ChatColor.RED + "Lunge has been disabled on this server.");
            return;
        }

        if (hasCurse(result) && (left == null || !hasCurse(left))) {
            ItemStack cleaned = result.clone();
            removeCurses(cleaned);
            event.setResult(cleaned);
            if (viewer instanceof Player p) p.sendMessage(ChatColor.RED + "Curse enchantments are disabled on this server.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVillagerAcquireTrade(VillagerAcquireTradeEvent event) {
        ItemStack result = event.getRecipe().getResult();
        if (hasMending(result) || hasSweeping(result) || hasCurse(result) || hasLunge(result)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLootGenerate(LootGenerateEvent event) {
        event.getLoot().removeIf(item -> hasMending(item));
        event.getLoot().forEach(item -> { if (hasSweeping(item)) removeSweeping(item); });
        event.getLoot().forEach(item -> { if (hasCurse(item)) removeCurses(item); });
        event.getLoot().forEach(item -> { if (hasLunge(item)) removeLunge(item); });
        event.getLoot().removeIf(item -> item != null && item.getType() == Material.TOTEM_OF_UNDYING);
        event.getLoot().removeIf(item -> isInvisibilityPotion(item));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        event.getDrops().removeIf(item -> item != null && item.getType() == Material.TOTEM_OF_UNDYING);
        event.getDrops().removeIf(item -> isInvisibilityPotion(item));
        if (event.getEntity().getType() == EntityType.IRON_GOLEM) {
            event.getDrops().removeIf(item -> item != null && item.getType() == Material.IRON_INGOT);
        }
        if (event.getEntity().getType() == EntityType.ZOMBIFIED_PIGLIN) {
            event.getDrops().removeIf(item -> item != null
                    && (item.getType() == Material.GOLD_NUGGET || item.getType() == Material.GOLD_INGOT));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsumePotion(PlayerItemConsumeEvent event) {
        if (!isInvisibilityPotion(event.getItem())) return;
        if (isAdminCreative(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "Invisibility potions are disabled on this server.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!isInvisibilityPotion(event.getPotion().getItem())) return;
        if (event.getPotion().getShooter() instanceof Player p && isAdminCreative(p)) return;
        event.setCancelled(true);
    }

    // ── 4. Remove Sweeping Edge and Lunge from enchanting table ──────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        event.getEnchantsToAdd().remove(Enchantment.SWEEPING_EDGE);
        event.getEnchantsToAdd().remove(Enchantment.BINDING_CURSE);
        event.getEnchantsToAdd().remove(Enchantment.VANISHING_CURSE);
        if (!isAdminCreative(event.getEnchanter())) {
            Enchantment lunge = lungeEnchantment();
            if (lunge != null) event.getEnchantsToAdd().remove(lunge);
        }
    }

    // ── 5. Block End Crystal crafting ────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && result.getType() == Material.END_CRYSTAL) {
            event.getInventory().setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraftEndCrystal(CraftItemEvent event) {
        if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.END_CRYSTAL) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) {
                p.sendMessage(ChatColor.RED + "End Crystals cannot be crafted on this server.");
            }
        }
    }

    // ── 7. Axe damage nerf ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAxeDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!AXE_MATERIALS.contains(weapon.getType())) return;

        double multiplier = isCritical(player) ? AXE_CRIT_MULT : AXE_NORMAL_MULT;
        event.setDamage(event.getDamage() * multiplier);
    }

    // Crit conditions: player is falling and not on the ground
    private boolean isCritical(Player player) {
        return player.getFallDistance() > 0
                && !player.isOnGround()
                && !player.isInsideVehicle()
                && player.getVelocity().getY() < 0;
    }

    // ── 8. Respawn Anchor explodes when placed in overworld ───────────────────
    // vanilla already blows them up if you USE them in overworld, but placing is fine by default
    // we want to discourage people from trying to use them as grief tools

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawnAnchorPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.RESPAWN_ANCHOR) return;
        World world = event.getBlock().getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return;

        Location loc = event.getBlock().getLocation().clone().add(0.5, 0.5, 0.5);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block b = loc.getBlock();
            if (b.getType() == Material.RESPAWN_ANCHOR) b.setType(Material.AIR);
            world.createExplosion(loc.getX(), loc.getY(), loc.getZ(), 5.0f, true, true);
        });
    }

    // ── 9. Dolphin's Grace and Hero of the Village disabled ───────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffectApply(EntityPotionEffectEvent event) {
        if (event.getAction() == EntityPotionEffectEvent.Action.REMOVED) return;
        if (!(event.getEntity() instanceof Player)) return;
        PotionEffect newEffect = event.getNewEffect();
        if (newEffect == null) return;
        PotionEffectType type = newEffect.getType();
        if (type == PotionEffectType.DOLPHINS_GRACE || type == PotionEffectType.HERO_OF_THE_VILLAGE) {
            event.setCancelled(true);
        }
    }

    // ── 10. Villager trade price doubling (no discounts) ─────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onVillagerInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        if (!(event.getInventory() instanceof MerchantInventory merchantInv)) return;
        if (!(merchantInv.getMerchant() instanceof Villager villager)) return;

        List<MerchantRecipe> recipes = villager.getRecipes();
        int currentCount = recipes.size();
        Integer alreadyDoubled = villager.getPersistentDataContainer()
                .get(RECIPES_DOUBLED_KEY, PersistentDataType.INTEGER);
        int processedCount = (alreadyDoubled != null) ? alreadyDoubled : 0;
        if (processedCount >= currentCount) return;

        List<MerchantRecipe> updated = new ArrayList<>(recipes);
        for (int i = processedCount; i < currentCount; i++) {
            updated.set(i, doubleRecipePrice(recipes.get(i)));
        }
        villager.setRecipes(updated);
        villager.getPersistentDataContainer().set(RECIPES_DOUBLED_KEY, PersistentDataType.INTEGER, currentCount);
    }

    // ── 11. Strength custom scaling: Strength I = 120%, Strength II = 140% ───

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStrengthDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        PotionEffect strength = player.getPotionEffect(PotionEffectType.STRENGTH);
        if (strength == null) return;

        AttributeInstance atk = player.getAttribute(Attribute.ATTACK_DAMAGE);
        if (atk == null) return;

        int amplifier     = strength.getAmplifier(); // 0 = Strength I, 1 = Strength II
        double weaponDmg  = atk.getValue();          // base + weapon/enchant modifiers, not Strength
        double vanillaBonus = 3.0 * (amplifier + 1);
        double denominator  = weaponDmg + vanillaBonus;
        if (denominator <= 0) return;

        double customMult = (amplifier == 0) ? 1.20 : 1.40;
        event.setDamage(event.getDamage() * (weaponDmg * customMult) / denominator);
    }

    // ── 12. Extra armor durability damage from Sharpness enchant ─────────────
    //  Sharpness 1-3: +1 extra durability per hit (×2 total)
    //  Sharpness 4+ : +2 extra durability per hit on diamond/netherite (×3 total)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorDurability(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player defender)) return;

        int sharpness = attacker.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharpness == 0) return;

        ItemStack[] armor = defender.getInventory().getArmorContents();
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece == null || piece.getType() == Material.AIR) continue;
            if (!(piece.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)) continue;

            int extra = (sharpness >= 4 && DIAMOND_NETHERITE_ARMOR.contains(piece.getType())) ? 2 : 1;
            int newDmg = damageable.getDamage() + extra;
            if (newDmg >= piece.getType().getMaxDurability()) {
                armor[i] = null;
            } else {
                damageable.setDamage(newDmg);
                piece.setItemMeta((ItemMeta) damageable);
            }
            changed = true;
        }
        if (changed) defender.getInventory().setArmorContents(armor);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasMending(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta.hasEnchant(Enchantment.MENDING)) return true;
        return meta instanceof EnchantmentStorageMeta esm && esm.hasStoredEnchant(Enchantment.MENDING);
    }

    private boolean removeMending(ItemStack item) {
        if (!hasMending(item)) return false;
        ItemMeta meta = item.getItemMeta();
        meta.removeEnchant(Enchantment.MENDING);
        if (meta instanceof EnchantmentStorageMeta esm) {
            esm.removeStoredEnchant(Enchantment.MENDING);
            if (esm.getStoredEnchants().isEmpty()) {
                item.setType(Material.BOOK);
            } else {
                item.setItemMeta(meta);
            }
        } else {
            item.setItemMeta(meta);
        }
        return true;
    }

    private boolean hasSweeping(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta.hasEnchant(Enchantment.SWEEPING_EDGE)) return true;
        return meta instanceof EnchantmentStorageMeta esm && esm.hasStoredEnchant(Enchantment.SWEEPING_EDGE);
    }

    private void removeSweeping(ItemStack item) {
        if (!hasSweeping(item)) return;
        ItemMeta meta = item.getItemMeta();
        meta.removeEnchant(Enchantment.SWEEPING_EDGE);
        if (meta instanceof EnchantmentStorageMeta esm) {
            esm.removeStoredEnchant(Enchantment.SWEEPING_EDGE);
        }
        item.setItemMeta(meta);
    }

    private boolean hasCurse(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta.hasEnchant(Enchantment.BINDING_CURSE) || meta.hasEnchant(Enchantment.VANISHING_CURSE)) return true;
        return meta instanceof EnchantmentStorageMeta esm
                && (esm.hasStoredEnchant(Enchantment.BINDING_CURSE) || esm.hasStoredEnchant(Enchantment.VANISHING_CURSE));
    }

    private void removeCurses(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        meta.removeEnchant(Enchantment.BINDING_CURSE);
        meta.removeEnchant(Enchantment.VANISHING_CURSE);
        if (meta instanceof EnchantmentStorageMeta esm) {
            esm.removeStoredEnchant(Enchantment.BINDING_CURSE);
            esm.removeStoredEnchant(Enchantment.VANISHING_CURSE);
            if (esm.getStoredEnchants().isEmpty()) {
                item.setType(Material.BOOK);
                return;
            }
        }
        item.setItemMeta(meta);
    }

    private boolean hasLunge(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Enchantment lunge = lungeEnchantment();
        if (lunge == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta.hasEnchant(lunge)) return true;
        return meta instanceof EnchantmentStorageMeta esm && esm.hasStoredEnchant(lunge);
    }

    private void removeLunge(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        Enchantment lunge = lungeEnchantment();
        if (lunge == null) return;
        ItemMeta meta = item.getItemMeta();
        meta.removeEnchant(lunge);
        if (meta instanceof EnchantmentStorageMeta esm) {
            esm.removeStoredEnchant(lunge);
            if (esm.getStoredEnchants().isEmpty()) {
                item.setType(Material.BOOK);
                return;
            }
        }
        item.setItemMeta(meta);
    }

    private boolean isInvisibilityPotion(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        if (type != Material.POTION && type != Material.SPLASH_POTION && type != Material.LINGERING_POTION) return false;
        if (!(item.getItemMeta() instanceof PotionMeta meta)) return false;
        if (meta.getBasePotionType() == PotionType.INVISIBILITY) return true;
        return meta.getCustomEffects().stream().anyMatch(e -> e.getType().equals(PotionEffectType.INVISIBILITY));
    }

    private boolean isAdminCreative(Player player) {
        if (player.getGameMode() != GameMode.CREATIVE) return false;
        if (player.isOp()) return true;
        org.bukkit.plugin.Plugin ap = org.bukkit.Bukkit.getPluginManager().getPlugin("AdminPlugin");
        if (!(ap instanceof AdminPlugin admin)) return false;
        return admin.getStaffManager().isAtLeast(player.getUniqueId(), StaffRank.ADMIN);
    }

    private MerchantRecipe doubleRecipePrice(MerchantRecipe old) {
        List<ItemStack> newIngredients = new ArrayList<>();
        for (ItemStack ing : old.getIngredients()) {
            if (ing == null) { newIngredients.add(null); continue; }
            ItemStack doubled = ing.clone();
            doubled.setAmount(Math.min(doubled.getAmount() * 2, doubled.getMaxStackSize()));
            newIngredients.add(doubled);
        }
        // specialPrice = 0 suppresses hero of the village and zombie cure discounts
        MerchantRecipe recipe = new MerchantRecipe(
                old.getResult().clone(),
                old.getUses(),
                old.getMaxUses(),
                old.hasExperienceReward(),
                old.getVillagerExperience(),
                old.getPriceMultiplier(),
                old.getDemand(),
                0);
        recipe.setIngredients(newIngredients);
        return recipe;
    }
}
