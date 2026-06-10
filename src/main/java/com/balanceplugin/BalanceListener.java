package com.balanceplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class BalanceListener implements Listener {

    private static final NamespacedKey RECIPES_DOUBLED_KEY = new NamespacedKey("balanceplugin", "recipes_doubled");

    private final JavaPlugin plugin;

    private static final Set<EntityType> BLOCKED_SPAWNER_TYPES = EnumSet.of(
            EntityType.VILLAGER,
            EntityType.ZOMBIFIED_PIGLIN
    );

    private static final Set<Material> BLOCKED_SPAWN_EGGS = EnumSet.of(
            Material.VILLAGER_SPAWN_EGG,
            Material.ZOMBIFIED_PIGLIN_SPAWN_EGG
    );

    public BalanceListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEnchantApplyOldEnchantmentCost(EnchantItemEvent event) {
        Player player = event.getEnchanter();

        int cost = event.getExpLevelCost();
        int vanillaCost = event.whichButton() + 1;
        int extraLevelCost = cost - vanillaCost;


        if (extraLevelCost <= 0) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;

            int newLevel = Math.max(0, player.getLevel() - extraLevelCost);
            player.setLevel(newLevel);

        });

    }

    @EventHandler
    public void onVillagerCareerChange(VillagerCareerChangeEvent event) {
        Villager villager = event.getEntity();

        if (villager.getProfession() != Villager.Profession.NONE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onVillagerBreed(EntityBreedEvent event) {
        if (event.getEntity() instanceof Villager) {
            if (Math.random() > 0.25d) {
                event.setCancelled(true);
            }
        }
    }


    // block iron, gold, and totem drops
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
