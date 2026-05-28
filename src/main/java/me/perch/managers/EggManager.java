package me.perch.manager;

import me.perch.Eggs;
import me.perch.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.bukkit.inventory.MerchantRecipe;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class EggManager {

    private final Eggs plugin;
    private final NamespacedKey keyIsPerchEgg;
    private final NamespacedKey keyUses;
    private final NamespacedKey keyMaxUses;
    private final NamespacedKey keyOriginalName;
    private final NamespacedKey keyProjectileUses;
    private final NamespacedKey keyPassengerEgg;

    public EggManager(Eggs plugin) {
        this.plugin = plugin;
        this.keyIsPerchEgg = new NamespacedKey(plugin, "is_perch_egg");
        this.keyUses = new NamespacedKey(plugin, "uses");
        this.keyMaxUses = new NamespacedKey(plugin, "max_uses");
        this.keyOriginalName = new NamespacedKey(plugin, "original_name");
        this.keyProjectileUses = new NamespacedKey(plugin, "projectile_uses");
        this.keyPassengerEgg = new NamespacedKey(plugin, "passenger_egg");
    }

    public NamespacedKey getKeyUses() { return keyUses; }
    public NamespacedKey getKeyOriginalName() { return keyOriginalName; }
    public NamespacedKey getKeyProjectileUses() { return keyProjectileUses; }
    public NamespacedKey getKeyPassengerEgg() { return keyPassengerEgg; }

    public boolean isPerchEgg(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keyIsPerchEgg, PersistentDataType.BOOLEAN);
    }

    public ItemStack createPerchEgg(int uses) {
        ConfigManager cm = plugin.getConfigManager();

        Material mat = cm.getEggMaterial();
        if (mat == null) mat = Material.EGG;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) return item;

        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (NoSuchMethodError e) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        try {
            meta.setMaxStackSize(64);
        } catch (NoSuchMethodError | Exception ignored) {
        }

        meta.getPersistentDataContainer().set(keyIsPerchEgg, PersistentDataType.BOOLEAN, true);
        meta.getPersistentDataContainer().set(keyUses, PersistentDataType.INTEGER, uses);
        meta.getPersistentDataContainer().set(keyMaxUses, PersistentDataType.INTEGER, uses);

        updateEggVisuals(meta, uses);

        item.setItemMeta(meta);
        return item;
    }

    public void updateEggVisuals(ItemMeta meta, int uses) {
        if (meta == null) return;
        ConfigManager cm = plugin.getConfigManager();

        String rawName = cm.getEggNameRaw();
        String processedName = rawName.replace("%uses%", String.valueOf(uses));

        meta.displayName(cm.parse(processedName).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        for (String line : cm.getEggLoreTemplate()) {
            String processedLine = line.replace("%uses%", String.valueOf(uses));
            lore.add(cm.parse(processedLine).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
    }

    public ItemStack createFilledSpawnEggWithPassenger(LivingEntity vehicle, LivingEntity passenger) {
        ItemStack vehicleEgg = createFilledSpawnEgg(vehicle);
        if (vehicleEgg == null) return null;

        ItemStack passengerEgg = createFilledSpawnEgg(passenger);
        if (passengerEgg == null) return null;

        ItemMeta meta = vehicleEgg.getItemMeta();
        if (!(meta instanceof SpawnEggMeta spawnEggMeta)) return vehicleEgg;

        try {
            byte[] passengerEggBytes = serializeItemStack(passengerEgg);
            spawnEggMeta.getPersistentDataContainer().set(keyPassengerEgg, PersistentDataType.BYTE_ARRAY, passengerEggBytes);

            List<Component> lore = spawnEggMeta.lore();
            if (lore == null) lore = new ArrayList<>();

            String passengerName = formatKey(passenger.getType().name());

            if (passenger instanceof Ageable ageable && !ageable.isAdult()) {
                passengerName += " (Baby)";
            }

            Component passengerLine = plugin.getConfigManager()
                    .parse("<white>Passenger: <#FFFF3A>" + passengerName)
                    .decoration(TextDecoration.ITALIC, false);

            if (lore.size() >= 1) {
                lore.add(1, passengerLine);
            } else {
                lore.add(passengerLine);
            }

            spawnEggMeta.lore(lore);
            vehicleEgg.setItemMeta(spawnEggMeta);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return vehicleEgg;
    }

    public ItemStack getPassengerEgg(ItemMeta meta) {
        if (meta == null) return null;
        if (!meta.getPersistentDataContainer().has(keyPassengerEgg, PersistentDataType.BYTE_ARRAY)) return null;

        byte[] bytes = meta.getPersistentDataContainer().get(keyPassengerEgg, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return null;

        try {
            return deserializeItemStack(bytes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] serializeItemStack(ItemStack item) throws Exception {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(byteOut)) {
            out.writeObject(item);
        }
        return byteOut.toByteArray();
    }

    private ItemStack deserializeItemStack(byte[] bytes) throws Exception {
        ByteArrayInputStream byteIn = new ByteArrayInputStream(bytes);
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(byteIn)) {
            return (ItemStack) in.readObject();
        }
    }

    public ItemStack createFilledSpawnEgg(LivingEntity entity) {
        ConfigManager cm = plugin.getConfigManager();

        String matName = entity.getType().name() + "_SPAWN_EGG";
        Material eggMat = Material.getMaterial(matName);
        if (eggMat == null) {
            if (entity.getType().name().equals("MUSHROOM_COW")) eggMat = Material.MOOSHROOM_SPAWN_EGG;
            else return null;
        }

        ItemStack stack = new ItemStack(eggMat);
        SpawnEggMeta meta = (SpawnEggMeta) stack.getItemMeta();
        if (meta == null) return null;

        EntitySnapshot snapshot = entity.createSnapshot();
        meta.setSpawnedEntity(snapshot);

        String typeName = formatKey(entity.getType().name());
        if (entity instanceof Ageable ageable && !ageable.isAdult()) {
            typeName += " (Baby)";
        }

        String simpleName = getDisplayEntityName(entity);

        Component nameComponent = Component.text(simpleName);
        String formatString = cm.getFilledEggNameFormat();
        formatString = ColorUtil.convertLegacyToMiniMessage(formatString);
        formatString = formatString.replace("%name%", "<name>");

        Component finalDisplayName = MiniMessage.miniMessage().deserialize(
                formatString,
                Placeholder.component("name", nameComponent)
        );
        finalDisplayName = finalDisplayName.decoration(TextDecoration.ITALIC, false);
        meta.displayName(finalDisplayName);

        meta.getPersistentDataContainer().set(keyIsPerchEgg, PersistentDataType.BOOLEAN, true);
        String serializedName = MiniMessage.miniMessage().serialize(finalDisplayName);
        meta.getPersistentDataContainer().set(keyOriginalName, PersistentDataType.STRING, serializedName);

        List<Component> finalLore = new ArrayList<>();
        String headerFormat = cm.getFilledEggHeaderFormat();
        headerFormat = ColorUtil.convertLegacyToMiniMessage(headerFormat);
        headerFormat = headerFormat.replace("%name%", "<name>");

        finalLore.add(MiniMessage.miniMessage().deserialize(headerFormat, Placeholder.component("name", Component.text(typeName)))
                .decoration(TextDecoration.ITALIC, false));

        List<StatEntry> stats = new ArrayList<>();

        double currentHealth = Math.round(entity.getHealth());
        double maxHealth = 0;
        var maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) maxHealth = Math.round(maxHealthAttr.getValue());

        String hpString;
        if (maxHealth > 20) {
            hpString = (int)currentHealth + "/" + (int)maxHealth;
        } else {
            String hearts = getHearts(currentHealth, maxHealth);
            hpString = hearts + " <gray>(" + (int)currentHealth + "/" + (int)maxHealth + ")";
        }
        addStat(stats, "Health", hpString, false);

        if (entity instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() != null) {
            String ownerName = tameable.getOwner().getName();
            if (ownerName != null) addStat(stats, "Owner", ownerName, false);
        }

        if (entity instanceof Steerable steerable && !(entity instanceof AbstractHorse)) {
            addStat(stats, "Saddle", (steerable.hasSaddle() ? "Yes" : "No"), false);
        }

        if (entity.getEquipment() != null) {
            ItemStack chest = entity.getEquipment().getItem(EquipmentSlot.CHEST);
            if (chest != null && chest.getType() != Material.AIR) {
                addStat(stats, "Armor", formatKey(chest.getType().name()), true);
            }
        }

        if (entity instanceof IronGolem ironGolem) addStat(stats, "Built By", (ironGolem.isPlayerCreated() ? "Player" : "Village"), false);
        if (entity instanceof Snowman snowman) addStat(stats, "Pumpkin", (snowman.isDerp() ? "Yes" : "No"), false);

        if (entity instanceof Bee bee) {
            addStat(stats, "Nectar", (bee.hasNectar() ? "Yes" : "No"), false);
            if (bee.getAnger() > 0) addStat(stats, "Mood", "<red>Angry", false);
        }

        if (entity instanceof Slime slime) addStat(stats, "Size", String.valueOf(slime.getSize()), false);
        if (entity instanceof Fox fox) addStat(stats, "Type", formatKey(fox.getFoxType().name()), true);
        if (entity instanceof Rabbit rabbit) addStat(stats, "Type", formatKey(rabbit.getRabbitType().name()), true);
        if (entity instanceof Creeper creeper) addStat(stats, "Charged", (creeper.isPowered() ? "Yes" : "No"), false);

        if (entity instanceof AbstractNautilus abstractnautilus) {
            ItemStack armor = abstractnautilus.getInventory().getArmor();
            if (armor != null && armor.getType() != Material.AIR) {
                addStat(stats, "Armor", formatKey(armor.getType().name().replace("_NAUTILUS_ARMOR", "")), true);
            }
            boolean hasSaddle = abstractnautilus.getInventory().getSaddle() != null;
            addStat(stats, "Saddle", (hasSaddle ? "Yes" : "No"), false);
        }

        if (entity instanceof ZombieNautilus zombienautilus) addStat(stats, "Variant", formatKey(zombienautilus.getVariant()), true);

        if (entity instanceof Pig pig) try { addStat(stats, "Variant", formatKey(pig.getVariant()), true); } catch (NoSuchMethodError ignored) {}
        if (entity instanceof Cow cow && !(cow instanceof MushroomCow)) try { addStat(stats, "Variant", formatKey(cow.getVariant()), true); } catch (NoSuchMethodError ignored) {}
        if (entity instanceof Chicken chicken) try { addStat(stats, "Variant", formatKey(chicken.getVariant()), true); } catch (NoSuchMethodError ignored) {}

        if (entity instanceof AbstractHorse abstractHorse) {
            boolean isLlamaOrCamel = (abstractHorse instanceof Llama) || (abstractHorse instanceof Camel);

            if (!isLlamaOrCamel) {
                double speed = abstractHorse.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
                addStat(stats, "Speed", String.format("%.2f", speed * 43) + " Blocks/s", false);

                double jumpStrength = abstractHorse.getJumpStrength();
                double jumpBlocks = toBlockHeight(jumpStrength);
                addStat(stats, "Jump", String.format("%.2f", jumpBlocks) + " Blocks", false);

                boolean hasSaddle = abstractHorse.getInventory().getSaddle() != null;
                addStat(stats, "Saddle", (hasSaddle ? "Yes" : "No"), false);
            }

            if (entity instanceof Horse specificHorse) {
                String style = formatKey(specificHorse.getStyle().name());
                String color = formatKey(specificHorse.getColor().name());
                addStat(stats, "Style", color + ", " + style, true);

                ItemStack armor = specificHorse.getInventory().getArmor();
                if (armor != null && armor.getType() != Material.AIR) {
                    addStat(stats, "Armor", formatKey(armor.getType().name().replace("_HORSE_ARMOR", "")), true);
                }
            }

            if (entity instanceof ChestedHorse chested && chested.isCarryingChest()) {
                addStat(stats, "Chest", "Yes", false);
            }
        }

        if (entity instanceof Panda panda) {
            addStat(stats, "Main Gene", formatKey(panda.getMainGene().name()), true);
            addStat(stats, "Hidden Gene", formatKey(panda.getHiddenGene().name()), true);
        }

        if (entity instanceof Wolf wolf) {
            addStat(stats, "Type", formatKey(wolf.getVariant()), true);
            try {
                Object soundVariant = wolf.getClass().getMethod("getSoundVariant").invoke(wolf);
                addStat(stats, "Personality", formatKey(soundVariant), false);
            } catch (Exception ignored) {}
            if (wolf.isTamed()) addStat(stats, "Collar", formatKey(wolf.getCollarColor().name()), true);

            try {
                if (wolf.getEquipment() != null) {
                    ItemStack bodyArmor = wolf.getEquipment().getItem(EquipmentSlot.BODY);
                    if (bodyArmor != null && bodyArmor.getType() != Material.AIR) {
                        addStat(stats, "Armor", formatKey(bodyArmor.getType().name().replace("_WOLF_ARMOR", "")), true);
                    }
                }
            } catch (NoSuchMethodError ignored) {}
        }

        if (entity instanceof Cat cat) {
            addStat(stats, "Type", formatKey(cat.getCatType()), true);
            if (cat.isTamed()) addStat(stats, "Collar", formatKey(cat.getCollarColor().name()), true);
        }

        if (entity instanceof Sheep sheep) {
            addStat(stats, "Color", formatKey(sheep.getColor().name()), true);
            addStat(stats, "Sheared", sheep.isSheared() ? "Yes" : "No", false);
        }
        if (entity instanceof Parrot parrot) addStat(stats, "Variant", formatKey(parrot.getVariant().name()), true);
        if (entity instanceof Axolotl axolotl) addStat(stats, "Variant", formatKey(axolotl.getVariant().name()), true);
        if (entity instanceof MushroomCow mooshroom) addStat(stats, "Variant", formatKey(mooshroom.getVariant().name()), true);
        if (entity instanceof Frog frog) addStat(stats, "Variant", formatKey(frog.getVariant()), true);

        if (entity instanceof Llama llama) {
            addStat(stats, "Strength", String.valueOf(llama.getStrength()), false);
            addStat(stats, "Color", formatKey(llama.getColor().name()), true);
            if (llama.getInventory().getDecor() != null) {
                addStat(stats, "Decor", formatKey(llama.getInventory().getDecor().getType().name()), true);
            }
        }

        if (entity instanceof TropicalFish fish) {
            String body = formatKey(fish.getBodyColor().name());
            String pattern = formatKey(fish.getPatternColor().name());
            String colorStr = guessColor(body) + body + " <gray>& " + guessColor(pattern) + pattern;
            String rawFormat = plugin.getConfigManager().getFilledEggStatFormat();
            rawFormat = rawFormat.replace("%label%", "Colors").replace("%value%", colorStr);
            Component c = plugin.getConfigManager().parse(rawFormat).decoration(TextDecoration.ITALIC, false);
            stats.add(new StatEntry("Colors", c));
            addStat(stats, "Pattern", formatKey(fish.getPattern().name()), false);
        }

        if (entity instanceof Villager villager) {
            addStat(stats, "Type", formatKey(villager.getVillagerType()), true);
            addStat(stats, "Profession", formatKey(villager.getProfession()), false);
            addStat(stats, "Level", String.valueOf(villager.getVillagerLevel()), false);
        }

        if (entity instanceof ZombieVillager zombieVillager) {
            addStat(stats, "Type", formatKey(zombieVillager.getVillagerType()), true);
            addStat(stats, "Profession", formatKey(zombieVillager.getVillagerProfession()), false);
        }

        if (entity instanceof Goat goat && goat.isScreaming()) addStat(stats, "Type", "Screaming", false);

        stats.sort((entry1, entry2) -> {
            String text1 = PlainTextComponentSerializer.plainText().serialize(entry1.component());
            String text2 = PlainTextComponentSerializer.plainText().serialize(entry2.component());
            return Integer.compare(text2.length(), text1.length());
        });

        for (StatEntry entry : stats) finalLore.add(entry.component());

        if (entity instanceof Villager villager) {

            List<MerchantRecipe> recipes = villager.getRecipes();

            if (recipes != null && !recipes.isEmpty()) {

                finalLore.add(
                        plugin.getConfigManager()
                                .parse("<white>Trades:")
                                .decoration(TextDecoration.ITALIC, false)
                );

                int shownTrades = 0;

                for (MerchantRecipe recipe : recipes) {

                    if (shownTrades >= 20) break;

                    ItemStack result = recipe.getResult();

                    if (result == null || result.getType() == Material.AIR) continue;

                    String tradeName;

                    if (result.getType() == Material.ENCHANTED_BOOK
                            && result.getItemMeta() instanceof EnchantmentStorageMeta enchantmeta
                            && !enchantmeta.getStoredEnchants().isEmpty()) {

                        Enchantment enchant = enchantmeta.getStoredEnchants().keySet().iterator().next();
                        int level = enchantmeta.getStoredEnchants().get(enchant);

                        String enchantName = formatKey(
                                enchant.getKey().getKey()
                                        .replace("_", " ")
                        );

                        tradeName = enchantName + " " + toRoman(level);

                    } else {

                        tradeName = formatKey(result.getType().name());

                        if (result.getAmount() > 1) {
                            tradeName = result.getAmount() + "x " + tradeName;
                        }
                    }

                    finalLore.add(
                            plugin.getConfigManager()
                                    .parse("  <#FFFF3A>" + tradeName)
                                    .decoration(TextDecoration.ITALIC, false)
                    );

                    shownTrades++;
                }

                if (recipes.size() > shownTrades) {
                    finalLore.add(
                            plugin.getConfigManager()
                                    .parse("<gray>+" + (recipes.size() - shownTrades) + " more...")
                                    .decoration(TextDecoration.ITALIC, false)
                    );
                }
            }
        }
        meta.lore(finalLore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String getDisplayEntityName(LivingEntity entity) {
        String simpleName;
        if (entity.customName() != null) {
            simpleName = PlainTextComponentSerializer.plainText().serialize(entity.customName());
        } else {
            simpleName = formatKey(entity.getType().name());
            if (entity instanceof Ageable ageable && !ageable.isAdult()) simpleName += " (Baby)";
        }
        return simpleName;
    }

    private String getHearts(double current, double max) {
        StringBuilder hearts = new StringBuilder("<red>");
        int displayMax = (int)Math.ceil(max / 2.0);
        int currentHearts = (int)Math.ceil(current / 2.0);

        if (currentHearts > displayMax) currentHearts = displayMax;

        for (int i = 0; i < displayMax; i++) {
            if (i < currentHearts) hearts.append("❤");
            else hearts.append("<gray>❤</gray><red>");
        }
        return hearts.toString();
    }

    private void addStat(List<StatEntry> list, String label, String value, boolean useColorLogic) {
        String colorPrefix = plugin.getConfigManager().getFilledEggDefaultColor();
        if (useColorLogic) colorPrefix = guessColor(value);
        String rawFormat = plugin.getConfigManager().getFilledEggStatFormat();
        rawFormat = rawFormat.replace("%label%", label).replace("%value%", colorPrefix + value);
        Component c = plugin.getConfigManager().parse(rawFormat).decoration(TextDecoration.ITALIC, false);
        list.add(new StatEntry(label, c));
    }

    private String guessColor(String text) {
        String upper = text.toUpperCase();

        if (upper.contains("TEMPERATE")) return "<#DA8648>";
        if (upper.contains("WARM")) return "<#EFEFEF>";
        if (upper.contains("COLD")) return "<#4D7A47>";

        if (upper.contains("DESERT")) return "<#E3BC7A>";
        if (upper.contains("SAVANNA")) return "<#E36E34>";
        if (upper.contains("SNOW")) return "<#F0F8FF>";
        if (upper.contains("TAIGA")) return "<#587056>";
        if (upper.contains("JUNGLE")) return "<#466D38>";
        if (upper.contains("SWAMP")) return "<#5D4939>";
        if (upper.contains("PLAINS")) return "<#91BD59>";

        if (upper.contains("WHITE")) return "<white>";
        if (upper.contains("ORANGE")) return "<gold>";
        if (upper.contains("MAGENTA")) return "<light_purple>";
        if (upper.contains("LIGHT BLUE")) return "<aqua>";
        if (upper.contains("YELLOW")) return "<yellow>";
        if (upper.contains("LIME")) return "<green>";
        if (upper.contains("PINK")) return "<light_purple>";
        if (upper.contains("GRAY") || upper.contains("GREY")) return "<gray>";
        if (upper.contains("CYAN")) return "<dark_aqua>";
        if (upper.contains("PURPLE")) return "<dark_purple>";
        if (upper.contains("BLUE")) return "<blue>";
        if (upper.contains("BROWN")) return "<#8B4513>";
        if (upper.contains("GREEN")) return "<dark_green>";
        if (upper.contains("RED")) return "<red>";
        if (upper.contains("BLACK")) return "<gray>";

        if (upper.contains("CREAMY")) return "<#F0E68C>";
        if (upper.contains("CHESTNUT")) return "<#3F1806>";
        if (upper.contains("DARK BROWN")) return "<#4B3621>";
        if (upper.contains("GOLD")) return "<gold>";

        if (upper.contains("IRON")) return "<gray>";
        if (upper.contains("DIAMOND")) return "<aqua>";
        if (upper.contains("NETHERITE")) return "<#3F3F3F>";

        return plugin.getConfigManager().getFilledEggDefaultColor();
    }

    private String toRoman(int number) {
        return switch (number) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(number);
        };
    }

    private double toBlockHeight(double x) {
        return -0.1817584952 * Math.pow(x, 3) + 3.689713992 * Math.pow(x, 2) + 2.128599134 * x - 0.343930367;
    }

    private String formatKey(Object input) {
        if (input == null) return "Unknown";
        String key;
        if (input instanceof Keyed keyed) key = keyed.getKey().getKey();
        else key = input.toString();

        key = key.replace("_", " ").toLowerCase();

        StringBuilder result = new StringBuilder();
        String[] words = key.split(" ");
        for (String word : words) {
            if (word.isEmpty()) continue;
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        }
        return result.toString().trim();
    }

    private record StatEntry(String label, Component component) {}
}
