package me.nallar.tickthreading.minecraft;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.IPlayerTracker;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkMod;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.RelaunchClassLoader;
import javassist.is.faulty.Timings;
import me.nallar.insecurity.ThisIsNotAnError;
import me.nallar.reporting.LeakDetector;
import me.nallar.reporting.Metrics;
import me.nallar.tickthreading.Log;
import me.nallar.tickthreading.minecraft.commands.Command;
import me.nallar.tickthreading.minecraft.commands.DumpCommand;
import me.nallar.tickthreading.minecraft.commands.ProfileCommand;
import me.nallar.tickthreading.minecraft.commands.TPSCommand;
import me.nallar.tickthreading.minecraft.commands.TicksCommand;
import me.nallar.tickthreading.minecraft.entitylist.EntityList;
import me.nallar.tickthreading.minecraft.entitylist.LoadedEntityList;
import me.nallar.tickthreading.minecraft.entitylist.LoadedTileEntityList;
import me.nallar.tickthreading.util.FieldUtil;
import me.nallar.tickthreading.util.LocationUtil;
import me.nallar.tickthreading.util.PatchUtil;
import me.nallar.tickthreading.util.TableFormatter;
import me.nallar.tickthreading.util.VersionUtil;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetServerHandler;
import net.minecraft.network.packet.Packet3Chat;
import net.minecraft.network.packet.PacketCount;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.Configuration;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.EventPriority;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;

@SuppressWarnings ("WeakerAccess")
@Mod (modid = "TickThreading", name = "TickThreading", version = "@MOD_VERSION@")
@NetworkMod (clientSideRequired = false, serverSideRequired = false)
public class TickThreading {
	@Mod.Instance
	public static TickThreading instance;
	private static final Metrics metrics = new Metrics("TickThreading", VersionUtil.TTVersionNumber());
	private static final int loadedEntityFieldIndex = 0;
	private static final int loadedTileEntityFieldIndex = 2;
	final Map<World, TickManager> managers = new LinkedHashMap<World, TickManager>();
	private final Runtime runtime = Runtime.getRuntime();
	public String messageDeadlockDetected = "The server appears to have frozen and will restart soon if it does not recover. :(";
	public String messageDeadlockRecovered = "The server has recovered and will not need to restart. :)";
	public String messageDeadlockSavingExiting = "The server is saving the world and restarting - be right back!";
	public boolean exitOnDeadlock = false;
	public boolean requireOpForTicksCommand = true;
	public boolean requireOpForProfileCommand = true;
	public boolean shouldLoadSpawn = false;
	public boolean concurrentNetworkTicks = false;
	public boolean antiCheatKick = false;
	public boolean antiCheatNotify = true;
	public boolean cleanWorlds = true;
	public boolean allowWorldUnloading = true;
	public boolean requireOpForDumpCommand = true;
	public boolean enableFastMobSpawning = true;
	public boolean enableBugWarningMessage = true;
	public int saveInterval = 180;
	public int deadLockTime = 45;
	public int chunkCacheSize = 2000;
	public int chunkGCInterval = 1200;
	private int tickThreads = 0;
	private int regionSize = 16;
	public boolean variableTickRate = true;
	private boolean appliedEnergisticsLoaded = false;
	private DeadLockDetector deadLockDetector;
	private HashSet<Integer> disabledFastMobSpawningDimensions = new HashSet<Integer>();
	private boolean waitForEntityTickCompletion = true;
	private int targetTPS = 20;
	private final LeakDetector leakDetector = new LeakDetector(1200);
	public static int recentSpawnedItems;

	public TickThreading() {
		Log.LOGGER.getLevel(); // Force log class to load
		try {
			PatchUtil.writePatchRunners();
		} catch (IOException e) {
			Log.severe("Failed to write patch runners", e);
		}
		if (PatchUtil.shouldPatch(LocationUtil.getJarLocations())) {
			Log.severe("TickThreading is disabled, because your server has not been patched" +
					" or the patches are out of date" +
					"\nTo patch your server, simply run the PATCHME.bat/sh file in your server directory");
			MinecraftServer.getServer().initiateShutdown();
			Runtime.getRuntime().exit(1);
		}
	}

	@Mod.Init
	public void init(FMLInitializationEvent event) {
		MinecraftForge.EVENT_BUS.register(this);
		if (!enableBugWarningMessage) {
			return;
		}
		appliedEnergisticsLoaded = Loader.isModLoaded("AppliedEnergistics");
		GameRegistry.registerPlayerTracker(new LoginWarningHandler());
	}

