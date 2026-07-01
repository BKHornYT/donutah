package net.donutsmp.donutah;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TooltipHandler {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private static final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean>     fetching   = new ConcurrentHashMap<>();
    private static final java.util.Set<String>    loggedScreenTitles = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Name of the item currently being hovered — updated every tooltip render tick. */
    public static volatile String lastHoveredItemName = null;

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
            if (!DonutAH.onTargetServer) return;
            if (stack.isEmpty()) return;
            lastHoveredItemName = stack.getHoverName().getString().trim();
            if (!DonutAHConfig.tooltipEnabled) return;

            // Never add DonutAH lines to server UI buttons (Filter, Next Page, Quick Buy,
            // Search, Edit, "Empty" placeholders, ...) — identified by control name or
            // "Click to ..." instruction lore.
            if (AHScraper.isServerUiStack(stack)) return;

            // Skip if the current screen is a muted GUI (e.g. "Team", "Settings")
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen != null) {
                String rawTitle = mc.screen.getTitle().getString();
                String screenTitle = AHScraper.normalizeSmallCaps(
                    AHScraper.stripFormatting(rawTitle)).toLowerCase().trim();
                if (BuildConstants.STAGING && !loggedScreenTitles.contains(rawTitle)) {
                    loggedScreenTitles.add(rawTitle);
                    net.donutsmp.donutah.DebugWebhook.send("🖥️ Screen title raw: `" + rawTitle + "` → normalized: `" + screenTitle + "`");
                }
                if (!DonutAH.mutedGuis.isEmpty()) {
                    for (String muted : DonutAH.mutedGuis) {
                        String normalizedMuted = AHScraper.normalizeSmallCaps(muted).toLowerCase().trim();
                        if (screenTitle.contains(normalizedMuted)) {
                            // Only suppress tooltip if the hovered slot is in the container
                            // portion of the screen — not in the player's own inventory.
                            if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> hs) {
                                net.donutsmp.donutah.mixin.HandledScreenAccessor acc =
                                        (net.donutsmp.donutah.mixin.HandledScreenAccessor) hs;
                                net.minecraft.world.inventory.Slot focused = acc.donutah_getFocusedSlot();
                                net.minecraft.world.inventory.AbstractContainerMenu handler = hs.getMenu();
                                int totalSlots = handler.slots.size();
                                if (focused != null) {
                                    // Use absolute slot position (indexOf), not within-inventory index (getSlotIndex())
                                    int absIdx = handler.slots.indexOf(focused);
                                    // Last 36 slots are always the player inventory
                                    if (absIdx >= 0 && absIdx < totalSlots - 36) return;
                                    // else: player inventory slot — show tooltip normally
                                }
                                // focusedSlot null or player inventory — show tooltip
                            } else {
                                return; // Non-handled screen — suppress
                            }
                            break;
                        }
                    }
                }
            }

            // Normalize both ways so small-caps DB names match new ASCII AH item names
            String hoveredLower = lastHoveredItemName.toLowerCase();
            String hoveredNorm  = AHScraper.normalizeSmallCaps(hoveredLower);

            // Muted items: show static price if set, then label, then return
            if (DonutAH.mutedItems.contains(hoveredLower) || DonutAH.mutedItems.contains(hoveredNorm)) {
                Double staticPrice = DonutAH.staticPrices.get(hoveredLower);
                if (staticPrice == null) staticPrice = DonutAH.staticPrices.get(hoveredNorm);
                if (staticPrice != null) {
                    String pfx = DonutAHConfig.getFormattedPrefix();
                    String col = DonutAHConfig.getColorCode();
                    if (DonutAHConfig.tooltipSpacer) lines.add(Component.literal(""));
                    lines.add(Component.literal(pfx + " §7Shop: " + col + "$" + formatPrice(staticPrice)));
                    int count = stack.getCount();
                    if (DonutAHConfig.showStackValue && count > 1) {
                        lines.add(Component.literal(pfx + " §7Stack §8(§f" + count + "x§8)§7: " + col + "$" + formatPrice(staticPrice * count)));
                    }
                }
                if (DonutAHConfig.devShowLabels) {
                    String label = DonutAH.itemLabels.get(hoveredLower);
                    if (label == null) label = DonutAH.itemLabels.get(hoveredNorm);
                    if (label != null) {
                        if (staticPrice == null && DonutAHConfig.tooltipSpacer) lines.add(Component.literal(""));
                        lines.add(Component.literal(DonutAHConfig.getFormattedPrefix() + " §e★ " + label));
                    }
                }
                return;
            }

            // Shulker boxes: show total contents value instead of the shulker's market price
            if (isShulker(stack)) {
                if (DonutAHConfig.showShulkerValue) appendShulkerValue(stack, lines);
                return;
            }

            String itemName = stack.getHoverName().getString();
            List<String> enchants = extractEnchantments(stack);
            String key = itemName.toLowerCase() + "|" + String.join(",", enchants);

            CachedPrice cached = priceCache.get(key);

            if (BuildConstants.DEV && DonutAHConfig.devShowRawName) {
                lines.add(Component.literal("§8[dev] §7raw: §f" + itemName));
            }

            if (cached != null && !cached.isExpired()) {
                if (DonutAHConfig.tooltipSpacer) lines.add(Component.literal(""));
                appendPriceLine(lines, cached, stack.getCount());
            } else if (!fetching.containsKey(key)) {
                fetching.put(key, true);
                fetchAsync(itemName, enchants, key);
                if (DonutAHConfig.tooltipSpacer) lines.add(Component.literal(""));
                lines.add(Component.literal(DonutAHConfig.getFormattedPrefix() + " §7Avg: §eFetching..."));
            } else {
                if (DonutAHConfig.tooltipSpacer) lines.add(Component.literal(""));
                lines.add(Component.literal(DonutAHConfig.getFormattedPrefix() + " §7Avg: §eFetching..."));
            }

            // Show custom label if set (server-wide, set via /donutah label)
            if (DonutAHConfig.devShowLabels) {
                String label = DonutAH.itemLabels.get(itemName.toLowerCase());
                if (label == null) label = DonutAH.itemLabels.get(AHScraper.normalizeSmallCaps(itemName.toLowerCase()));
                if (label != null) {
                    lines.add(Component.literal(DonutAHConfig.getFormattedPrefix() + " §e★ " + label));
                }
            }
        });
    }

    // ── Shulker box handling ───────────────────────────────────────────────

    private static boolean isShulker(ItemStack stack) {
        return (stack.getItem() instanceof BlockItem bi) && (bi.getBlock() instanceof ShulkerBoxBlock);
    }

    private static void appendShulkerValue(ItemStack shulker, List<Component> lines) {
        ItemContainerContents container = shulker.get(DataComponents.CONTAINER);
        if (container == null) {
            lines.add(Component.literal(DonutAHConfig.getFormattedPrefix() + " §7Contents: §7Empty"));
            return;
        }

        double total = 0;
        boolean loading = false;
        boolean hasItems = false;

        for (net.minecraft.world.item.ItemStackTemplate t : container.nonEmptyItems()) {
            ItemStack c = t.create();
            hasItems = true;
            String name = c.getHoverName().getString();

            // Use static price if available (shop items with known fixed price)
            Double staticPrice = DonutAH.staticPrices.get(name.toLowerCase());
            if (staticPrice != null) {
                total += staticPrice * c.getCount();
                continue;
            }

            List<String> enchants = AHScraper.getStackEnchantments(c);
            Collections.sort(enchants);
            String key = name.toLowerCase() + "|" + String.join(",", enchants);

            CachedPrice cached = priceCache.get(key);
            if (cached == null || cached.isExpired()) {
                if (!fetching.containsKey(key)) {
                    fetching.put(key, true);
                    fetchAsync(name, enchants, key);
                }
                loading = true;
            } else if (!cached.noData) {
                total += cached.avgPrice * c.getCount();
            }
        }

        String pfx = DonutAHConfig.getFormattedPrefix();
        String col  = DonutAHConfig.getColorCode();
        if (DonutAHConfig.tooltipSpacer) lines.add(Component.literal(""));
        if (!hasItems) {
            lines.add(Component.literal(pfx + " §7Contents: §7Empty"));
        } else if (loading) {
            lines.add(Component.literal(pfx + " §7Contents: §eFetching..."));
        } else {
            lines.add(Component.literal(pfx + " §7Contents: " + col + "$" + formatPrice(total) + " §8total"));
        }
    }

    // ── Regular item handling ──────────────────────────────────────────────

    private static List<String> extractEnchantments(ItemStack stack) {
        List<String> enchants = AHScraper.getStackEnchantments(stack);
        Collections.sort(enchants);
        return enchants;
    }

    private static void appendPriceLine(List<Component> lines, CachedPrice cached, int stackCount) {
        String pfx = DonutAHConfig.getFormattedPrefix();
        String col  = DonutAHConfig.getColorCode();

        if (cached.noData) {
            lines.add(Component.literal(pfx + " §7Avg: §cNo data"));
            return;
        }

        // Avg per unit
        lines.add(Component.literal(pfx + " §7Avg: " + col + "$" + formatPrice(cached.avgPrice) + " §8per unit"));

        // Stack value (only when holding more than 1)
        if (DonutAHConfig.showStackValue && stackCount > 1) {
            double stackVal = cached.avgPrice * stackCount;
            lines.add(Component.literal(pfx + " §7Stack §8(§f" + stackCount + "x§8)§7: " + col + "$" + formatPrice(stackVal)));
        }

        // Min/max range
        if (DonutAHConfig.showMinMax && (cached.minPrice > 0 || cached.maxPrice > 0)) {
            lines.add(Component.literal(pfx + " §7Range: §e$" + formatPrice(cached.minPrice) + " §8→ §c$" + formatPrice(cached.maxPrice)));
        }

        // Trend arrow (▲ green if up, ▼ red if down)
        if (DonutAHConfig.showTrend && cached.hasTrend) {
            String arrow = cached.trendPct >= 0 ? "§a▲" : "§c▼";
            String pct   = String.format("%.1f%%", Math.abs(cached.trendPct));
            lines.add(Component.literal(pfx + " §7Trend: " + arrow + " §7" + pct + " §8(24h)"));
        }

        // Daily volume
        if (DonutAHConfig.showVolume && cached.dailyVolume > 0) {
            lines.add(Component.literal(pfx + " §7Volume: §f" + cached.dailyVolume + " §8scans today"));
        }
    }

    private static void fetchAsync(String itemName, List<String> enchants, String key) {
        Thread.ofVirtual().start(() -> {
            try {
                net.donutsmp.donutah.network.ApiClient.PriceResponse resp =
                        net.donutsmp.donutah.network.ApiClient.fetchPrice(itemName, enchants);
                CachedPrice cp = new CachedPrice();
                cp.noData    = (resp == null || resp.avg <= 0);
                cp.avgPrice  = cp.noData ? 0 : resp.avg;
                cp.minPrice  = cp.noData ? 0 : resp.min;
                cp.maxPrice  = cp.noData ? 0 : resp.max;
                cp.fetchedAt = System.currentTimeMillis();

                // Fetch trend + volume if either config is enabled
                if (!cp.noData && (DonutAHConfig.showTrend || DonutAHConfig.showVolume)) {
                    net.donutsmp.donutah.network.ApiClient.StatsResponse stats =
                            net.donutsmp.donutah.network.ApiClient.fetchStats(itemName);
                    if (stats != null) {
                        cp.hasTrend   = true;
                        cp.trendPct   = stats.price_change_pct;
                        cp.dailyVolume = stats.daily_volume;
                    }
                }

                priceCache.put(key, cp);
            } catch (Exception e) {
                CachedPrice cp = new CachedPrice();
                cp.noData    = true;
                cp.fetchedAt = System.currentTimeMillis();
                priceCache.put(key, cp);
            } finally {
                fetching.remove(key);
            }
        });
    }

    public static void clearCache() {
        priceCache.clear();
        fetching.clear();
        loggedScreenTitles.clear();
    }

    public static String formatPrice(double p) {
        if (!DonutAHConfig.compactNumbers) return String.format("%.0f", p);
        if (p >= 1_000_000_000_000L) return String.format("%.2fT", p / 1_000_000_000_000L);
        if (p >= 1_000_000_000) return String.format("%.2fB", p / 1_000_000_000);
        if (p >= 1_000_000)     return String.format("%.2fM", p / 1_000_000);
        if (p >= 1_000)         return String.format("%.1fK", p / 1_000);
        return String.format("%.0f", p);
    }

    private static class CachedPrice {
        double  avgPrice;
        double  minPrice;
        double  maxPrice;
        boolean noData;
        boolean hasTrend;
        double  trendPct;
        int     dailyVolume;
        long    fetchedAt;
        boolean isExpired() { return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS; }
    }
}
