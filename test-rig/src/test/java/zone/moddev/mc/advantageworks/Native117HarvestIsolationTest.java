package zone.moddev.mc.advantageworks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EntityGetter;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native117HarvestIsolationTest {
    private static final AABB BOUNDS = new AABB(0, 0, 0, 1, 1, 1).inflate(2);
    private static net.minecraft.world.level.Level world;

    @BeforeClass public static void initializeNativeRegistries() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        world = Native117EntityContextTest.world(net.minecraft.world.level.Level.OVERWORLD);
    }

    private static ItemEntity item(Item type, int count) {
        return new ItemEntity(world, 0.5, 0.5, 0.5, new ItemStack(type, count));
    }

    @Test public void nativeDefaultQueryReproducesFourThenSixThenEightBeforeTickCleanup() {
        QueryReader reader = new QueryReader();
        ItemEntity first = item(Items.GUNPOWDER, 4);
        reader.index.add(first);
        assertEquals(4, count(reader.getEntitiesOfClass(ItemEntity.class, BOUNDS)));
        reader.getEntitiesOfClass(ItemEntity.class, BOUNDS).forEach(ItemEntity::discard);
        assertTrue(first.isRemoved());
        assertTrue(EntitySelector.NO_SPECTATORS.test(first));
        assertSame(first, reader.getEntitiesOfClass(ItemEntity.class, BOUNDS).get(0));
        reader.index.add(item(Items.GUNPOWDER, 2));
        assertEquals(6, count(reader.getEntitiesOfClass(ItemEntity.class, BOUNDS)));
        reader.getEntitiesOfClass(ItemEntity.class, BOUNDS).forEach(ItemEntity::discard);
        reader.index.add(item(Items.GUNPOWDER, 2));
        assertEquals(8, count(reader.getEntitiesOfClass(ItemEntity.class, BOUNDS)));
    }

    @Test public void removedEarlierDropCannotInflateTheNextTrial() {
        QueryReader reader = new QueryReader();
        reader.index.add(item(Items.GUNPOWDER, 4));
        NativeHarvestProbe.harvestDrops(reader, BOUNDS).forEach(ItemEntity::discard);
        assertTrue(NativeHarvestProbe.harvestDrops(reader, BOUNDS).isEmpty());
        reader.index.add(item(Items.GUNPOWDER, 2));
        assertEquals(2, count(NativeHarvestProbe.harvestDrops(reader, BOUNDS)));
    }

    @Test public void removedSulfurLikeDropCannotContaminateSilkOrWrongToolCases() {
        QueryReader reader = new QueryReader();
        ItemEntity powder = item(Items.GUNPOWDER, 4);
        reader.index.add(powder);
        powder.discard();
        assertTrue(NativeHarvestProbe.harvestDrops(reader, BOUNDS).isEmpty());
        ItemEntity ore = item(Items.IRON_ORE, 1);
        reader.index.add(ore);
        assertEquals(Collections.singletonList(ore), NativeHarvestProbe.harvestDrops(reader, BOUNDS));
    }

    @Test public void everyUnexpectedLiveIdentityAndQuantityRemainsVisibleToTheOracle() {
        QueryReader reader = new QueryReader();
        ItemEntity expected = item(Items.IRON_ORE, 1), unexpected = item(Items.GUNPOWDER, 5);
        reader.index.add(expected);
        reader.index.add(unexpected);
        assertEquals(reader.index, NativeHarvestProbe.harvestDrops(reader, BOUNDS));
        assertEquals(6, count(NativeHarvestProbe.harvestDrops(reader, BOUNDS)));
    }

    @Test public void fixtureQueryKeepsTheOriginalSpatialBoundary() {
        QueryReader reader = new QueryReader();
        ItemEntity outside = item(Items.GUNPOWDER, 4);
        outside.setPos(32, 0.5, 0.5);
        reader.index.add(outside);
        assertTrue(NativeHarvestProbe.harvestDrops(reader, BOUNDS).isEmpty());
    }

    private static int count(List<ItemEntity> entities) {
        return entities.stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    /** Models only Chunk's index and predicate dispatch; native entities/default queries are real. */
    private static final class QueryReader implements EntityGetter {
        final List<ItemEntity> index = new ArrayList<>();

        @Override public <T extends Entity> List<T> getEntities(net.minecraft.world.level.entity.EntityTypeTest<Entity, T> type,
                AABB bounds, Predicate<? super T> filter) {
            List<T> found = new ArrayList<>();
            for (ItemEntity entity : index) if (entity.getBoundingBox().intersects(bounds)) {
                T value = type.tryCast(entity);
                if (value != null && (filter == null || filter.test(value))) found.add(value);
            }
            return found;
        }

        @Override public List<Entity> getEntities(Entity except, AABB bounds, Predicate<? super Entity> filter) {
            throw new UnsupportedOperationException("Not used by harvest queries");
        }

        @Override public List<? extends Player> players() { return Collections.emptyList(); }
    }
}