	@SuppressWarnings ("FieldRepeatedlyAccessedInMethod")
	@Mod.PreInit
	public void preInit(FMLPreInitializationEvent event) {
		Configuration config = new Configuration(event.getSuggestedConfigurationFile());
		config.load();
		String GENERAL = Configuration.CATEGORY_GENERAL;

		TicksCommand.name = config.get(GENERAL, "ticksCommandName", TicksCommand.name, "Name of the command to be used for performance stats. Defaults to ticks.").value;
		TPSCommand.name = config.get(GENERAL, "tpsCommandName", TPSCommand.name, "Name of the command to be used for TPS reports.").value;
		ProfileCommand.name = config.get(GENERAL, "profileCommandName", ProfileCommand.name, "Name of the command to be used for profiling reports.").value;
		DumpCommand.name = config.get(GENERAL, "dumpCommandName", DumpCommand.name, "Name of the command to be used for profiling reports.").value;
		messageDeadlockDetected = config.get(GENERAL, "messageDeadlockDetected", messageDeadlockDetected, "The message to be displayed if a deadlock is detected. (Only sent if exitOnDeadlock is on)").value;
		messageDeadlockRecovered = config.get(GENERAL, "messageDeadlockRecovered", messageDeadlockRecovered, "The message to be displayed if the server recovers from an apparent deadlock. (Only sent if exitOnDeadlock is on)").value;
		messageDeadlockSavingExiting = config.get(GENERAL, "messageDeadlockSavingExiting", messageDeadlockSavingExiting, "The message to be displayed when the server attempts to save and stop after a deadlock. (Only sent if exitOnDeadlock is on)").value;
		tickThreads = config.get(GENERAL, "tickThreads", tickThreads, "number of threads to use to tick. 0 = automatic").getInt(tickThreads);
		regionSize = config.get(GENERAL, "regionSize", regionSize, "width/length of tick regions, specified in blocks.").getInt(regionSize);
		saveInterval = config.get(GENERAL, "saveInterval", saveInterval, "Time between auto-saves, in ticks.").getInt(saveInterval);
		deadLockTime = config.get(GENERAL, "deadLockTime", deadLockTime, "The time(seconds) of being frozen which will trigger the DeadLockDetector. Set to 1 to instead detect lag spikes.").getInt(deadLockTime);
		chunkCacheSize = Math.max(100, config.get(GENERAL, "chunkCacheSize", chunkCacheSize, "Number of unloaded chunks to keep cached. Replacement for Forge's dormant chunk cache, which tends to break. Minimum size of 100").getInt(chunkCacheSize));
		chunkGCInterval = config.get(GENERAL, "chunkGCInterval", chunkGCInterval, "Interval between chunk garbage collections in ticks").getInt(chunkGCInterval);
		targetTPS = config.get(GENERAL, "targetTPS", targetTPS, "TPS the server should try to run at.").getInt(targetTPS);
		variableTickRate = config.get(GENERAL, "variableRegionTickRate", variableTickRate, "Allows tick rate to vary per region so that each region uses at most 50ms on average per tick.").getBoolean(variableTickRate);
		exitOnDeadlock = config.get(GENERAL, "exitOnDeadlock", exitOnDeadlock, "If the server should shut down when a deadlock is detected").getBoolean(exitOnDeadlock);
		enableFastMobSpawning = config.get(GENERAL, "enableFastMobSpawning", enableFastMobSpawning, "If enabled, TT's alternative mob spawning implementation will be used.").getBoolean(enableFastMobSpawning);
		requireOpForTicksCommand = config.get(GENERAL, "requireOpsForTicksCommand", requireOpForTicksCommand, "If a player must be opped to use /ticks").getBoolean(requireOpForTicksCommand);
		requireOpForProfileCommand = config.get(GENERAL, "requireOpsForProfileCommand", requireOpForProfileCommand, "If a player must be opped to use /profile").getBoolean(requireOpForProfileCommand);
		requireOpForDumpCommand = config.get(GENERAL, "requireOpsForDumpCommand", requireOpForDumpCommand, "If a player must be opped to use /dump").getBoolean(requireOpForDumpCommand);
		shouldLoadSpawn = config.get(GENERAL, "shouldLoadSpawn", shouldLoadSpawn, "Whether chunks within 200 blocks of world spawn points should always be loaded.").getBoolean(shouldLoadSpawn);
		waitForEntityTickCompletion = config.get(GENERAL, "waitForEntityTickCompletion", waitForEntityTickCompletion, "Whether we should wait until all Tile/Entity tick threads are finished before moving on with world tick. False = experimental, but may improve performance.").getBoolean(waitForEntityTickCompletion);
		concurrentNetworkTicks = config.get(GENERAL, "concurrentNetworkTicks", concurrentNetworkTicks, "Whether network ticks should be ran in a separate thread from the main minecraft thread. This is likely to be very buggy, especially with mods doing custom networking such as IC2!").getBoolean(concurrentNetworkTicks);
		antiCheatKick = config.get(GENERAL, "antiCheatKick", antiCheatKick, "Whether to kick players for detected cheating").getBoolean(antiCheatKick);
		antiCheatNotify = config.get(GENERAL, "antiCheatNotify", antiCheatNotify, "Whether to notify admins if TT anti-cheat detects cheating").getBoolean(antiCheatNotify);
		cleanWorlds = config.get(GENERAL, "cleanWorlds", cleanWorlds, "Whether to clean worlds on unload - this should fix some memory leaks due to mods holding on to world objects").getBoolean(cleanWorlds);
		allowWorldUnloading = config.get(GENERAL, "allowWorldUnloading", allowWorldUnloading, "Whether worlds should be allowed to unload.").getBoolean(allowWorldUnloading);
		enableBugWarningMessage = config.get(GENERAL, "enableBugWarningMessage", enableBugWarningMessage, "Whether to enable warning if there are severe known compatibility issues with the current TT build you are using and your installed mods. Highly recommend leaving this enabled, if you disable it chances are you'll get users experiencing these issues annoying mod authors, which I really don't want to happen.").getBoolean(enableBugWarningMessage);
		config.save();
		int[] disabledDimensions = config.get(GENERAL, "disableFastMobSpawningDimensions", new int[]{-1}, "List of dimensions not to enable fast spawning in.").getIntList();
		disabledFastMobSpawningDimensions = new HashSet<Integer>(disabledDimensions.length);
		for (int disabledDimension : disabledDimensions) {
			disabledFastMobSpawningDimensions.add(disabledDimension);
		}
		PacketCount.allowCounting = false;
	}

