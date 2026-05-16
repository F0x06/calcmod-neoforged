package net.jsa2025.calcmod.commands.arguments;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;

public class RecipeSuggestionProvider implements SuggestionProvider<CommandSourceStack> {

    @Override
    public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Stream<ResourceLocation> itemIds = context.getSource().getRecipeManager().getRecipes().stream()
                .filter(recipe -> recipe.value() instanceof CraftingRecipe)
                .map(recipe -> recipe.value().getResultItem(context.getSource().registryAccess()).getItem())
                .filter(item -> item != Items.AIR)
                .distinct()
                .map(item -> BuiltInRegistries.ITEM.getKey(item));
        return SharedSuggestionProvider.suggestResource(itemIds, builder);
    }

}
