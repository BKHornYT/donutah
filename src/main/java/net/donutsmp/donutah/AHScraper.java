package net.donutsmp.donutah;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.donutsmp.donutah.network.ApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AHScraper {

    private static final Pattern PRICE_PATTERN =
            Pattern.compile("\\$\\s*([\\d,]+(?:\\.\\d+)?)(K|M|B|T|Q)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCH_PAREN =
            Pattern.compile("\\(([^)]+)\\)");
    private static final Gson GSON = new Gson();

    public enum SortMode { LOWEST_PRICE, HIGHEST_PRICE, LAST_LISTED, RECENTLY_LISTED, UNKNOWN }

    public static void onAHScreenOpen(ChestMenu handler) {
        if (!DonutAH.onTargetServer) return;
        if (handler.getRowCount() != 6) return;

        Minecraft client = Minecraft.getInstance();

        // Log all non-empty slots for debugging
        DonutAH.LOGGER.info("[DonutAH] === SLOT SCAN ===");
        int nonEmpty = 0;
        for (int slot = 0; slot < 54; slot++) {
            ItemStack stack = handler.getSlot(slot).getItem();
            if (!stack.isEmpty()) {
                nonEmpty++;
                DonutAH.LOGGER.info("[DonutAH] Slot {}: '{}' lore={}", slot,
                        stack.getHoverName().getString(), getLoreStrings(stack));
            }
        }
        DonutAH.LOGGER.info("[DonutAH] Non-empty: {}", nonEmpty);

        if (nonEmpty == 0) {
            DonutAH.LOGGER.warn("[DonutAH] All slots empty — data not loaded yet");
            if (BuildConstants.STAGING) {
                DebugWebhook.send("⚠️ AH screen: all 54 slots empty — data not loaded yet");
            }
            return;
        }

        // Backstop against filtered/own-listings views: a real AH page 1 always has a
        // "Next Page" button; sparse search-result pages (e.g. own listings) don't.
        boolean hasNextPage = false;
        for (int slot = 0; slot < 54; slot++) {
            ItemStack stack = handler.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            String n = normalizeSmallCaps(stripFormatting(stack.getHoverName().getString())).trim();
            if (n.equals("next page")) { hasNextPage = true; break; }
        }
        if (!hasNextPage) {
            DonutAH.LOGGER.info("[DonutAH] Skipping — no Next Page button (filtered/search view, not real page 1)");
            if (BuildConstants.STAGING) {
                DebugWebhook.send("⏭️ Scan skipped: no Next Page button — filtered/search view");
            }
            return;
        }

        // Detect sort mode from any slot's lore
        SortMode mode = detectSortMode(handler);
        DonutAH.LOGGER.info("[DonutAH] Sort mode: {}", mode);

        // ONLY scan when sorted by Lowest Price
        if (mode != SortMode.LOWEST_PRICE) {
            DonutAH.LOGGER.info("[DonutAH] Skipping — not Lowest Price sort (mode={})", mode);
            if (BuildConstants.STAGING) {
                DebugWebhook.send("⏭️ Scan skipped: sort=**" + mode + "** (need LOWEST\\_PRICE)");
            }
            return;
        }

        // ONLY scan when filter is set to All
        if (!detectFilterAll(handler)) {
            DonutAH.LOGGER.info("[DonutAH] Skipping — filter is not set to All");
            if (BuildConstants.STAGING) {
                DebugWebhook.send("⏭️ Scan skipped: filter ≠ ALL");
            }
            return;
        }

        // Skip if viewing own listings — search item lore shows "(username)" matching the current player
        String activeSearch = detectActiveSearch(handler);
        String playerName = client.player.getName().getString();
        DonutAH.LOGGER.info("[DonutAH] detectActiveSearch='{}' playerName='{}'", activeSearch, playerName);
        if (BuildConstants.STAGING) {
            DebugWebhook.send("🔍 activeSearch=`" + activeSearch + "` playerName=`" + playerName + "`");
        }
        if (activeSearch != null && activeSearch.equalsIgnoreCase(playerName)) {
            DonutAH.LOGGER.info("[DonutAH] Skipping — viewing own listings (search={})", activeSearch);
            if (BuildConstants.STAGING) {
                DebugWebhook.send("⏭️ Scan skipped: viewing own listings (**" + activeSearch + "**)");
            }
            return;
        }

        scanAndSubmit(handler, mode);
    }

    // Returns the active search term if the SEARCH item's lore contains "(sometext)",
    // indicating a player/item filter is active (e.g. /ah bkhorn → "(bkhorn)").
    // Returns null if no active search is detected.
    private static String detectActiveSearch(ChestMenu handler) {
        // Scan all slots for any item whose lore contains (username) — don't rely on item name
        // matching since DonutSMP uses custom fonts/encoding we can't reliably normalize.
        // Player names are 3-16 chars, alphanumeric + underscore, no spaces.
        for (int slot = 0; slot < 54; slot++) {
            ItemStack stack = handler.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            for (String line : getLoreStrings(stack)) {
                String clean = stripFormatting(line).trim();
                Matcher m = SEARCH_PAREN.matcher(clean);
                if (m.find()) {
                    String content = m.group(1);
                    if (content.length() >= 3 && content.length() <= 16
                            && content.matches("[a-zA-Z0-9_]+")) {
                        DonutAH.LOGGER.info("[DonutAH] detectActiveSearch: found '{}' in slot {} lore", content, slot);
                        if (BuildConstants.STAGING) {
                            DebugWebhook.send("🔍 detectActiveSearch: `(" + content + ")` found in slot " + slot);
                        }
                        return content;
                    }
                }
            }
        }
        return null;
    }

    private static boolean detectFilterAll(ChestMenu handler) {
        for (int slot = 0; slot < 54; slot++) {
            ItemStack stack = handler.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            String name = normalizeSmallCaps(stripFormatting(stack.getHoverName().getString()).trim());
            if (!name.equals("filter")) continue;

            DonutAH.LOGGER.info("[DonutAH] Found FILTER item at slot {}", slot);
            // Primary: same Text-API color inspection as parseSortFromText
            if (parseFilterFromText(stack)) return true;
            // Fallback: §-code method, same as parseSortLore
            if (parseFilterLore(getLoreStrings(stack))) return true;

            // New AH format: Filter item controls sort order only (no category filter).
            // If its lore contains sort-mode keywords, there is no category filter active
            // and the AH is showing all items — treat as "All".
            List<String> lore = getLoreStrings(stack);
            for (String line : lore) {
                String clean = stripFormatting(line).toLowerCase().replaceAll("[^a-z]", "");
                if (clean.contains("lowestprice") || clean.contains("highestprice")
                        || clean.contains("recentlylisted") || clean.contains("lastlisted")) {
                    DonutAH.LOGGER.info("[DonutAH] Filter item has sort-only lore (new AH format) — treating as ALL");
                    if (BuildConstants.STAGING) {
                        DebugWebhook.send("ℹ️ Filter item has sort-only lore (new AH format) — treating as ALL");
                    }
                    return true;
                }
            }

            DonutAH.LOGGER.warn("[DonutAH] FILTER item found but state unknown — lore: {}", lore);
            if (BuildConstants.STAGING) {
                DebugWebhook.send("⚠️ Scan skipped: FILTER item state unknown (lore: `" + lore + "`)");
            }
            return false;
        }
        DonutAH.LOGGER.warn("[DonutAH] FILTER item not found — skipping scan");
        if (BuildConstants.STAGING) {
            DebugWebhook.send("⚠️ Scan skipped: FILTER item not found in any slot");
        }
        return false;
    }

    private static boolean parseFilterFromText(ItemStack stack) {
        try {
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) return false;
            for (Component line : lore.lines()) {
                boolean[] hasNonWhite = {false};
                line.visit((style, content) -> {
                    var color = style.getColor();
                    if (color != null) {
                        int rgb = color.getValue() & 0xFFFFFF;
                        if (rgb != 0xFFFFFF && rgb != 0xAAAAAA && rgb != 0x555555
                                && rgb != 0x000000 && rgb != 0xDDDDDD) {
                            hasNonWhite[0] = true;
                        }
                    }
                    return Optional.empty();
                }, Style.EMPTY);
                if (hasNonWhite[0]) {
                    // Strip formatting and non-letter chars (bullets, spaces) then match "all" exactly
                    String text = stripFormatting(line.getString()).toLowerCase()
                                  .replaceAll("[^a-z]", "");
                    DonutAH.LOGGER.info("[DonutAH] Colored filter lore line: '{}'", text);
                    if (text.equals("all")) return true;
                }
            }
        } catch (Throwable e) {
            DonutAH.LOGGER.warn("[DonutAH] parseFilterFromText: {}", e.getMessage());
            if (BuildConstants.STAGING) {
                DebugWebhook.send("❌ parseFilterFromText exception: `" + e.getMessage() + "`");
            }
        }
        return false;
    }

    private static boolean parseFilterLore(List<String> lore) {
        for (String line : lore) {
            if (line.contains("§a") || line.contains("§2") || line.contains("§b")) {
                String text = stripFormatting(line).toLowerCase().replaceAll("[^a-z]", "");
                DonutAH.LOGGER.info("[DonutAH] Filter §-code colored: '{}'", text);
                if (text.equals("all")) return true;
            }
        }
        return false;
    }

    private static SortMode detectSortMode(ChestMenu handler) {
        // First pass: find SORT or FILTER item by name
        for (int slot = 0; slot < 54; slot++) {
            ItemStack stack = handler.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            String name = normalizeSmallCaps(stripFormatting(stack.getHoverName().getString()).trim());
            if (name.equals("sort")) {
                DonutAH.LOGGER.info("[DonutAH] Found SORT item at slot {}", slot);
                SortMode mode = parseSortFromText(stack);
                if (mode != SortMode.UNKNOWN) return mode;
                mode = parseSortLore(getLoreStrings(stack));
                if (mode != SortMode.UNKNOWN) return mode;
                // Don't early-return UNKNOWN — fall through to second pass
            } else if (name.equals("filter")) {
                // New AH format: Filter item holds sort options; active = white, inactive = gray
                DonutAH.LOGGER.info("[DonutAH] Found FILTER item at slot {} (new AH format)", slot);
                SortMode mode = parseSortFromFilterLore(stack);
                if (mode != SortMode.UNKNOWN) return mode;
                mode = parseSortLore(getLoreStrings(stack));
                if (mode != SortMode.UNKNOWN) return mode;
            }
        }
        // Second pass: scan every item's lore for a colored sort keyword
        for (int slot = 0; slot < 54; slot++) {
            ItemStack stack = handler.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            SortMode mode = parseSortFromText(stack);
            if (mode != SortMode.UNKNOWN) return mode;
        }
        return SortMode.UNKNOWN;
    }

    // Directly inspects Text color runs — works with any RGB, not just the 16 standard colors
    private static SortMode parseSortFromText(ItemStack stack) {
        try {
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) return SortMode.UNKNOWN;
            for (Component line : lore.lines()) {
                boolean[] hasNonWhite = {false};
                line.visit((style, content) -> {
                    var color = style.getColor();
                    if (color != null) {
                        int rgb = color.getValue() & 0xFFFFFF;
                        // Anything that isn't white or gray shades is "active" color
                        if (rgb != 0xFFFFFF && rgb != 0xAAAAAA && rgb != 0x555555
                                && rgb != 0x000000 && rgb != 0xDDDDDD) {
                            hasNonWhite[0] = true;
                        }
                    }
                    return Optional.empty();
                }, Style.EMPTY);
                if (hasNonWhite[0]) {
                    String text = line.getString().toLowerCase().trim();
                    DonutAH.LOGGER.info("[DonutAH] Colored lore line: '{}'", text);
                    if (text.contains("lowest price"))    return SortMode.LOWEST_PRICE;
                    if (text.contains("highest price"))   return SortMode.HIGHEST_PRICE;
                    if (text.contains("last listed"))     return SortMode.LAST_LISTED;
                    if (text.contains("recently listed")) return SortMode.RECENTLY_LISTED;
                }
            }
        } catch (Throwable e) {
            DonutAH.LOGGER.warn("[DonutAH] parseSortFromText: {}", e.getMessage());
            if (BuildConstants.STAGING) {
                DebugWebhook.send("❌ parseSortFromText exception: `" + e.getMessage() + "`");
            }
        }
        return SortMode.UNKNOWN;
    }

    // Like parseSortFromText but treats WHITE as "active" — new vanilla AH UI uses
    // white for the selected sort option and gray for inactive ones.
    private static SortMode parseSortFromFilterLore(ItemStack stack) {
        try {
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) return SortMode.UNKNOWN;
            for (Component line : lore.lines()) {
                boolean[] hasNonGray = {false};
                line.visit((style, content) -> {
                    var color = style.getColor();
                    if (color != null) {
                        int rgb = color.getValue() & 0xFFFFFF;
                        // Gray shades and black = inactive; everything else (incl. white) = active
                        if (rgb != 0xAAAAAA && rgb != 0x555555 && rgb != 0x000000 && rgb != 0xDDDDDD) {
                            hasNonGray[0] = true;
                        }
                    }
                    return Optional.empty();
                }, Style.EMPTY);
                if (hasNonGray[0]) {
                    String text = line.getString().toLowerCase().trim();
                    DonutAH.LOGGER.info("[DonutAH] Filter lore non-gray line: '{}'", text);
                    if (text.contains("lowest price"))    return SortMode.LOWEST_PRICE;
                    if (text.contains("highest price"))   return SortMode.HIGHEST_PRICE;
                    if (text.contains("last listed"))     return SortMode.LAST_LISTED;
                    if (text.contains("recently listed")) return SortMode.RECENTLY_LISTED;
                }
            }
        } catch (Throwable e) {
            DonutAH.LOGGER.warn("[DonutAH] parseSortFromFilterLore: {}", e.getMessage());
            if (BuildConstants.STAGING) {
                DebugWebhook.send("❌ parseSortFromFilterLore exception: `" + e.getMessage() + "`");
            }
        }
        return SortMode.UNKNOWN;
    }

    private static SortMode parseSortLore(List<String> lore) {
        for (String line : lore) {
            if (line.contains("§a") || line.contains("§2") || line.contains("§b")) {
                String lower = stripFormatting(line).toLowerCase();
                if (lower.contains("lowest price"))    return SortMode.LOWEST_PRICE;
                if (lower.contains("highest price"))   return SortMode.HIGHEST_PRICE;
                if (lower.contains("last listed"))     return SortMode.LAST_LISTED;
                if (lower.contains("recently listed")) return SortMode.RECENTLY_LISTED;
            }
        }
        return SortMode.UNKNOWN;
    }

    private static void scanAndSubmit(ChestMenu handler, SortMode mode) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        String scannerUUID = client.player.getStringUUID();
        long timestamp = System.currentTimeMillis();

        // Track items seen this scan — only take the FIRST (cheapest) occurrence of each item name
        Set<String> seen = new HashSet<>();
        JsonArray items = new JsonArray();
        // Pending cache updates — applied only after a successful POST
        Map<String, DonutAH.CachedListing> cacheUpdate = new HashMap<>();

        for (int slot = 0; slot < 54; slot++) {
            ItemStack stack = handler.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            String itemName = stack.getHoverName().getString();
            if (isServerUiStack(stack)) continue;

            List<String> lore = getLoreStrings(stack);
            ParsedListing listing = parseListing(itemName, lore, stack.getCount());
            if (listing == null) {
                DonutAH.LOGGER.info("[DonutAH] Slot {} '{}' — could not parse lore: {}", slot, itemName, lore);
                continue;
            }

            // Use the ENCHANTMENTS data component as the authoritative source —
            // lore-based extraction misses enchants stored in the component, not in LORE.
            listing.enchantments = getStackEnchantments(stack);
            Collections.sort(listing.enchantments);

            // Dedup key includes enchantments — different enchant combos are different items
            String enchantKey = String.join(",", listing.enchantments);
            String itemKey = itemName.toLowerCase() + "|" + enchantKey;

            // Skip all but the first (cheapest) occurrence of this item+enchant combo on the page
            if (!seen.add(itemKey)) continue;

            // Per-unit price: divide total listing price by stack quantity
            double pricePerUnit = listing.quantity > 1
                    ? listing.price / listing.quantity
                    : listing.price;

            // Skip if this exact listing was already submitted this session (no change)
            DonutAH.CachedListing cached = DonutAH.scanCache.get(itemKey);
            if (cached != null
                    && cached.seller != null
                    && cached.seller.equalsIgnoreCase(listing.seller)
                    && Math.abs(cached.price - pricePerUnit) < 0.01) {
                DonutAH.LOGGER.info("[DonutAH] Skipping '{}' — already submitted this session (${}/unit, seller={})",
                        itemName, pricePerUnit, listing.seller);
                continue;
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("item_name", listing.itemName);
            if (!listing.enchantments.isEmpty()) {
                JsonArray enchArr = new JsonArray();
                listing.enchantments.forEach(enchArr::add);
                entry.add("enchantments", enchArr);
            }
            entry.addProperty("price",     pricePerUnit);
            entry.addProperty("worth",     listing.worth);
            entry.addProperty("quantity",  listing.quantity);
            entry.addProperty("seller",    listing.seller);
            entry.addProperty("sort_mode", mode.name());
            entry.addProperty("is_first",  true);
            items.add(entry);

            cacheUpdate.put(itemKey, new DonutAH.CachedListing(pricePerUnit, listing.seller));
        }

        DonutAH.LOGGER.info("[DonutAH] {} new/changed listings to submit", items.size());

        if (items.size() == 0) {
            if (BuildConstants.STAGING) {
                DebugWebhook.send("✔️ AH scanned — **" + seen.size() + "** unique listings found, all already cached (nothing new)");
            }
            return;
        }

        if (BuildConstants.STAGING) {
            StringBuilder sb = new StringBuilder("📤 Submitting **").append(items.size()).append("** new listings:\n```\n");
            for (int i = 0; i < items.size(); i++) {
                if (i >= 20) {
                    sb.append("  ...and ").append(items.size() - 20).append(" more\n");
                    break;
                }
                com.google.gson.JsonObject obj = items.get(i).getAsJsonObject();
                sb.append("  ").append(obj.get("item_name").getAsString());
                if (obj.has("enchantments")) {
                    com.google.gson.JsonArray ea = obj.get("enchantments").getAsJsonArray();
                    sb.append(" [");
                    for (int j = 0; j < ea.size(); j++) {
                        if (j > 0) sb.append(", ");
                        sb.append(ea.get(j).getAsString());
                    }
                    sb.append("]");
                }
                sb.append(" — $").append(String.format("%.0f", obj.get("price").getAsDouble())).append("/unit\n");
            }
            sb.append("```");
            DebugWebhook.send(sb.toString());
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("scanner_uuid", scannerUUID);
        payload.addProperty("sort_mode",    mode.name());
        payload.addProperty("timestamp",    timestamp);
        payload.add("items", items);

        // Update cache only on successful POST
        final int submitted = items.size();
        ApiClient.postScan(payload.toString(), () -> {
            DonutAH.listingCache.putAll(cacheUpdate);  // keep tooltips up to date
            DonutAH.scanCache.putAll(cacheUpdate);     // prevent re-submission this session
            DonutAH.sessionScans += submitted;
            DonutAH.LOGGER.info("[DonutAH] Cache updated with {} items", cacheUpdate.size());
            if (BuildConstants.STAGING) {
                DebugWebhook.send("✅ POST /scan accepted — **" + submitted + "** items | session total: **" + DonutAH.sessionScans + "**");
            }
        });
    }

    private static ParsedListing parseListing(String name, List<String> lore, int count) {
        String seller = null;
        double price = -1, worth = -1;
        List<String> enchantments = new ArrayList<>();
        boolean priceSeen = false;

        for (String line : lore) {
            String clean = stripFormatting(line).trim();
            if (clean.isEmpty()) continue;
            if (clean.startsWith("Price:")) {
                price = parsePrice(clean.substring(6).trim());
                priceSeen = true;
            } else if (clean.startsWith("Worth:")) {
                worth = parsePrice(clean.substring(6).trim());
            } else if (clean.startsWith("Seller:")) {
                seller = clean.substring(7).trim();
            } else if (clean.startsWith("$") && price < 0) {
                // New AH format: price shown directly as "$899" without "Price:" prefix
                double p = parsePrice(clean);
                if (p >= 0) { price = p; priceSeen = true; }
            } else if (!priceSeen) {
                // Lines before price are enchantments (e.g. "Sharpness IV", "Mending")
                enchantments.add(clean);
            }
        }

        if (price < 0) return null;
        if (seller == null) seller = "";
        Collections.sort(enchantments);  // sort for consistent dedup key regardless of lore order
        ParsedListing pl = new ParsedListing();
        pl.itemName     = name;
        pl.price        = price;
        pl.worth        = worth >= 0 ? worth : (count > 0 ? price / count : price);
        pl.quantity     = count;
        pl.seller       = seller;
        pl.enchantments = enchantments;
        return pl;
    }

    public static double parsePrice(String raw) {
        Matcher m = PRICE_PATTERN.matcher(raw.trim());
        if (!m.find()) return -1;
        double value;
        try { value = Double.parseDouble(m.group(1).replace(",", "")); }
        catch (NumberFormatException e) { return -1; }
        String s = m.group(2);
        if (s != null) switch (s.toUpperCase()) {
            case "K": value *= 1_000; break;
            case "M": value *= 1_000_000; break;
            case "B": value *= 1_000_000_000; break;
            case "T": value *= 1_000_000_000_000L; break;
            case "Q": value *= 1_000_000_000_000_000L; break;
        }
        return value;
    }

    public static List<String> getLoreStrings(ItemStack stack) {
        List<String> result = new ArrayList<>();
        try {
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) return result;
            for (Component line : lore.lines()) {
                result.add(toSectionCodedString(line));
            }
        } catch (Throwable e) {
            DonutAH.LOGGER.warn("[DonutAH] getLoreStrings: {}", e.getMessage());
        }
        return result;
    }

    // Reconstruct a §-coded string from a Text object's style tree,
    // so that colour-based detection (e.g. §a = active sort mode) keeps working.
    private static String toSectionCodedString(Component text) {
        StringBuilder sb = new StringBuilder();
        text.visit((style, content) -> {
            var color = style.getColor();
            if (color != null) {
                String code = rgbToSectionCode(color.getValue());
                if (code != null) sb.append(code);
            }
            sb.append(content);
            return Optional.empty();
        }, Style.EMPTY);
        return sb.length() > 0 ? sb.toString() : text.getString();
    }

    private static String rgbToSectionCode(int rgb) {
        switch (rgb & 0xFFFFFF) {
            case 0x000000: return "§0";
            case 0x0000AA: return "§1";
            case 0x00AA00: return "§2";
            case 0x00AAAA: return "§3";
            case 0xAA0000: return "§4";
            case 0xAA00AA: return "§5";
            case 0xFFAA00: return "§6";
            case 0xAAAAAA: return "§7";
            case 0x555555: return "§8";
            case 0x5555FF: return "§9";
            case 0x55FF55: return "§a";
            case 0x55FFFF: return "§b";
            case 0xFF5555: return "§c";
            case 0xFF55FF: return "§d";
            case 0xFFFF55: return "§e";
            case 0xFFFFFF: return "§f";
            default:       return null;
        }
    }

    public static String stripFormatting(String s) {
        return s.replaceAll("§[0-9a-fk-or]", "");
    }

    // Converts Unicode small caps ("tiny text") to ASCII lowercase
    // e.g. "ꜱᴏʀᴛ" → "sort"
    public static String normalizeSmallCaps(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u1D00': sb.append('a'); break; // ᴀ
                case '\u0299': sb.append('b'); break; // ʙ
                case '\u1D04': sb.append('c'); break; // ᴄ
                case '\u1D05': sb.append('d'); break; // ᴅ
                case '\u1D07': sb.append('e'); break; // ᴇ
                case '\uA730': sb.append('f'); break; // ꜰ (Latin small capital F)
                case '\u0493': sb.append('f'); break; // ғ (approximate)
                case '\u0262': sb.append('g'); break; // ɢ
                case '\u029C': sb.append('h'); break; // ʜ
                case '\u026A': sb.append('i'); break; // ɪ
                case '\u1D0A': sb.append('j'); break; // ᴊ
                case '\u1D0B': sb.append('k'); break; // ᴋ
                case '\u029F': sb.append('l'); break; // ʟ
                case '\u1D0D': sb.append('m'); break; // ᴍ
                case '\u0274': sb.append('n'); break; // ɴ
                case '\u1D0F': sb.append('o'); break; // ᴏ
                case '\u1D18': sb.append('p'); break; // ᴘ
                case '\u0280': sb.append('r'); break; // ʀ
                case '\uA731': sb.append('s'); break; // ꜱ
                case '\u0455': sb.append('s'); break; // ѕ (Cyrillic dze lookalike)
                case '\u1D1B': sb.append('t'); break; // ᴛ
                case '\u1D1C': sb.append('u'); break; // ᴜ
                case '\u1D20': sb.append('v'); break; // ᴠ
                case '\u1D21': sb.append('w'); break; // ᴡ
                case '\u028F': sb.append('y'); break; // ʏ
                case '\u1D22': sb.append('z'); break; // ᴢ
                default: sb.append(Character.toLowerCase(c)); break;
            }
        }
        return sb.toString();
    }

    private static boolean isControlItem(String name) {
        String l = normalizeSmallCaps(stripFormatting(name)).trim();
        return l.equals("sort") || l.equals("filter") || l.equals("search")
            || l.equals("next") || l.equals("previous") || l.equals("back")
            || l.equals("close") || l.equals("next page") || l.equals("previous page")
            || l.equals("your items") || l.equals("shard shop") || l.equals("auction")
            || l.equals("quick buy") || l.equals("quick sell") || l.equals("edit")
            || l.equals("empty");
    }

    // True for any server UI/control button: known control names, or lore that is a
    // "Click to ..." instruction (all new-AH control items carry one, real listings never do).
    // Used by the scanner AND by TooltipHandler so Filter/Next Page/Quick Buy etc.
    // never get a DonutAH price line.
    public static boolean isServerUiStack(ItemStack stack) {
        try {
            if (isControlItem(stack.getHoverName().getString())) return true;
            for (String line : getLoreStrings(stack)) {
                String clean = normalizeSmallCaps(stripFormatting(line)).trim();
                if (clean.startsWith("click to")) return true;
            }
        } catch (Throwable e) {
            DonutAH.LOGGER.warn("[DonutAH] isServerUiStack: {}", e.getMessage());
        }
        return false;
    }

    // Reads real enchantments from the item's ENCHANTMENTS data component.
    // This works for all items regardless of what the server puts in the LORE component.
    public static List<String> getStackEnchantments(ItemStack stack) {
        List<String> result = new ArrayList<>();
        try {
            // 1. Regular enchanted items use ENCHANTMENTS component
            ItemEnchantments enchComp = stack.get(DataComponents.ENCHANTMENTS);
            // 2. Real enchanted books in inventory use STORED_ENCHANTMENTS component
            if (enchComp == null || enchComp.isEmpty())
                enchComp = stack.get(DataComponents.STORED_ENCHANTMENTS);

            if (enchComp != null && !enchComp.isEmpty()) {
                for (var enchEntry : enchComp.keySet()) {
                    String name = enchEntry.value().description().getString();
                    int level = enchComp.getLevel(enchEntry);
                    result.add(level == 1 ? name : name + " " + toRoman(level));
                }
                return result;
            }

            // 3. Fallback: DonutSMP AH sends enchanted books as display items — the
            //    STORED_ENCHANTMENTS component is NOT populated by their AH system.
            //    Instead, enchantments appear as lore lines before "Price:".
            //    We only apply this fallback when the item is clearly an AH slot item
            //    (lore contains a "Price:" line), so inventory items are never affected.
            return getEnchantmentsFromAHLore(stack);

        } catch (Throwable e) {
            DonutAH.LOGGER.warn("[DonutAH] getStackEnchantments: {}", e.getMessage());
        }
        return result;
    }

    // Extracts enchantment names from AH lore lines that appear before the price line.
    // Returns empty if no price line is found (i.e. the item is not an AH slot item).
    private static List<String> getEnchantmentsFromAHLore(ItemStack stack) {
        List<String> enchants = new ArrayList<>();
        List<String> lore = getLoreStrings(stack);

        // Confirm this is an AH item: old format has "Price:", new format has a bare "$ xxx" line
        boolean hasPriceLine = lore.stream().anyMatch(l -> {
            String clean = stripFormatting(l).trim();
            return clean.startsWith("Price:") || (clean.startsWith("$") && parsePrice(clean) >= 0);
        });
        if (!hasPriceLine) return enchants;

        // Collect every lore line before the price line — those are the enchantment names
        for (String line : lore) {
            String clean = stripFormatting(line).trim();
            if (clean.startsWith("Price:") || clean.startsWith("Seller:")
                    || clean.startsWith("Worth:") || clean.startsWith("Time Left:")
                    || (clean.startsWith("$") && parsePrice(clean) >= 0)) break;
            if (!clean.isEmpty()) enchants.add(clean);
        }
        return enchants;
    }

    private static String toRoman(int n) {
        switch (n) {
            case 1: return "I";   case 2: return "II";   case 3: return "III";
            case 4: return "IV";  case 5: return "V";    case 6: return "VI";
            case 7: return "VII"; case 8: return "VIII"; case 9: return "IX";
            case 10: return "X";  default: return String.valueOf(n);
        }
    }

    public static class ParsedListing {
        public String itemName, seller;
        public double price, worth;
        public int quantity;
        public List<String> enchantments = new ArrayList<>();
    }
}