	@Mod.ServerStarting
	public void serverStarting(FMLServerStartingEvent event) {
		Log.severe(VersionUtil.versionString() + " is installed on this server!"
				+ "\nIf anything breaks, check if it is still broken without TickThreading"
				+ "\nWe don't want to annoy mod devs with issue reports caused by TickThreading."
				+ "\nSeriously, please don't."
				+ "\nIf it's only broken with TickThreading, report it at http://github.com/nallar/TickThreading");
		ServerCommandManager serverCommandManager = (ServerCommandManager) event.getServer().getCommandManager();
		serverCommandManager.registerCommand(new TicksCommand());
		serverCommandManager.registerCommand(new TPSCommand());
		serverCommandManager.registerCommand(new ProfileCommand());
		serverCommandManager.registerCommand(new DumpCommand());
		MinecraftServer.setTargetTPS(targetTPS);
		FMLLog.info("Loaded " + RelaunchClassLoader.patchedClasses + " patched classes" +
				"\nUsed " + RelaunchClassLoader.usedPatchedClasses + '.');
		Command.checkForPermissions();
	}

	@ForgeSubscribe (
			priority = EventPriority.HIGHEST
	)
	public synchronized void onWorldLoad(WorldEvent.Load event) {
		World world = event.world;
		if (world.isRemote) {
			Log.severe("World " + Log.name(world) + " seems to be a client world", new Throwable());
			return;
		}
		if (DimensionManager.getWorld(world.getDimension()) != world) {
			Log.severe("World " + world.getName() + " was loaded with an incorrect dimension ID!", new Throwable());
			return;
		}
		if (managers.containsKey(world)) {
			Log.severe("World " + world.getName() + "'s world load event was fired twice.", new Throwable());
			return;
		}
		world.loadEventFired = true;
		TickManager manager = new TickManager((WorldServer) world, regionSize, getThreadCount(), waitForEntityTickCompletion);
		try {
			Field loadedTileEntityField = FieldUtil.getFields(World.class, List.class)[loadedTileEntityFieldIndex];
			new LoadedTileEntityList<TileEntity>(world, loadedTileEntityField, manager);
			Field loadedEntityField = FieldUtil.getFields(World.class, List.class)[loadedEntityFieldIndex];
			new LoadedEntityList<TileEntity>(world, loadedEntityField, manager);
			Log.info("Threading initialised for world " + Log.name(world));
			if (managers.put(world, manager) != null) {
				Log.severe("World load fired twice for world " + world.getName());
			}
		} catch (Exception e) {
			Log.severe("Failed to initialise threading for world " + Log.name(world), e);
		}
		if (deadLockDetector == null) {
			deadLockDetector = new DeadLockDetector();
		}
	}

