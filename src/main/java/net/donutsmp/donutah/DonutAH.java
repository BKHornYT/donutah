package net.donutsmp.donutah;

import net.donutsmp.donutah.commands.DonutAHCommand;
import net.donutsmp.donutah.dev.LabelInputScreen;
import net.donutsmp.donutah.network.ApiClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DonutAH implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("donutah");
    public static final String TARGET_SERVER_SUFFIX = "donutsmp.net";
    public static volatile String API_BASE = BuildConstants.STAGING ? ApiClient.STAGING_PRIMARY_BASE : ApiClient.PRIMARY_BASE;
    public static boolean onTargetServer = false;

    // Tooltip cache — pre-populated from /api/latest on join, used by TooltipHandler
    public static final Map<String, CachedListing> listingCache = new ConcurrentHashMap<>();
    // Scan dedup cache — starts empty each session, only tracks what was submitted THIS session
    public static final Map<String, CachedListing> scanCache = new ConcurrentHashMap<>();

    // Items hidden from DB/dashboard (server blacklist — managed via dashboard)
    public static final Set<String> blacklistedItems = ConcurrentHashMap.newKeySet();
    // Items whose tooltips are suppressed in-game (separate from blacklist)
    public static final Set<String> mutedItems       = ConcurrentHashMap.newKeySet();
    // Locally toggled mutes — merged back after every reload/sync
    public static final Set<String> localMuted       = ConcurrentHashMap.newKeySet();

    // Session scan counter — incremented per item on successful POST (reset on disconnect)
    public static volatile int sessionScans = 0;

    // GUI names whose tooltips are suppressed (title contains match → no DonutAH tooltip)
    public static final Set<String> mutedGuis = ConcurrentHashMap.newKeySet();

    // Custom item labels fetched from backend (item_name.lowercase → label text)
    public static final Map<String, String> itemLabels = new ConcurrentHashMap<>();
    // Static prices for shop items (item_name.lowercase → fixed price)
    public static final Map<String, Double> staticPrices = new ConcurrentHashMap<>();
    // Timestamp of last successful cache sync (epoch ms)
    public static volatile long lastSyncTime = 0;

    // Key state tracking
    private static boolean muteKeyWasDown  = false;
    private static boolean labelKeyWasDown = false;
    private static boolean invKeyWasDown   = false;

    public static class CachedListing {
        public final double price;
        public final String seller;
        public CachedListing(double price, String seller) {
            this.price  = price;
            this.seller = seller;
        }
    }

    @Override
    public void onInitializeClient() {
        DonutAHConfig.load();

        // Inventory value key — all builds
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null || client.player == null) return;
            if (!onTargetServer || client.screen == null) return;
            boolean vDown = InputConstants.isKeyDown(
                client.getWindow(),DonutAHConfig.inventoryValueKey.getValue());
            if (vDown && !invKeyWasDown) {
                double total = 0;
                int counted = 0;
                for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack stack = client.player.getInventory().getItem(i);
                    if (stack.isEmpty()) continue;
                    String name = stack.getHoverName().getString();
                    java.util.List<String> enchants = net.donutsmp.donutah.AHScraper.getStackEnchantments(stack);
                    java.util.Collections.sort(enchants);
                    String key = name.toLowerCase() + "|" + String.join(",", enchants);
                    CachedListing listing = listingCache.get(key);
                    if (listing == null && !enchants.isEmpty()) {
                        // Fallback to unenchanted price
                        listing = listingCache.get(name.toLowerCase() + "|");
                    }
                    if (listing == null) {
                        // Check static prices
                        Double sp = staticPrices.get(name.toLowerCase());
                        if (sp != null) { total += sp * stack.getCount(); counted++; }
                    } else {
                        total += listing.price * stack.getCount();
                        counted++;
                    }
                }
                final double fTotal = total;
                final int fCounted = counted;
                client.execute(() -> client.player.sendSystemMessage(
                    Component.literal("§8[§b⬡§8] §7Inventory value: §a$" + TooltipHandler.formatPrice(fTotal) +
                        " §8(§f" + fCounted + " §7item types priced§8)")));
            }
            invKeyWasDown = vDown;
        });

        if (BuildConstants.DEV) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.getWindow() == null) return;

                // Mute key — toggle mute while hovering item
                boolean bDown = InputConstants.isKeyDown(
                    client.getWindow(),DonutAHConfig.devMuteKey.getValue());
                if (bDown && !muteKeyWasDown && onTargetServer) {
                    String name = TooltipHandler.lastHoveredItemName;
                    if (name != null && !name.isEmpty()) {
                        String nameFinal = name;
                        boolean isMuted = mutedItems.contains(nameFinal.toLowerCase());
                        Thread.ofVirtual().start(() -> {
                            if (isMuted) {
                                boolean ok = ApiClient.unmuteItem(nameFinal);
                                mutedItems.remove(nameFinal.toLowerCase());
                                localMuted.remove(nameFinal.toLowerCase());
                                TooltipHandler.clearCache();
                                if (client.player != null) {
                                    String status = ok ? "§eUnmuted" : "§cFailed to save (unmuted locally)";
                                    client.execute(() -> client.player.sendSystemMessage(
                                        Component.literal("§8[§b⬡§8] " + status + "§7: §f\"" + nameFinal + "\"")));
                                }
                            } else {
                                boolean ok = ApiClient.muteItem(nameFinal);
                                localMuted.add(nameFinal.toLowerCase());
                                mutedItems.add(nameFinal.toLowerCase());
                                TooltipHandler.clearCache();
                                if (client.player != null) {
                                    String status = ok ? "§aMuted" : "§cFailed to save (muted locally)";
                                    client.execute(() -> client.player.sendSystemMessage(
                                        Component.literal("§8[§b⬡§8] " + status + "§7: §f\"" + nameFinal + "\"")));
                                }
                            }
                        });
                    }
                }
                muteKeyWasDown = bDown;

                // Label key — open label editor while hovering item
                boolean lDown = InputConstants.isKeyDown(
                    client.getWindow(),DonutAHConfig.devLabelKey.getValue());
                if (lDown && !labelKeyWasDown && onTargetServer && client.screen != null) {
                    String name = TooltipHandler.lastHoveredItemName;
                    if (name != null && !name.isEmpty()) {
                        String nameFinal = name;
                        client.execute(() -> client.setScreen(new LabelInputScreen(nameFinal, null)));
                    }
                }
                labelKeyWasDown = lDown;
            });
        }

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData info = client.getCurrentServer();
            if (info != null) {
                String addr = info.ip.toLowerCase();
                onTargetServer = addr.endsWith(TARGET_SERVER_SUFFIX)
                        || addr.contains(TARGET_SERVER_SUFFIX + ":");
                LOGGER.info("[DonutAH] Connected to {}. Active: {}", addr, onTargetServer);

                if (onTargetServer) {
                    if (BuildConstants.STAGING) {
                        DebugWebhook.send("🔌 Joined **donutsmp.net** — selecting best server...");
                    }

                    // Cache sync loop: re-selects best server before every fetch
                    Thread.ofVirtual().start(() -> {
                        boolean firstRun = true;
                        do {
                            // Always pick best server before each cache sync
                            String best = ApiClient.selectBestServer();
                            if (!best.equals(API_BASE)) {
                                LOGGER.info("[DonutAH] API switched: {} -> {}", API_BASE, best);
                                if (BuildConstants.STAGING) {
                                    String msg = firstRun
                                        ? "🌐 Server selected: `" + best + "`"
                                        : "⚠️ Server switched: `" + API_BASE + "` → `" + best + "`";
                                    DebugWebhook.send(msg);
                                }
                                API_BASE = best;
                            } else if (BuildConstants.STAGING && firstRun) {
                                DebugWebhook.send("🌐 Server selected: `" + best + "`");
                            }
                            firstRun = false;

                            List<ApiClient.LatestListing> latest = ApiClient.fetchLatest();
                            for (ApiClient.LatestListing ll : latest) {
                                if (ll.item_name != null && ll.seller != null) {
                                    listingCache.put(ll.getCacheKey(),
                                            new CachedListing(ll.price, ll.seller));
                                }
                            }
                            Set<String> bl = ApiClient.fetchBlacklist();
                            blacklistedItems.clear();
                            blacklistedItems.addAll(bl);
                            Set<String> mu = ApiClient.fetchMuted();
                            mutedItems.clear();
                            mutedItems.addAll(mu);
                            mutedItems.addAll(localMuted);
                            java.util.Map<String, String> labels = ApiClient.fetchLabels();
                            itemLabels.clear();
                            itemLabels.putAll(labels);
                            java.util.Map<String, Double> sp = ApiClient.fetchStaticPrices();
                            staticPrices.clear();
                            staticPrices.putAll(sp);
                            java.util.Set<String> mg = ApiClient.fetchMutedGuis();
                            mutedGuis.clear();
                            mutedGuis.addAll(mg);
                            TooltipHandler.clearCache();
                            lastSyncTime = System.currentTimeMillis();
                            LOGGER.info("[DonutAH] Cache synced: {} listings, {} blacklisted, {} muted, {} labels, {} static prices via {}", latest.size(), bl.size(), mu.size(), labels.size(), sp.size(), API_BASE);
                            if (BuildConstants.STAGING) {
                                DebugWebhook.send("🔄 Cache synced via `" + API_BASE + "`: **" + latest.size() + "** listings, **" + bl.size() + "** blacklisted, **" + mu.size() + "** muted, **" + labels.size() + "** labels, **" + sp.size() + "** static prices");
                            }

                            if (BuildConstants.DEV && firstRun) {
                                ApiClient.OnlineStats onlineStats = ApiClient.fetchOnlineStats();
                                if (onlineStats != null && onlineStats.online > 0) {
                                    int count = onlineStats.online;
                                    Minecraft mc = Minecraft.getInstance();
                                    mc.execute(() -> {
                                        if (mc.player != null)
                                            mc.player.sendSystemMessage(Component.literal(
                                                "§8[§b⬡§8] §7" + count + " DonutSMP player" +
                                                (count == 1 ? "" : "s") + " online with DonutAH"));
                                    });
                                }
                            }

                            long intervalMs = BuildConstants.DEV
                                ? (long) DonutAHConfig.devSyncIntervalMin * 60_000L
                                : 3L * 60_000L;
                            try { Thread.sleep(intervalMs); } catch (InterruptedException e) { return; }
                        } while (onTargetServer);
                    });

                    {
                        final String uuid     = client.player != null ? client.player.getStringUUID() : null;
                        final String username = client.player != null ? client.player.getName().getString() : "Unknown";

                        // Heartbeat loop — tells backend this player is online (every 60s)
                        Thread.ofVirtual().start(() -> {
                            do {
                                if (uuid != null) ApiClient.postHeartbeat(uuid, username);
                                try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
                            } while (onTargetServer);
                        });
                    }
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (BuildConstants.STAGING && onTargetServer) {
                DebugWebhook.send("🔴 Disconnected — session scans submitted: **" + sessionScans + "**");
            }
            onTargetServer = false;
            sessionScans = 0;
            TooltipHandler.clearCache();
            listingCache.clear();
            scanCache.clear();
            blacklistedItems.clear();
            mutedItems.clear();
            localMuted.clear();
            itemLabels.clear();
            staticPrices.clear();
            mutedGuis.clear();
            lastSyncTime = 0;
        });

        TooltipHandler.register();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                DonutAHCommand.register(dispatcher));

        LOGGER.info("[DonutAH] Initialized. API: {}", API_BASE);
    }
}
