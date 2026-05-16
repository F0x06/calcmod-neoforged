package net.jsa2025.calcmod.commands.subcommands;


import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.jsa2025.calcmod.CalcMod;
import net.jsa2025.calcmod.commands.arguments.RecipeSuggestionProvider;
import net.jsa2025.calcmod.commands.CalcCommand;
import net.jsa2025.calcmod.utils.CalcMessageBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


public class Craft {
    static DecimalFormat df = new DecimalFormat("#.##");
    static NumberFormat nf = NumberFormat.getInstance(new Locale("en", "US"));

    private static final SimpleCommandExceptionType ERROR_UNKNOWN = new SimpleCommandExceptionType(Component.literal("Unknown recipe or item"));


    public static LiteralArgumentBuilder<CommandSourceStack> registerServer(LiteralArgumentBuilder<CommandSourceStack> command) {
        command
                .then(Commands.literal("craft").then(Commands.argument("item", ResourceLocationArgument.id()).suggests(new RecipeSuggestionProvider())
                                .then(Commands.literal("depth").then(Commands.argument("level", IntegerArgumentType.integer())
                                        .then(Commands.argument("amount", StringArgumentType.greedyString())
                                                .executes((ctx) -> {
                                                    RecipeHolder<?> recipe = resolveRecipe(ctx.getSource(), ResourceLocationArgument.getId(ctx, "item"));
                                                    CalcMessageBuilder message = execute(ctx.getSource().getEntity(), recipe, StringArgumentType.getString(ctx, "amount"), IntegerArgumentType.getInteger(ctx, "level"), ctx.getSource().registryAccess());
                                                    CalcCommand.sendMessageServer(ctx.getSource(), message);
                                                    return 1;
                                                })))
                                ).then(Commands.argument("amount", StringArgumentType.greedyString())
                                        .executes((ctx) -> {
                                            RecipeHolder<?> recipe = resolveRecipe(ctx.getSource(), ResourceLocationArgument.getId(ctx, "item"));
                                            CalcMessageBuilder message = execute(ctx.getSource().getEntity(), recipe, StringArgumentType.getString(ctx, "amount"), 1, ctx.getSource().registryAccess());
                                            CalcCommand.sendMessageServer(ctx.getSource(), message);
                                            return 1;
                                        })))
                        .then(Commands.literal("help").executes(ctx -> {
                            CalcMessageBuilder message = Help.execute("craft");
                            CalcCommand.sendMessageServer(ctx.getSource(), message);
                            return 1;
                        })));
        return command;
    }

    private static RecipeHolder<?> resolveRecipe(CommandSourceStack source, ResourceLocation id) throws CommandSyntaxException {
        var direct = source.getRecipeManager().byKey(id);
        if (direct.isPresent()) return direct.get();

        Item item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow(ERROR_UNKNOWN::create);
        RegistryAccess registryAccess = source.registryAccess();

        List<RecipeHolder<?>> matches = source.getRecipeManager().getRecipes().stream()
                .filter(rh -> rh.value() instanceof CraftingRecipe)
                .filter(rh -> {
                    ItemStack result = rh.value().getResultItem(registryAccess);
                    return !result.isEmpty() && result.getItem() == item;
                })
                .toList();
        if (matches.isEmpty()) throw ERROR_UNKNOWN.create();

        return matches.stream()
                .filter(rh -> rh.id().equals(id))
                .findFirst()
                .or(() -> matches.stream().filter(rh -> !rh.id().getPath().contains("_from_")).findFirst())
                .orElse(matches.get(0));
    }


    public static CalcMessageBuilder execute(Entity player, RecipeHolder<?> recipeHolder, String amount, int steps, RegistryAccess registryAccess) {
        if (!(recipeHolder.value() instanceof CraftingRecipe craftingRecipe)) {
            return new CalcMessageBuilder().addString("That recipe is not a crafting recipe.");
        }

        List<ItemStack> is = craftingRecipe.getIngredients().stream()
                .map(Craft::firstStackOrEmpty)
                .toList();

        ItemStack output = craftingRecipe.getResultItem(registryAccess);
        int outputSize = output.getCount();
        double inputAmount = Math.floor(CalcCommand.getParsedExpression(player, amount));
        int a = (int) Math.ceil(inputAmount / outputSize);

        HashMap<String, Map.Entry<ItemStack, Integer>> ingredients = getIngredients(
                player.getCommandSenderWorld().getServer().getRecipeManager(),
                registryAccess, is, a, steps);

        CalcMessageBuilder messageBuilder = new CalcMessageBuilder()
                .addFromArray(
                        new String[]{"Ingredients to craft ", "input", " ", "input", ": \n"},
                        new String[]{nf.format(inputAmount), processItemName(output.getDisplayName().getString())},
                        new String[]{});

        for (Map.Entry<String, Map.Entry<ItemStack, Integer>> entry : ingredients.entrySet()) {
            String key = entry.getKey();
            ItemStack value = entry.getValue().getKey();
            int stackSize = value.getMaxStackSize();
            double sb = Math.floor(entry.getValue().getValue() / (double) (stackSize * 27));
            String sbString = nf.format(sb);
            int remainder = entry.getValue().getValue() % (stackSize * 27);
            double stacks = Math.floor(remainder / (double) stackSize);
            String stacksString = nf.format(stacks);
            remainder = remainder % stackSize;
            String items = nf.format(remainder);
            if (sb > 0) {
                messageBuilder.addString(key + ": ");
                messageBuilder.addResult("SBs: " + sbString + ", Stacks: " + stacksString + ", Items: " + items + "\n");
            } else if (stacks > 0) {
                messageBuilder.addString(key + ": ");
                messageBuilder.addResult("Stacks: " + stacksString + ", Items: " + items + "\n");
            } else {
                messageBuilder.addString(key + ": ");
                messageBuilder.addResult("Items: " + items + "\n");
            }
        }

        return messageBuilder;
    }