	@ForgeSubscribe
	public synchronized void onWorldUnload(WorldEvent.Unload event) {
		World world = event.world;
		try {
			TickManager tickManager = managers.remove(world);
			if (tickManager == null) {
				Log.severe("World unload fired twice for world " + world.getName(), new Throwable());
				return;
			}
			tickManager.unload();
			Field loadedTileEntityField = FieldUtil.getFields(World.class, List.class)[loadedTileEntityFieldIndex];
			Object loadedTileEntityList = loadedTileEntityField.get(world);
			if (!(loadedTileEntityList instanceof EntityList)) {
				Log.severe("Looks like another mod broke TT's replacement tile entity list in world: " + Log.name(world));
			}
			Field loadedEntityField = FieldUtil.getFields(World.class, List.class)[loadedEntityFieldIndex];
			Object loadedEntityList = loadedEntityField.get(world);
			if (!(loadedEntityList instanceof EntityList)) {
				Log.severe("Looks like another mod broke TT's replacement entity list in world: " + Log.name(world));
			}
		} catch (Exception e) {
			Log.severe("Probable memory leak, failed to unload threading for world " + Log.name(world), e);
		}
		if (world instanceof WorldServer) {
			((WorldServer) world).stopChunkTickThreads();
			leakDetector.scheduleLeakCheck(world, world.getName(), cleanWorlds);
		}
	}

	@ForgeSubscribe
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
			EntityPlayer entityPlayer = event.entityPlayer;
			ItemStack usedItem = entityPlayer.getCurrentEquippedItem();
			if (usedItem != null) {
				Item usedItemType = usedItem.getItem();
				if (usedItemType == Item.pocketSundial && (!requireOpForDumpCommand || entityPlayer.canCommandSenderUseCommand(4, "dump"))) {
					Command.sendChat(entityPlayer, DumpCommand.dump(new TableFormatter(entityPlayer), entityPlayer.worldObj, event.x, event.y, event.z, 35).toString());
					event.setCanceled(true);
				}
			}
		}
	}

	@ForgeSubscribe
	public void onEntityFall(LivingFallEvent event) {
		if (!Log.debug || !(event.entity instanceof EntityPlayerMP)) {
			return;
		}
		EntityPlayerMP entityPlayerMP = (EntityPlayerMP) event.entity;
		Log.debug(entityPlayerMP.username + " fell " + TableFormatter.formatDoubleWithPrecision(event.distance, 2) + " blocks. Current thread: " + Log.toString(Thread.currentThread()), new ThisIsNotAnError());
	}

	public TickManager getManager(World world) {
		return managers.get(world);
	}

	public List<TickManager> getManagers() {
		return new ArrayList<TickManager>(managers.values());
	}

	public boolean shouldFastSpawn(World world) {
		return this.enableFastMobSpawning && !disabledFastMobSpawningDimensions.contains(world.getDimension());
	}

	public int getThreadCount() {
		return tickThreads == 0 ? runtime.availableProcessors() + 1 : tickThreads;
	}

	public void waitForEntityTicks() {
		if (!waitForEntityTickCompletion) {
			long sT = 0;
			boolean profiling = Timings.enabled;
			if (profiling) {
				sT = System.nanoTime();
			}
			for (TickManager manager : managers.values()) {
				manager.tickEnd();
			}
			if (profiling) {
				Timings.record("server/EntityTickWait", System.nanoTime() - sT);
			}
		}
	}

	private class LoginWarningHandler implements IPlayerTracker {
		LoginWarningHandler() {
		}

		@Override
		public void onPlayerLogin(final EntityPlayer player) {
			if (player instanceof EntityPlayerMP) {
				NetServerHandler netServerHandler = ((EntityPlayerMP) player).playerNetServerHandler;
				if (appliedEnergisticsLoaded) {
					netServerHandler.sendPacketToPlayer(new Packet3Chat("You're using TickThreading with Applied Energistics. This is currently a bad idea, and you may lose items."));
					netServerHandler.sendPacketToPlayer(new Packet3Chat("See https://github.com/nallar/TickThreading/issues/246"));
				}
			}
		}

		@Override
		public void onPlayerLogout(final EntityPlayer player) {
		}

		@Override
		public void onPlayerChangedDimension(final EntityPlayer player) {
		}

		@Override
		public void onPlayerRespawn(final EntityPlayer player) {
		}
	}
}
