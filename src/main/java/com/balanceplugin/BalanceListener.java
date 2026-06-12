package com.balanceplugin;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.*;

public class BalanceListener implements Listener {

    private static final NamespacedKey RECIPES_DOUBLED_KEY = new NamespacedKey("balanceplugin", "recipes_doubled");

    private final JavaPlugin plugin;
    // tracks players who just drank an instant damage potion so we can halve the magic damage hit
    private final Set<UUID> pendingInstantDmgHalf = new HashSet<>();

    private static final Set<EntityType> BLOCKED_SPAWNER_TYPES = EnumSet.of(
            EntityType.VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN
    );

    private static final Set<Material> BLOCKED_SPAWN_EGGS = EnumSet.of(
            Material.VILLAGER_SPAWN_EGG,
            Material.ZOMBIFIED_PIGLIN_SPAWN_EGG
    );

    private static final Map<Enchantment, Integer> MAX_ALLOWED_BOOK_LEVELS = Map.of(
            Enchantment.SHARPNESS, 1,
            Enchantment.PROTECTION, 1,
            Enchantment.UNBREAKING, 1,
            Enchantment.FORTUNE, 1,
            Enchantment.LOOTING, 1,
            Enchantment.EFFICIENCY, 3
    );

    public BalanceListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ── 1. 1.7-style enchant XP cost ─────────────────────────────────────────

