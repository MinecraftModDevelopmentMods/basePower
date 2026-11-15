param([string]$RepositoryRoot = (Join-Path $PSScriptRoot '../..'), [string]$TargetRoot = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$TargetRoot = (Resolve-Path -LiteralPath $TargetRoot).Path
$baseline = '8d9937b3c0c9ef28a231b90fc2ff6adfea1af733'
$relative = 'java/zone/moddev/mc/advantageworks/client'
$hashes = [ordered]@{
 ClientResourceProbe='C8FDC4A003FE4749C8DC8D882CF1BD5B1E56F2BD235D83531F2B6D651037E4ED'
 TankVisualProbe='2725D39B037F73347C9CFBAB5A0621A004754A3BDB1B6B66CD7CF9DEB2F66A75'
 OilRenderProbe='7D444AD5B6A2B47BAB208807AB57BE2B1740E8014D1A0D299497002782316AA1'
 RecipeBookGroupingProbe='A4ED7890B461612FDECD415DB53C45FF2557B379CFFF0BFCB02A7C4022618AFF'
 MachineGuiLayoutProbe='5A034F4A09AE1FC5CFD6BBB6AB27088BE2317CE64C6783D5B409A130E3EB6096'
 ChildRendererGeometryProbe='389DF44FFDF8AE897849D5492A1818C2710E7EDB9F1C5ACA47518E7D420E1C1C'
 NativeGuiProjection='8B256AAFD3925DF43FC91B9117766F2119381C1CA09739AD85CD488E63230604'
}
function Require([bool]$Valid, [string]$Message) { if (!$Valid) { throw $Message } }
function Read-Baseline([string]$Path, [string]$Hash) {
 $info=[Diagnostics.ProcessStartInfo]::new('git', "-C `"$RepositoryRoot`" show ${baseline}:test-rig/client-resources/$Path")
 $info.UseShellExecute=$false; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
 $process=[Diagnostics.Process]::Start($info)
 try {
  $source=$process.StandardOutput.ReadToEnd(); $errorText=$process.StandardError.ReadToEnd(); $process.WaitForExit()
  Require ($process.ExitCode -eq 0) "Cannot read baseline: $errorText"
  $actual=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($source)))
  Require ($actual -ceq $Hash) "Reference SHA-256 mismatch: $Path"
  return $source
 } finally { $process.Dispose() }
}
function Canonical([string]$Source) {
 $Source=[regex]::Replace($Source,'(?m)^import [^\r\n]+\r?\n','')
 [regex]::Replace($Source,'"(?:\\.|[^"\\])*"|''(?:\\.|[^''\\])*''|/\*[\s\S]*?\*/|//[^\r\n]*|\s+',{
  param($m)
  if($m.Value.StartsWith('"') -or $m.Value.StartsWith("'")) { $m.Value } else { '' }
 })
}
function Adapt([string]$Source,[string]$Before,[string]$After) {
 $beforeKey=Canonical $Before
 Require ($Source.Contains($beforeKey)) "Reviewed native adapter no longer matches its immutable input: $Before"
 $Source.Replace($beforeKey,(Canonical $After))
}
function Imports([string]$Source) {
 (@([regex]::Matches($Source,'(?m)^import ([^;\r\n]+);') | ForEach-Object {$_.Groups[1].Value}) | Sort-Object -Unique) -join "`n"
}
function Native-Names([string]$Source) {
 $moves=[ordered]@{
  'net.minecraftforge.api.distmarker'='net.neoforged.api.distmarker'
  'net.minecraftforge.fml'='net.neoforged.fml'
  'net.minecraftforge.eventbus.api'='net.neoforged.bus.api'
  'net.minecraftforge.client'='net.neoforged.neoforge.client'
  'net.minecraftforge.fluids'='net.neoforged.neoforge.fluids'
  'net.minecraftforge.event.TickEvent'='net.neoforged.neoforge.client.event.ClientTickEvent'
  'net.minecraftforge.common.capabilities.ForgeCapabilities'='net.neoforged.neoforge.capabilities.Capabilities'
  'net.minecraftforge.registries.ForgeRegistries'='net.minecraft.core.registries.BuiltInRegistries'
  'net.minecraftforge.registries.ForgeRegistry'='net.neoforged.neoforge.registries.RegistrySnapshot'
  'import net.minecraftforge.registries.GameData;'=''
  'net.minecraftforge.registries.RegistryManager'='net.neoforged.neoforge.registries.RegistryManager'
  'ForgeRegistries.ITEMS'='BuiltInRegistries.ITEM'
  'ForgeRegistries.BLOCKS'='BuiltInRegistries.BLOCK'
  'ForgeRegistries.FLUIDS'='BuiltInRegistries.FLUID'
  'ForgeRegistries.ENTITY_TYPES.getValue('='zone.moddev.mc.basepower.util.RegistryValues.find(BuiltInRegistries.ENTITY_TYPE, '
  'ForgeRegistries.BLOCK_ENTITY_TYPES.getValue('='zone.moddev.mc.basepower.util.RegistryValues.find(BuiltInRegistries.BLOCK_ENTITY_TYPE, '
  '@Mod.EventBusSubscriber'='@net.neoforged.fml.common.EventBusSubscriber'
 }
 foreach($move in $moves.GetEnumerator()) { $Source=$Source.Replace($move.Key,$move.Value) }
 return $Source
}
foreach($name in $hashes.Keys) {
 $source=Native-Names (Read-Baseline "$relative/$name.java" $hashes[$name])
 if($name -eq 'RecipeBookGroupingProbe') { $source="import net.minecraft.core.registries.Registries;`n" + $source }
 $target=Get-Content -LiteralPath (Join-Path $TargetRoot "$relative/$name.java") -Raw
 Require ((Imports $source) -ceq (Imports $target)) "$name import bindings changed beyond explicit native class moves."
 if($name -eq 'RecipeBookGroupingProbe') {
  $start=$target.IndexOf('    private record NativeRecipeFixture')
  $end=$target.IndexOf('    private static void render(', [Math]::Max(0,$start))
  Require ($start -ge 0 -and $end -gt $start) "$name changed beyond explicit native adapters: missing per-packet fixture helpers."
  $helpers=$target.Substring($start,$end-$start)
  $helperHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes((Canonical $helpers))))
  Require ($helperHash -ceq 'B5AC4F67564A0AA69B1CF44A27C6199B77AF0F893C449E99C7DA7CBEC717ABC4') "$name changed beyond explicit native adapters: per-packet helper binding."
  $target=$target.Remove($start,$end-$start)
 }
 $expected=Canonical $source
 switch($name) {
  ClientResourceProbe {
   $expected=Adapt $expected 'TickEvent.ClientTickEvent event' 'ClientTickEvent.Post event'
   $expected=Adapt $expected ' || event.phase != TickEvent.Phase.END' ''
   $expected=Adapt $expected 'net.minecraftforge.versions.mcp.MCPVersion.getMCVersion()' 'net.minecraft.SharedConstants.getCurrentVersion().getName()'
   $expected=Adapt $expected 'result.addProperty("forge", net.minecraftforge.versions.forge.ForgeVersion.getVersion());' 'result.addProperty("neoforge", net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion.getVersion());'
   $expected=Adapt $expected '"52.1.0".equals(result.get("forge").getAsString())' '"21.1.265".equals(result.get("neoforge").getAsString())'
   $expected=$expected.Replace('2.4.14.121011','2.4.19.121012').Replace('2.2.15.121011','2.2.20.121012')
  }
  TankVisualProbe {
   $expected=Adapt $expected @'
IFluidHandler handler = server.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP)
 .orElseThrow(() -> new IllegalStateException(name + " tank has no native Forge fluid capability"));
'@ @'
PacketLevel capabilityLevel = PacketLevel.withRegistries(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
IFluidHandler handler = Capabilities.FluidHandler.BLOCK.getCapability(capabilityLevel, BlockPos.ZERO, state, server, Direction.UP);
require(handler != null, name + " tank has no native NeoForge fluid capability");
'@
  }
  OilRenderProbe {
   $expected=Adapt $expected '((BucketItem) bucket).getFluid()' 'zone.moddev.mc.basepower.util.FluidContainerHelper.fluidForBucket(bucket)'
   $expected=Adapt $expected 'DefaultVertexFormat.BLOCK.getOffset(i)' 'DefaultVertexFormat.BLOCK.getOffset(element)'
  }
  ChildRendererGeometryProbe {
   $expected=Adapt $expected 'minecraft.getEntityRenderDispatcher().renderers.get(type)' 'registeredEntityRenderer(minecraft, type)'
   $expected=Adapt $expected 'BuiltInRegistries.BLOCK.getValues().stream()' 'BuiltInRegistries.BLOCK.stream()'
   $expected=Adapt $expected 'format.getOffset(i)' 'format.getOffset(element)'
   $expected=Adapt $expected 'private static Object valueOfType(Object object, Class<?> type) throws IllegalAccessException {' @'
private static EntityRenderer<?> registeredEntityRenderer(Minecraft minecraft, EntityType<?> type) throws ReflectiveOperationException {
 var field = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class.getDeclaredField("renderers");
 field.setAccessible(true);
 return (EntityRenderer<?>) ((java.util.Map<?, ?>) field.get(minecraft.getEntityRenderDispatcher())).get(type);
}
private static Object valueOfType(Object object, Class<?> type) throws IllegalAccessException {
'@
  }
  RecipeBookGroupingProbe {
   $expected=Adapt $expected 'List<Path> packets = files.filter(file -> file.toString().endsWith(".bin")).sorted().toList();' @'
List<Path> binaries = files.filter(file -> file.toString().endsWith(".bin")).sorted().toList();
List<Path> packets = binaries.stream().filter(file -> !file.toString().endsWith(".registry.bin")
 && !file.getFileName().toString().equals("server-registry-snapshot.bin")).sorted().toList();
'@
   $expected=Adapt $expected 'Map<ResourceLocation, ForgeRegistry.Snapshot> registry = RegistryManager.ACTIVE.takeSnapshot(false);' ''
   $expected=$expected.Replace('nativeForge','nativeNeoForge').Replace('"forge".equals(mod.getString("ModId"))','"neoforge".equals(mod.getString("ModId"))').Replace('"52.1.0"','"21.1.265"').Replace('Recipe fixture was not saved by Forge 52.1.0','Recipe fixture was not saved by NeoForge 21.1.265')
   $expected=Adapt $expected @'
CompoundTag saved = level.getCompound("fml").getCompound("Registries");
String itemRegistry = BuiltInRegistries.ITEM.getRegistryName().toString();
String blockRegistry = BuiltInRegistries.BLOCK.getRegistryName().toString();
require(saved.contains(itemRegistry, Tag.TAG_COMPOUND) && saved.contains(blockRegistry, Tag.TAG_COMPOUND)
 && !saved.getCompound(itemRegistry).getList("ids", Tag.TAG_COMPOUND).isEmpty()
 && !saved.getCompound(blockRegistry).getList("ids", Tag.TAG_COMPOUND).isEmpty(), "Missing native server registry snapshot");
for (String key : saved.getAllKeys()) registry.put(ResourceLocation.parse(key), ForgeRegistry.Snapshot.read(saved.getCompound(key)));
require(GameData.injectSnapshot(registry, false, false).isEmpty(), "Client lacks a server registry entry");
'@ ''
   $expected=Adapt $expected 'RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);' ''
   $expected=Adapt $expected @'
require(packetNames.equals(Set.of("recipes-normal-sweep.bin", "recipes-tech-sweep.bin", "recipes-apocalyptic-sweep.bin")),
 "Normal, Tech and Apocalyptic recipe-book packets are required");
'@ @'
require(packetNames.equals(Set.of("recipes-normal-sweep.bin", "recipes-tech-sweep.bin", "recipes-apocalyptic-sweep.bin")),
 "Normal, Tech and Apocalyptic recipe-book packets are required");
Set<String> snapshotNames = binaries.stream().filter(file -> file.toString().endsWith(".registry.bin"))
 .map(file -> file.getFileName().toString()).collect(Collectors.toSet());
require(snapshotNames.equals(packetNames.stream().map(RecipeBookGroupingProbe::snapshotName).collect(Collectors.toSet())),
 "Each recipe packet requires its own exact registry snapshot sidecar");
'@
   $expected=Adapt $expected 'require("PASS".equals(manifest.get("status").getAsString()), "Server recipe fixture did not pass");' @'
require("PASS".equals(manifest.get("status").getAsString()), "Server recipe fixture did not pass");
NativeRecipeFixture fixture = loadNativeFixture(file, manifest);
RegistryAccess registries = fixture.registries();
'@
   $expected=Adapt $expected 'Unpooled.wrappedBuffer(Files.readAllBytes(file))' 'Unpooled.wrappedBuffer(fixture.packetBytes())'
   $expected=Adapt $expected 'finally { buffer.release(); } RecipeManager manager = new RecipeManager(registries);' @'
finally { buffer.release(); }
require(packet.getRecipes().size() == manifest.getAsJsonObject("registrySnapshot").get("recipeCount").getAsInt(),
 "Recipe packet count differs from its registry-bound receipt");
RecipeManager manager = new RecipeManager(registries);
'@
  }
 }
 Require ($expected -ceq (Canonical $target)) "$name changed beyond explicit native adapters."
}
$labels='resources/gui-label-expectations.json'
Require ((Read-Baseline $labels '5BDE8743D07B2CBD4AD916F81A13008FB6A6DE69281B0774F2FCA2A44F098832').Replace("`r`n","`n") -ceq
 ([IO.File]::ReadAllText((Join-Path $TargetRoot $labels))).Replace("`r`n","`n")) 'Independent human-facing caption expectations changed.'
$pack=Get-Content -LiteralPath (Join-Path $TargetRoot 'resources/pack.mcmeta') -Raw | ConvertFrom-Json
Require ($pack.pack.pack_format -eq 34) 'Native 1.21.1 resource pack must use format 34.'
$metadata=Get-Content -LiteralPath (Join-Path $TargetRoot 'resources/META-INF/neoforge.mods.toml') -Raw
foreach($pin in 'loaderVersion="[4,)"','versionRange="[1.21.1]"','versionRange="[21.1.265]"','versionRange="[2.4.19.121012]"') {
 Require ($metadata.Contains($pin)) "Missing native probe metadata pin: $pin"
}
$runner=Get-Content -LiteralPath (Join-Path $TargetRoot 'run.ps1') -Raw
$captionGate=@'
$expectedGuiTextChecks = if ($RequireExtensions) { 351 } else { 126 }
if ($MachineGuiLayouts -and ($result.machine_gui_text_spec -ne 2 -or $result.machine_gui_text_status -ne 'PASS' -or
 $result.machine_gui_text_checks -ne $expectedGuiTextChecks -or
 $result.machine_gui_negative_controls_rejected -ne ($expectedGuiTextChecks + 4))) {
 throw 'Required-caption GUI contract v2 did not pass; an older no-label probe is not acceptable.'
}
'@
Require ((Canonical $runner).Contains((Canonical $captionGate))) 'Runner must require all 351 caption checks and 355 negative controls (126/130 for Power only).'
Require ($runner -notmatch '\b(?:Remove-Item|Clear-Content)\b') 'Runner must not delete old or failed evidence.'
$failure=[regex]::Match($runner,'(?s)\} catch \{\s*# Preserve native partial results.*?\} finally \{').Value
Require ($failure.Contains('runner-failure.json') -and $failure.Contains('if (-not (Test-Path')) 'Runner must preserve partial native results and write its failure separately.'
Write-Output "PASS: seven complete probe bodies/imports and captions bound to Forge 1.21.1 commit $baseline; only reviewed NeoForge adapters allowed. Rendering NOT RUN."
