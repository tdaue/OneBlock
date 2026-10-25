package oneblock.utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit / Folia scheduling bridge.
 *
 * <p>On Folia, world and entity state may only be touched from the region
 * thread that owns it, so work is routed to the global, region or entity
 * schedulers. The Folia API is reached through reflection so the plugin
 * keeps compiling against the plain Spigot API and running on Java 8.
 *
 * <p>Everywhere else every method behaves exactly like the pre-Folia code:
 * {@link #runAt} and {@link #runFor} run immediately on the calling thread,
 * and the timed methods delegate to the {@code BukkitScheduler}.
 */
public final class Scheduler {
	public static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");
	private static final String PKG = "io.papermc.paper.threadedregions.scheduler.";
	private static final long MS_PER_TICK = 50L;

	private static Plugin plugin;
	private static Object global, async, region;
	private static Method globalRun, globalRunDelayed, globalRunAtFixedRate, globalCancel;
	private static Method asyncRunAtFixedRate, asyncCancel;
	private static Method regionRun, regionRunDelayed;
	private static Method entityGetScheduler, entityRun, entityRunDelayed, teleportAsync;

	private Scheduler() {}

	public static void init(Plugin pl) {
		plugin = pl;
		if (!FOLIA) return;
		try {
			Class<?> g = Class.forName(PKG + "GlobalRegionScheduler");
			Class<?> a = Class.forName(PKG + "AsyncScheduler");
			Class<?> r = Class.forName(PKG + "RegionScheduler");
			Class<?> e = Class.forName(PKG + "EntityScheduler");
			global = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
			async = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
			region = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
			globalRun = g.getMethod("run", Plugin.class, Consumer.class);
			globalRunDelayed = g.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
			globalRunAtFixedRate = g.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
			globalCancel = g.getMethod("cancelTasks", Plugin.class);
			asyncRunAtFixedRate = a.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
			asyncCancel = a.getMethod("cancelTasks", Plugin.class);
			regionRun = r.getMethod("run", Plugin.class, Location.class, Consumer.class);
			regionRunDelayed = r.getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
			entityGetScheduler = Entity.class.getMethod("getScheduler");
			entityRun = e.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
			entityRunDelayed = e.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
			teleportAsync = Entity.class.getMethod("teleportAsync", Location.class);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Folia detected, but its scheduler API could not be resolved", ex);
		}
	}

	/** Server-wide work (console commands, config). Folia: global region thread. */
	public static void run(Runnable task) {
		if (FOLIA) call(globalRun, global, plugin, wrap(task));
		else task.run();
	}

	public static void runLater(Runnable task, long delay) {
		if (FOLIA) call(globalRunDelayed, global, plugin, wrap(task), Math.max(1, delay));
		else Bukkit.getScheduler().runTaskLater(plugin, task, delay);
	}

	public static void runTimer(Runnable task, long delay, long period) {
		if (FOLIA) call(globalRunAtFixedRate, global, plugin, wrap(task), Math.max(1, delay), Math.max(1, period));
		else Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
	}

	public static void runTimerAsync(Runnable task, long delay, long period) {
		if (FOLIA) call(asyncRunAtFixedRate, async, plugin, wrap(task),
				Math.max(1, delay) * MS_PER_TICK, Math.max(1, period) * MS_PER_TICK, TimeUnit.MILLISECONDS);
		else Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
	}

	/** Work on blocks around {@code loc}. Folia: the region thread owning that location. */
	public static void runAt(Location loc, Runnable task) {
		if (FOLIA) call(regionRun, region, plugin, loc, wrap(task));
		else task.run();
	}

	public static void runAtLater(Location loc, Runnable task, long delay) {
		if (FOLIA) call(regionRunDelayed, region, plugin, loc, wrap(task), Math.max(1, delay));
		else Bukkit.getScheduler().runTaskLater(plugin, task, delay);
	}

	/** Work on an entity. Folia: the entity's own thread; skipped if it was removed. */
	public static void runFor(Entity entity, Runnable task) {
		if (FOLIA) call(entityRun, entityScheduler(entity), plugin, wrap(task), null);
		else task.run();
	}

	public static void runForLater(Entity entity, Runnable task, long delay) {
		if (FOLIA) call(entityRunDelayed, entityScheduler(entity), plugin, wrap(task), null, Math.max(1, delay));
		else Bukkit.getScheduler().runTaskLater(plugin, task, delay);
	}

	/** Folia forbids synchronous teleports; {@code teleportAsync} is used there instead. */
	public static void teleport(Entity entity, Location loc) {
		if (FOLIA) runFor(entity, () -> call(teleportAsync, entity, loc));
		else entity.teleport(loc);
	}

	public static void cancelTasks() {
		if (FOLIA) {
			call(globalCancel, global, plugin);
			call(asyncCancel, async, plugin);
		}
		else Bukkit.getScheduler().cancelTasks(plugin);
	}

	/** Players in {@code world}, readable from any thread. */
	public static Collection<? extends Player> playersIn(World world) {
		if (!FOLIA) return world.getPlayers();
		List<Player> players = new ArrayList<>();
		for (Player p : Bukkit.getOnlinePlayers())
			if (world.equals(p.getWorld())) players.add(p);
		return players;
	}

	private static Object entityScheduler(Entity entity) {
		try {
			return entityGetScheduler.invoke(entity);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Consumer<Object> wrap(Runnable task) {
		return scheduledTask -> task.run();
	}

	private static Object call(Method method, Object target, Object... args) {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			throw new IllegalStateException(cause);
		} catch (IllegalAccessException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static boolean classExists(String name) {
		try {
			Class.forName(name);
			return true;
		} catch (ClassNotFoundException ex) {
			return false;
		}
	}
}
