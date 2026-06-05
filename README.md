# BalancePlugin

Paper 1.21.4 plugin that tweaks a bunch of game balance stuff to make the server economy and combat less broken. Handles gold nerfs, axe damage, mending removal, strength pot scaling, armor durability, and a few other things.

Built for a private PvP server. Use/modify freely.

---

## What it does

**Mesa gold nerf**
Removes all gold ore above y=64 on chunk generation. In badlands biomes, also removes 80% of underground gold ore — brings it down to roughly 2× normal instead of the vanilla 10×. Stops players from flooding the economy by just living in a mesa.

**Axe damage nerf**
Axes in 1.9+ hit way too hard compared to swords. This scales them back:
- Normal hit: ~58% of base damage
- Crit hit: ~92% of base damage

Leaves axes slightly weaker than swords on normal hits, roughly equal on crits.

**Strength pot scaling**
Instead of the vanilla flat +3 damage per level, Strength now multiplies total damage:
- Strength I: ×1.20
- Strength II: ×1.40

Feels less punishing in a no-cooldown environment.

**Mending removed**
Mending doesn't exist — stripped from fishing loot, villager trades, loot chests, and enchanting tables. Gear should break eventually.

**Sweeping Edge and Lunge removed**
Both enchantments are blocked from being obtained through any normal means.

**Curse enchantments blocked**
Binding Curse and Vanishing Curse are silently stripped from any item that would get them — anvils, loot, trades.

**Villager/Piglin spawner block**
Can't place villager or zombified piglin spawner eggs, and those spawner types can't spawn naturally. Prevents villager trading exploits and piglin gold farms.

**Invisibility potions disabled**
Invis pots can't be drunk or splash-thrown by regular players. Admins in creative can still use them.

**End Crystal crafting disabled**
The recipe is blocked — can't craft them.

**Respawn Anchor explodes in overworld**
Placing a respawn anchor in the overworld blows it up. Discourages grief attempts.

**Dolphin's Grace and Hero of the Village disabled**
Both status effects are cancelled when applied. Hero of the Village in particular gives massive trade discounts which breaks the economy.

**Villager trade price doubling**
All villager trades have their ingredient costs doubled. Suppresses natural discounts (no hero of the village, no zombie cure discounts).

**Sharpness armor durability**
Sharpness enchants deal extra durability damage to the defender's armor:
- Sharpness 1–3: +1 extra durability per hit on all armor
- Sharpness 4+: +2 extra durability per hit on diamond/netherite

Makes high-sharpness swords actually wear down gear over time.

---

## Building

Requires Java 21+ and Maven. Also needs AdminPlugin on the compile classpath (soft dep at runtime).

```
mvn clean package
```

Jar ends up in `target/BalancePlugin-1.21.4.jar`.

---

## Dependencies

- **AdminPlugin** (soft) — used to check if a player is an admin in creative mode (bypasses some restrictions)

---

## Notes

- Axe crit detection uses fall distance + velocity check, same logic as vanilla
- Gold removal runs at chunk generation time — won't affect already-generated chunks
- Mending stripping on fishing only triggers when the fish item is actually caught (not on the cast)
- The villager trade doubling uses a PersistentDataContainer flag so it only runs once per trade, not every time the inventory opens