    static HashMap<String, Map.Entry<ItemStack, Integer>> getIngredients(RecipeManager manager, RegistryAccess registryAccess, List<ItemStack> is, int amount_needed, int steps) {
        HashMap<String, Map.Entry<ItemStack, Integer>> ingredients = new HashMap<>();
        CalcMod.LOGGER.info("Step");
        for (ItemStack ingredient : is) {
            if (ingredient.getCount() > 0) {
                String name = processItemName(ingredient.getDisplayName().getString());
                if (ingredients.containsKey(name)) {
                    ingredients.put(name, Map.entry(ingredients.get(name).getKey(), ingredients.get(name).getValue() + amount_needed));
                } else {
                    ingredients.put(name, Map.entry(ingredient, amount_needed));
                }
            }
        }

        HashMap<String, Map.Entry<ItemStack, Integer>> ex_ingredients = new HashMap<>();
        CalcMod.LOGGER.info("Step1");

        for (Map.Entry<ItemStack, Integer> ingredient : ingredients.values()) {
            if (steps == 1) {
                return ingredients;
            }

            Optional<ResourceKey<Item>> ing_id = BuiltInRegistries.ITEM.getResourceKey(ingredient.getKey().getItem());
            if (ing_id.isEmpty()) {
                ex_ingredients.put(processItemName(ingredient.getKey().getDisplayName().getString()), Map.entry(ingredient.getKey(), ingredient.getValue()));
                continue;
            }

            CalcMod.LOGGER.info("Step1.5");
            try {
                final String ingPath = ing_id.get().location().getPath();
                final String preferredId = ingPath + "_from_" + ingPath.split("_")[0] + "_block";

                List<RecipeHolder<?>> matches = manager.getRecipes().stream()
                        .filter(rh -> rh.value() instanceof CraftingRecipe)
                        .filter(rh -> {
                            ItemStack result = rh.value().getResultItem(registryAccess);
                            return !result.isEmpty() && result.getItem() == ingredient.getKey().getItem();
                        })
                        .toList();

                Optional<RecipeHolder<?>> recipe = matches.stream()
                        .filter(rh -> rh.id().getPath().equals(preferredId))
                        .findFirst();
                if (recipe.isEmpty() && !matches.isEmpty()) {
                    recipe = Optional.of(matches.get(0));
                }

                if (recipe.isPresent()) {
                    CraftingRecipe craftingRecipe = (CraftingRecipe) recipe.get().value();
                    List<ItemStack> sis = craftingRecipe.getIngredients().stream()
                            .map(Craft::firstStackOrEmpty)
                            .toList();
                    CalcMod.LOGGER.info("Step3");

                    int outputCount = craftingRecipe.getResultItem(registryAccess).getCount();
                    HashMap<String, Map.Entry<ItemStack, Integer>> sub_ingredients = getIngredients(
                            manager, registryAccess, sis,
                            (int) Math.ceil((double) ingredient.getValue() / (double) outputCount),
                            steps - 1);

                    for (String item : sub_ingredients.keySet()) {
                        CalcMod.LOGGER.info(item);
                        if (ex_ingredients.containsKey(item)) {
                            ex_ingredients.put(item, Map.entry(ex_ingredients.get(item).getKey(), ex_ingredients.get(item).getValue() + sub_ingredients.get(item).getValue()));
                        } else {
                            ex_ingredients.put(item, Map.entry(sub_ingredients.get(item).getKey(), sub_ingredients.get(item).getValue()));
                        }
                    }
                } else {
                    ex_ingredients.put(processItemName(ingredient.getKey().getDisplayName().getString()), Map.entry(ingredient.getKey(), ingredient.getValue()));
                }
            } catch (Exception e) {
                e.printStackTrace();
                CalcMod.LOGGER.info(ing_id.get().location().getPath());
            }
        }

        return ex_ingredients;
    }

    private static ItemStack firstStackOrEmpty(net.minecraft.world.item.crafting.Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        return items.length > 0 ? items[0] : ItemStack.EMPTY;
    }

    private static String processItemName(String name) {
        if (name.length() > 1) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    public static String helpMessage = """
        §b§LCraft:§r§f
        Given an item and the quanity you want to craft of it, returns the amounts of the ingredients needed to craft the quantity of the item.
        §eUsage: /calc craft <item> <amount>§f
            """;

}
