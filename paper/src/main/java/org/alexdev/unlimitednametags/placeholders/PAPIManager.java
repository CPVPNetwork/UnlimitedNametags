package org.alexdev.unlimitednametags.placeholders;

import lombok.Getter;
import me.clip.placeholderapi.PlaceholderAPI;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

public class PAPIManager {

    /** How long an async caller waits for the owning region thread before giving up on a placeholder. */
    private static final long REGION_WAIT_MS = 1500L;
    /** Idle time after which a value resolved for an async caller is dropped. */
    private static final long REGION_CACHE_IDLE_SECONDS = 30L;

    private final UnlimitedNameTags plugin;
    @Getter
    private boolean papiEnabled;
    private UntPapiExpansion untPapiExpansion;
    /**
     * Folia only: values resolved on the owning region thread on behalf of callers that are not on it.
     * Keyed by the player whose region resolved the text.
     */
    private final Map<UUID, Map<String, RegionValue>> regionCache = new ConcurrentHashMap<>();

    public PAPIManager(@NotNull UnlimitedNameTags plugin) {
        this.plugin = plugin;
        this.checkPapiEnabled();
    }

    public void checkPapiEnabled() {
        if (papiEnabled) {
            return;
        }

        this.papiEnabled = plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (papiEnabled) {
            try {
                this.untPapiExpansion = new UntPapiExpansion(plugin);
                this.untPapiExpansion.register();
            } catch (Throwable e) {
                plugin.getLogger().warning("Failed to register PlaceholderAPI expansion: " + e.getMessage());
            }
        } else {
            plugin.getLogger().info("PlaceholderAPI is not enabled, some features may not work.");
        }
    }

    @NotNull
    public String setPlaceholders(Player player, String text) {
        if (skip(text)) {
            return text;
        }
        return resolve(player, "2\u0000" + text, text, () -> {
            final String firstReplacement = PlaceholderAPI.setPlaceholders(player, text);
            return PlaceholderAPI.setPlaceholders(player, firstReplacement);
        });
    }

    /**
     * Single PlaceholderAPI pass, used for {@code when} expressions.
     */
    @NotNull
    public String setPlaceholdersOnce(@NotNull Player player, @NotNull String text) {
        if (skip(text)) {
            return text;
        }
        return resolve(player, "1\u0000" + text, text, () -> PlaceholderAPI.setPlaceholders(player, text));
    }

    @NotNull
    public String setRelationalPlaceholders(@NotNull Player whoSees, @NotNull Player target, @NotNull String text) {
        if (skip(text)) {
            return text;
        }
        return resolve(target, "2\u0000" + whoSees.getUniqueId() + '\u0000' + text, text, () -> {
            final String firstReplacement = PlaceholderAPI.setRelationalPlaceholders(whoSees, target, text);
            return PlaceholderAPI.setRelationalPlaceholders(whoSees, target, firstReplacement);
        });
    }

    /**
     * Single relational PlaceholderAPI pass, used for {@code when} expressions.
     */
    @NotNull
    public String setRelationalPlaceholdersOnce(@NotNull Player whoSees, @NotNull Player target, @NotNull String text) {
        if (skip(text)) {
            return text;
        }
        return resolve(target, "1\u0000" + whoSees.getUniqueId() + '\u0000' + text, text,
                () -> PlaceholderAPI.setRelationalPlaceholders(whoSees, target, text));
    }

    private boolean skip(@NotNull String text) {
        return !papiEnabled || text.indexOf('%') == -1;
    }

    /**
     * Whether placeholders of this player must be resolved on the thread of the region that owns the player.
     * True on Folia and its forks (Canvas, ...), where expansions reading entity or world state fail anywhere else.
     */
    public boolean needsRegionThread() {
        return plugin.isFolia();
    }

    /**
     * Runs {@code task} on the region thread owning {@code player} (immediately when already on it).
     * The future fails with {@link CancellationException} when the player is gone before the task could run.
     */
    @NotNull
    public <T> CompletableFuture<T> supplyOnRegionThread(@NotNull Player player, @NotNull Supplier<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        final Runnable run = () -> {
            try {
                future.complete(task.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        };
        try {
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                run.run();
                return future;
            }
            final Runnable retired = () -> future.completeExceptionally(
                    new CancellationException(player.getName() + " is no longer on the server"));
            if (!player.getScheduler().execute(plugin, run, retired, 1L)) {
                retired.run();
            }
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Resolves {@code task} for {@code regionOwner}. Outside Folia, or when already on the owning region thread,
     * it runs in place. Any other thread gets the value computed on the owning region thread: the first request
     * waits for it, later ones are served from a short lived cache that is refreshed in the background.
     */
    @NotNull
    private String resolve(@NotNull Player regionOwner, @NotNull String cacheKey, @NotNull String fallback,
                           @NotNull Supplier<String> task) {
        if (!plugin.isFolia() || Bukkit.isOwnedByCurrentRegion(regionOwner)) {
            return run(task, fallback, true);
        }

        final Map<String, RegionValue> cache = regionCache.computeIfAbsent(regionOwner.getUniqueId(),
                u -> ExpiringMap.builder()
                        .expiration(REGION_CACHE_IDLE_SECONDS, TimeUnit.SECONDS)
                        .expirationPolicy(ExpirationPolicy.ACCESSED)
                        .build());
        final RegionValue cached = cache.get(cacheKey);
        if (cached != null) {
            if (System.nanoTime() - cached.createdAt > regionCacheTtlNanos() && cached.refreshing.compareAndSet(false, true)) {
                supplyOnRegionThread(regionOwner, () -> run(task, fallback, true)).whenComplete((value, error) -> {
                    if (error == null) {
                        cache.put(cacheKey, new RegionValue(value));
                    } else {
                        cached.refreshing.set(false);
                    }
                });
            }
            return cached.value;
        }

        final CompletableFuture<String> pending = supplyOnRegionThread(regionOwner, () -> run(task, fallback, true));
        pending.thenAccept(value -> cache.put(cacheKey, new RegionValue(value)));
        if (Bukkit.isPrimaryThread()) {
            // Tick thread of another region: waiting here could stall or deadlock the server, so answer now
            // with a best effort value and let the owning region fill the cache for the next request.
            return run(task, fallback, false);
        }
        try {
            return pending.get(REGION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            // player left, or the region did not tick in time
            return fallback;
        }
    }

    @NotNull
    private String run(@NotNull Supplier<String> task, @NotNull String text, boolean log) {
        try {
            return task.get();
        } catch (Throwable e) {
            if (log) {
                plugin.getLogger().log(Level.SEVERE, "Failed to set placeholders for text: " + text, e);
            }
            return text;
        }
    }

    private long regionCacheTtlNanos() {
        final int ticks = Math.max(1, plugin.getConfigManager().getSettings().getPerformance().getPlaceholderCacheTime());
        return TimeUnit.MILLISECONDS.toNanos(ticks * 50L);
    }

    public void removePlayer(@NotNull UUID playerId) {
        regionCache.remove(playerId);
    }

    public void clearCache() {
        regionCache.clear();
    }

    public void close() {
        regionCache.clear();
        if (papiEnabled && untPapiExpansion != null) {
            untPapiExpansion.unregister();
        }
    }

    private static final class RegionValue {
        private final String value;
        private final long createdAt = System.nanoTime();
        private final AtomicBoolean refreshing = new AtomicBoolean();

        private RegionValue(@NotNull String value) {
            this.value = value;
        }
    }

}
