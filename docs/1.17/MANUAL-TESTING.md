# Minecraft 1.17.1 Manual Check

Use the [test record](CURRENT-QUALIFICATION.md) for the exact candidate and the
limits of the automated checks. Manual acceptance is still open. Do not use the
only copy of an existing world.

## Open The Projects

Import these directories as existing Gradle projects in Eclipse:

```text
D:\PowerAdvantage\PA117\PowerAdvantage
D:\PowerAdvantage\PA117\SteamAdvantage\SteamAdvantage
D:\PowerAdvantage\PA117\ElectricAdvantage\ElectricAdvantage
```

Use a separate Eclipse workspace if an older port already uses these project
names. These are project directories, not pre-existing Eclipse workspaces.

Use Java 17 for Gradle and Java 16 for the project JRE and Minecraft launches.
After refreshing the Gradle projects, clean the Eclipse projects so their Java
and processed-resource output is current.

Power's `runClient.launch` loads Power alone. Its
`runClientAllAdvantage.launch` also loads the pinned Steam and Electric builds.
Prepare the latter with `gradlew.bat prepareCombinedEclipseLaunch` if needed.
Both use Power's `run` directory. Neither launch includes Advantage Works or
automatically opens a test world.

## Look And Interact

- Check the shared Power Advantage creative tab, names, tooltips and recipe-book
  pages for all three mods. Unlock
  recipes by collecting ingredients, not by granting every recipe at once.
- Open the machine menus at the GUI scales you normally use. Check titles,
  inventory labels, slot alignment, gauges, hover text and filled-fluid colours.
  Put items and fluids in the machine; an empty drawing is not a useful test of
  every gauge state.
- Place horizontal and vertical drills. Watch their moving parts and output,
  then save, close Minecraft completely and reopen the copied world. Check
  facing, pressure or power, inventory and continued work together.
- Inspect live turbines, elevator travel, lighting and turret presentation.
  Automated state and renderer checks do not replace watching these in a world.
- Try ordinary multiplayer interaction if that is part of the intended pack.
  Server-side player adapters do not prove a real client's death screen,
  reconnect experience or network presentation.

## Try An Existing World Copy

Keep an independent backup of the entire world, including player and dimension
data. Per-mod migration backups can contain intermediate data from another mod's
hook; they are not a substitute for that backup.

Only load a copy whose other mods have compatible target versions. Missing
unrelated mods are not an acceptable reason to lose their blocks or inventories.
This port's tests do not qualify the complete Sylvester modpack.

Before opening the copy, note a few working machines, their contents, tank
levels, battery charge and drill directions. Check them immediately after the
upgrade, let them work, then save and close the entire game before checking
again. Do not repair pipes or replace machines before recording a fault: that
would hide a restoration problem.

The strict dimension fixtures record one independently reproduced native change:
tamed-wolf health can become 20 instead of 8. That does not excuse any other
entity, inventory or machine-state change.

For a problem report, keep the log, screenshot, candidate versions and untouched
source copy. Note whether it happened on first import, during operation or only
after restarting. Do not save over the original world while investigating.