    @EventHandler
    public void onEnchantApplyOldEnchantmentCost(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        int cost = event.getExpLevelCost();
        int vanillaCost = event.whichButton() + 1;
        int extraLevelCost = cost - vanillaCost;
        if (extraLevelCost <= 0) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.setLevel(Math.max(0, player.getLevel() - extraLevelCost));
        });
    }

    // ── 2. Villager profession lock and breeding nerf ─────────────────────────

    @EventHandler
    public void onVillagerCareerChange(VillagerCareerChangeEvent event) {
        if (event.getEntity().getProfession() != Villager.Profession.NONE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onVillagerBreed(EntityBreedEvent event) {
        if (event.getEntity() instanceof Villager) {
            if (Math.random() > 0.25d) event.setCancelled(true);
        }
    }

    @EventHandler
    public void onVillagerGainIllegalTrade(VillagerAcquireTradeEvent event) {
        if(isBannedEnchantmentBookTradeResult(event.getRecipe().getResult())) {
            event.setRecipe(createAllowedVillagerEnchantmentBookTradeRecipe(event.getRecipe()));
        }
    }

    private MerchantRecipe createAllowedVillagerEnchantmentBookTradeRecipe(MerchantRecipe originalRecipe) {
        ItemStack original = originalRecipe.getResult();
        Map<Enchantment, Integer> enchantmentIntegerMap = ((EnchantmentStorageMeta) original.getItemMeta()).getStoredEnchants();

        ItemStack output = new ItemStack(Material.ENCHANTED_BOOK);

        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) output.getItemMeta();

        for (Map.Entry<Enchantment, Integer> entry : enchantmentIntegerMap.entrySet()) {
            meta.addStoredEnchant(entry.getKey(), 1, false);
        }



        output.setItemMeta(meta);


        plugin.getLogger().info(
                "Old enchants: " + ((EnchantmentStorageMeta) original.getItemMeta()).getStoredEnchants().toString()
                + "\nNew enchants: " + ((EnchantmentStorageMeta) output.getItemMeta()).getStoredEnchants().toString()

        );

        MerchantRecipe newRecipe = new MerchantRecipe(output,
                originalRecipe.getUses(),
                originalRecipe.getMaxUses(),
                originalRecipe.hasExperienceReward(),
                originalRecipe.getVillagerExperience(),
                originalRecipe.getPriceMultiplier(),
                originalRecipe.shouldIgnoreDiscounts()
        );

        List<ItemStack> ingredients = new ArrayList<>();

        for (ItemStack ingredient : originalRecipe.getIngredients()) {
            ingredients.add(ingredient.clone());
        }

        newRecipe.setIngredients(ingredients);

        return newRecipe;
    }

    private boolean isBannedEnchantmentBookTradeResult(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() != Material.ENCHANTED_BOOK) {
            return false;
        }

        if (!(itemStack.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
            return false;
        }

        for (Map.Entry<Enchantment, Integer> entry : meta.getStoredEnchants().entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();

            Integer maxAllowedLevel = MAX_ALLOWED_BOOK_LEVELS.get(enchantment);

            if (maxAllowedLevel != null && level > maxAllowedLevel) {
                return true;
            }
        }

        return false;
    }



    @EventHandler
    public void onMinecartChestSpawn(LootGenerateEvent event) {
        Location loc = event.getEntity().getLocation();

        if ((Math.abs(loc.getX()) - 512 <= 0) && (Math.abs(loc.getZ()) - 512 <= 0)) {
            event.setLoot(List.of());
        }
    }
    // ── 3. Mob drop removals ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        event.getDrops().removeIf(item -> item != null && item.getType() == Material.TOTEM_OF_UNDYING);
        if (event.getEntity().getType() == EntityType.IRON_GOLEM) {
            event.getDrops().removeIf(item -> item != null && item.getType() == Material.IRON_INGOT);
        }
        if (event.getEntity().getType() == EntityType.ZOMBIFIED_PIGLIN) {
            event.getDrops().removeIf(item -> item != null
                    && (item.getType() == Material.GOLD_NUGGET || item.getType() == Material.GOLD_INGOT));
        }
    }

    // ── 4. Strip banned enchants from all generated loot (chests, structures) ─

    @EventHandler(priority = EventPriority.HIGH)
    public void onLootGenerate(LootGenerateEvent event) {
        for (ItemStack item : event.getLoot()) {
            if (item == null || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            boolean changed = false;
            for (Enchantment banned : new Enchantment[]{
                    Enchantment.MENDING,
                    Enchantment.SWEEPING_EDGE,
                    Enchantment.BINDING_CURSE,
                    Enchantment.VANISHING_CURSE
            }) {
                if (meta.hasEnchant(banned)) { meta.removeEnchant(banned); changed = true; }
            }
            if (changed) item.setItemMeta(meta);
        }
    }

    // ── 5. Block villager and zombie piglin spawners ──────────────────────────

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
        if (BLOCKED_SPAWNER_TYPES.contains(event.getEntityType())) event.setCancelled(true);
    }

    // ── 6. Dolphin's Grace and Hero of the Village disabled ───────────────────

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

    // ── 7. Villager trade price doubling (no discounts) ──────────────────────

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

    // ── 8. Instant Damage potion nerf — both drinkable and splash do 50% ─────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInstantDamageSplash(PotionSplashEvent event) {
        if (!isInstantDamagePotion(event.getPotion().getItem())) return;
        for (LivingEntity entity : event.getAffectedEntities()) {
            event.setIntensity(entity, event.getIntensity(entity) * 0.5);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrinkInstantDamage(PlayerItemConsumeEvent event) {
        if (!isInstantDamagePotion(event.getItem())) return;
        pendingInstantDmgHalf.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInstantDamageMagicHit(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.MAGIC) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!pendingInstantDmgHalf.remove(player.getUniqueId())) return;
        event.setDamage(event.getDamage() * 0.5);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isInstantDamagePotion(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        if (type != Material.POTION && type != Material.SPLASH_POTION) return false;
        if (!(item.getItemMeta() instanceof PotionMeta meta)) return false;
        PotionType base = meta.getBasePotionType();
        if (base == PotionType.HARMING || base == PotionType.STRONG_HARMING) return true;
        return meta.getCustomEffects().stream().anyMatch(e -> e.getType().equals(PotionEffectType.INSTANT_DAMAGE));
    }

    private MerchantRecipe doubleRecipePrice(MerchantRecipe old) {
        List<ItemStack> newIngredients = new ArrayList<>();
        for (ItemStack ing : old.getIngredients()) {
            if (ing == null) { newIngredients.add(null); continue; }
            ItemStack doubled = ing.clone();
            doubled.setAmount(Math.min(doubled.getAmount() * 2, doubled.getMaxStackSize()));
            newIngredients.add(doubled);
        }
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
