package zone.moddev.mc.basepower.recipe.condition;

import com.google.gson.JsonObject;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.RecipeMode;
import net.minecraft.util.JsonUtils;
import net.minecraftforge.common.crafting.IConditionFactory;
import net.minecraftforge.common.crafting.JsonContext;

import java.util.Locale;
import java.util.function.BooleanSupplier;

public class NotRecipeModeConditionFactory implements IConditionFactory {
	@Override
	public BooleanSupplier parse(JsonContext context, JsonObject json) {
		RecipeMode mode = RecipeMode.valueOf(JsonUtils.getString(json, "mode").toUpperCase(Locale.US));
		return () -> PowerAdvantage.recipeMode != mode;
	}
}
