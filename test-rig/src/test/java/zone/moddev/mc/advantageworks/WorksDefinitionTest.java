package zone.moddev.mc.advantageworks;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WorksDefinitionTest {
    @Test
    public void completeWorksCoversEveryRegisteredComponent() {
        WorksDefinition definition = WorksDefinitionLoader.load();
        assertEquals(21, definition.stations.size());

        Set<String> blocks = new HashSet<>();
        Set<String> items = new HashSet<>();
        Set<String> ids = new HashSet<>();
        StringBuilder manual = new StringBuilder();
        for (WorksDefinition.Station station : definition.stations) {
            assertTrue(ids.add(station.id));
            assertTrue(station.id, !station.assertions.isEmpty());
            assertTrue(station.id, !station.manualChecks.isEmpty());
            for (WorksDefinition.Placement placement : station.placements) blocks.add(placement.block);
            collectActions(station.setupActions, blocks, items);
            collectActions(station.startActions, blocks, items);
            collectActions(station.stopActions, blocks, items);
            for (WorksDefinition.Assertion assertion : station.assertions) {
                if (assertion.block != null) blocks.add(assertion.block);
                if (assertion.item != null && !"modLoaded".equals(assertion.type)) items.add(assertion.item);
            }
            for (String check : station.manualChecks) manual.append(check).append('\n');
        }

        assertContainsAll(blocks,
                "basepower:fluid_drain", "basepower:fluid_discharge",
                "basepower:fluid_storage_tank", "basepower:fluid_metal_tank",
                "basepower:still", "basepower:fluid_pipe",
                "basepower:fluid_pipe_terminal", "basepower:fluid_switch",
                "basepower:item_conveyor", "basepower:item_filter_block",
                "basepower:item_filter_food", "basepower:item_filter_fuel",
                "basepower:item_filter_inventory", "basepower:item_filter_ore",
                "basepower:item_filter_plant", "basepower:item_filter_smelt",
                "basepower:item_filter_overflow", "basepower:steel_frame",
                "basepower:infinite_steam", "basepower:infinite_electricity",
                "basepower:infinite_quantum", "basepower:crude_oil",
                "basepower:refined_oil", "basepower:converter_rf_steam",
                "basepower:converter_rf_electricity", "basepower:converter_rf_quantum",
                "basepower:converter_tr_electricity");
        assertContainsAll(blocks,
                "basesteam:steam_pipe", "basesteam:steam_track",
                "basesteam:steam_boiler_coal", "basesteam:steam_boiler_electric",
                "basesteam:steam_boiler_geothermal", "basesteam:steam_boiler_oil",
                "basesteam:steam_tank", "basesteam:steam_furnace",
                "basesteam:steam_crusher", "basesteam:steam_drill",
                "basesteam:steam_elevator", "basesteam:steam_switch",
                "basesteam:steam_still", "basesteam:steam_pump",
                "basesteam:drillbit", "basesteam:platform",
                "basesteam:pump_pipe_steam");
        assertContainsAll(blocks,
                "baseelectric:electric_conduit", "baseelectric:li_ore",
                "baseelectric:sulfur_ore", "baseelectric:electric_track",
                "baseelectric:laser_turret", "baseelectric:laser_turret_evil",
                "baseelectric:led_bar", "baseelectric:photovoltaic_generator",
                "baseelectric:hydroelectric_generator", "baseelectric:steam_powered_generator",
                "baseelectric:electric_furnace", "baseelectric:electric_battery_array",
                "baseelectric:electric_crusher", "baseelectric:electric_drill",
                "baseelectric:electric_fabricator", "baseelectric:growth_chamber",
                "baseelectric:growth_chamber_controller", "baseelectric:electric_oven",
                "baseelectric:electric_switch", "baseelectric:electric_pump",
                "baseelectric:electric_still", "baseelectric:plastic_refinery",
                "baseelectric:pump_pipe_electric");

        assertContainsAll(items,
                "basepower:starch", "basepower:bioplastic_ingot",
                "basepower:sprocket", "basepower:rotator_tool",
                "basesteam:steam_governor", "basesteam:steam_drill_bit",
                "basesteam:blackpowder_cartridge", "basesteam:musket",
                "baseelectric:blank_circuit_board", "baseelectric:control_circuit",
                "baseelectric:integrated_circuit", "baseelectric:psu",
                "baseelectric:silicon_ingot", "baseelectric:silicon_mix",
                "baseelectric:solder_mix", "baseelectric:solder",
                "baseelectric:lead_acid_battery", "baseelectric:nickel_hydride_battery",
                "baseelectric:alkaline_battery", "baseelectric:lithium_battery",
                "baseelectric:li_ingot", "baseelectric:li_powder",
                "baseelectric:li_nugget", "baseelectric:sulfur_powder",
                "baseelectric:petrolplastic_ingot");

        assertTrue(manual.toString().contains("SA-110-001"));
        assertTrue(manual.toString().contains("SA-110-002"));
        assertTrue(manual.toString().contains("SA-110-003"));
        assertTrue(manual.toString().contains("EA-110-001"));
        assertTrue(manual.toString().contains("EA-110-002"));
        assertTrue(manual.toString().contains("EA-110-003"));

        assertConditionalAssertion(definition, "W-03", "mineralogy-oil-input-consumed", "mineralogy");
        assertConditionalAssertion(definition, "W-03", "mineralogy-oil-output-created", "mineralogy");
        assertAssertion(definition, "S-01", "geothermal-temperature-persisted", "nbtNumber");
        assertAssertion(definition, "S-04", "crusher-one-item-signal", "comparatorOutput");
        assertAssertion(definition, "S-06", "pump-fluid-persisted", "nbtNumber");
        assertAssertion(definition, "E-01", "hydroelectric-active", "block");
        assertAssertion(definition, "E-01", "hydroelectric-battery-charged", "inventoryNbtNumber");
        assertAssertion(definition, "P-API-01", "api-tank-filled", "nbtNumber");

        Set<String> enclosedStations = new HashSet<>();
        for (WorksDefinition.Station station : definition.stations) {
            if (station.enclosed) enclosedStations.add(station.id);
        }
        assertEquals(new HashSet<>(Arrays.asList(
                "S-05", "S-06", "S-07", "S-08", "E-01", "E-06", "E-07")), enclosedStations);
    }

    private static void collectActions(Iterable<WorksDefinition.Action> actions,
                                       Set<String> blocks, Set<String> items) {
        for (WorksDefinition.Action action : actions) {
            if (action.block != null) blocks.add(action.block);
            if (action.item != null) items.add(action.item);
        }
    }

    private static void assertContainsAll(Set<String> actual, String... expected) {
        Set<String> missing = new HashSet<>(Arrays.asList(expected));
        missing.removeAll(actual);
        assertTrue("Missing component coverage: " + missing, missing.isEmpty());
    }

    private static void assertConditionalAssertion(WorksDefinition definition, String stationId,
                                                   String assertionId, String requiredMod) {
        for (WorksDefinition.Station station : definition.stations) {
            if (!stationId.equals(station.id)) continue;
            for (WorksDefinition.Assertion assertion : station.assertions) {
                if (assertionId.equals(assertion.id)) {
                    assertTrue(assertion.requiredMods.contains(requiredMod));
                    return;
                }
            }
        }
        throw new AssertionError("Missing conditional assertion " + stationId + "/" + assertionId);
    }
    private static void assertAssertion(WorksDefinition definition, String stationId,
                                        String assertionId, String assertionType) {
        for (WorksDefinition.Station station : definition.stations) {
            if (!stationId.equals(station.id)) continue;
            for (WorksDefinition.Assertion assertion : station.assertions) {
                if (assertionId.equals(assertion.id)) {
                    assertEquals(assertionType, assertion.type);
                    return;
                }
            }
        }
        throw new AssertionError("Missing assertion " + stationId + "/" + assertionId);
    }
}
