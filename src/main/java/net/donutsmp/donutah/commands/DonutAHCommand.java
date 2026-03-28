package net.donutsmp.donutah.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.donutsmp.donutah.BuildConstants;
import net.donutsmp.donutah.DonutAH;
import net.donutsmp.donutah.DonutAHConfig;
import net.donutsmp.donutah.TooltipHandler;
import net.donutsmp.donutah.network.ApiClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DonutAHCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = ClientCommands.literal("donutah")
            .executes(ctx -> { sendHelp(ctx.getSource()); return 1; })
            .then(ClientCommands.literal("reload")
                .executes(ctx -> { reload(ctx.getSource()); return 1; }))
            .then(ClientCommands.literal("settings")
                .executes(ctx -> { openSettings(); return 1; }))
            .then(ClientCommands.literal("top")
                .executes(ctx -> { lookupTop(ctx.getSource()); return 1; }))
            .then(ClientCommands.argument("itemname", StringArgumentType.greedyString())
                .executes(ctx -> {
                    lookupItem(ctx.getSource(), StringArgumentType.getString(ctx, "itemname"));
                    return 1;
                }));

        if (BuildConstants.STAGING) {
            root.then(ClientCommands.literal("break")
                .executes(ctx -> { runBreakTest(ctx.getSource()); return 1; }));
        }

        dispatcher.register(root);
    }

    private static void openSettings() {
        Minecraft.getInstance().execute(() ->
            Minecraft.getInstance().setScreen(
                DonutAHConfig.createScreen(Minecraft.getInstance().screen)));
    }

    private static void reload(FabricClientCommandSource source) {
        if (!DonutAH.onTargetServer) {
            source.sendFeedback(Component.literal("§c✗ §7DonutAH only works on DonutSMP."));
            return;
        }
        source.sendFeedback(Component.literal("§8[§b⬡§8] §7Reloading…"));
        DonutAH.listingCache.clear();
        TooltipHandler.clearCache();
        Thread.ofVirtual().start(() -> {
            List<ApiClient.LatestListing> latest = ApiClient.fetchLatest();
            for (ApiClient.LatestListing ll : latest) {
                if (ll.item_name != null && ll.seller != null) {
                    DonutAH.listingCache.put(ll.getCacheKey(), new DonutAH.CachedListing(ll.price, ll.seller));
                }
            }
            java.util.Set<String> bl = ApiClient.fetchBlacklist();
            DonutAH.blacklistedItems.clear();
            DonutAH.blacklistedItems.addAll(bl);
            java.util.Set<String> mu = ApiClient.fetchMuted();
            DonutAH.mutedItems.clear();
            DonutAH.mutedItems.addAll(mu);
            DonutAH.mutedItems.addAll(DonutAH.localMuted);
            java.util.Map<String, String> labels = ApiClient.fetchLabels();
            DonutAH.itemLabels.clear();
            DonutAH.itemLabels.putAll(labels);
            java.util.Map<String, Double> sp = ApiClient.fetchStaticPrices();
            DonutAH.staticPrices.clear();
            DonutAH.staticPrices.putAll(sp);
            java.util.Set<String> mg = ApiClient.fetchMutedGuis();
            DonutAH.mutedGuis.clear();
            DonutAH.mutedGuis.addAll(mg);
            TooltipHandler.clearCache();
            Minecraft.getInstance().execute(() ->
                source.sendFeedback(Component.literal(
                    "§8[§b⬡§8] §aDone§7 — §f" + latest.size() +
                    " §7listings, §f" + bl.size() + " §7blacklisted, §f" + mu.size() + " §7muted, §f" + labels.size() + " §7labels, §f" + sp.size() + " §7shop prices, §f" + mg.size() + " §7muted GUIs, tooltip cache cleared.")));
        });
    }

    private static void lookupItem(FabricClientCommandSource source, String input) {
        if (!DonutAH.onTargetServer) {
            source.sendFeedback(Component.literal("§c✗ §7DonutAH only works on DonutSMP."));
            return;
        }
        source.sendFeedback(Component.literal("§8[§b⬡§8] §7Fetching §f" + input + "§7..."));

        Thread.ofVirtual().start(() -> {
            String[] words = input.trim().split("\\s+");
            List<ApiClient.VariantResponse> variants = null;
            String itemName = null;
            String enchantFilter = null;

            // Try progressively shorter item names (longest first)
            boolean connectionFailed = false;
            for (int i = words.length; i >= 1; i--) {
                String candidate = String.join(" ", Arrays.copyOfRange(words, 0, i));
                List<ApiClient.VariantResponse> result;
                try {
                    result = ApiClient.fetchVariants(candidate);
                } catch (Exception e) {
                    connectionFailed = true;
                    break;
                }
                if (!result.isEmpty()) {
                    variants = result;
                    itemName = candidate;
                    if (i < words.length) {
                        enchantFilter = String.join(" ", Arrays.copyOfRange(words, i, words.length));
                    }
                    break;
                }
            }

            if (variants == null || variants.isEmpty()) {
                final String fi = input;
                final boolean isConnFail = connectionFailed;
                final List<String> suggestions = isConnFail ? new ArrayList<>() : getSuggestions(input);
                Minecraft.getInstance().execute(() -> {
                    if (isConnFail) {
                        source.sendFeedback(Component.literal(
                            "§8[§b⬡§8] §eConnection issue§7 — couldn't reach the server. Try again."));
                    } else if (!suggestions.isEmpty()) {
                        MutableComponent msg = Component.literal("§8[§b⬡§8] §cNo data for §f\"" + fi + "\"§c. Did you mean: ");
                        for (int i = 0; i < suggestions.size(); i++) {
                            if (i > 0) msg.append(Component.literal("§7, "));
                            String sug = suggestions.get(i);
                            msg.append(Component.literal("§e" + sug).setStyle(Style.EMPTY
                                .withClickEvent(new ClickEvent.RunCommand("/donutah " + sug))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("§7Click to look up §f" + sug)))));
                        }
                        msg.append(Component.literal("§7?"));
                        source.sendFeedback(msg);
                    } else {
                        source.sendFeedback(Component.literal(
                            "§8[§b⬡§8] §cNo data for §f\"" + fi + "\"§c. Check the spelling."));
                    }
                });
                return;
            }

            // Apply enchant filter if provided
            if (enchantFilter != null && !enchantFilter.isEmpty()) {
                final String filter = enchantFilter;
                List<ApiClient.VariantResponse> filtered = variants.stream()
                    .filter(v -> matchesEnchantFilter(v, filter))
                    .collect(Collectors.toList());
                if (!filtered.isEmpty()) {
                    variants = filtered;
                } else {
                    final String fn = enchantFilter, fin = itemName, orig = input;
                    final List<String> suggestions = getSuggestions(orig);
                    Minecraft.getInstance().execute(() -> {
                        if (!suggestions.isEmpty()) {
                            MutableComponent msg = Component.literal("§8[§b⬡§8] §cNo variant of §f\"" + fin + "\"§c matches §f\"" + fn + "\"§c. Did you mean: ");
                            for (int i = 0; i < suggestions.size(); i++) {
                                if (i > 0) msg.append(Component.literal("§7, "));
                                String sug = suggestions.get(i);
                                msg.append(Component.literal("§e" + sug).setStyle(Style.EMPTY
                                    .withClickEvent(new ClickEvent.RunCommand("/donutah " + sug))
                                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("§7Click to look up §f" + sug)))));
                            }
                            msg.append(Component.literal("§7?"));
                            source.sendFeedback(msg);
                        } else {
                            source.sendFeedback(Component.literal(
                                "§8[§b⬡§8] §cNo variant of §f\"" + fin + "\"§c matches §f\"" + fn + "\"§c."));
                        }
                    });
                    return;
                }
            } else {
                // No filter — prefer unenchanted variant; note enchanted ones exist
                List<ApiClient.VariantResponse> unenchanted = variants.stream()
                    .filter(v -> v.enchantments == null || v.enchantments.isEmpty())
                    .collect(Collectors.toList());
                long enchantedCount = variants.stream()
                    .filter(v -> v.enchantments != null && !v.enchantments.isEmpty())
                    .count();
                if (!unenchanted.isEmpty()) {
                    variants = unenchanted;
                    if (enchantedCount > 0) {
                        final long ec = enchantedCount;
                        final String fin = itemName;
                        Minecraft.getInstance().execute(() ->
                            source.sendFeedback(Component.literal(
                                "§8[§b⬡§8] §7" + ec + " enchanted variant" + (ec == 1 ? "" : "s") +
                                " — try §f/donutah " + fin + " §e<enchants>§7 to search")));
                    }
                }
                // If no unenchanted variant exists, fall through and show all (enchanted only item)
            }

            final List<ApiClient.VariantResponse> finalVariants = variants;
            final String finalItemName = itemName;
            String msg = buildMessage(finalItemName, finalVariants);
            Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal(msg)));
        });
    }

    private static List<String> getSuggestions(String input) {
        String[] words = input.trim().toLowerCase().split("\\s+");
        String inputLower = input.trim().toLowerCase();
        java.util.Map<String, Integer> scores = new java.util.LinkedHashMap<>();
        for (String key : DonutAH.listingCache.keySet()) {
            String name = key.contains("|") ? key.substring(0, key.indexOf('|')) : key;
            if (scores.containsKey(name)) continue;
            if (name.equals(inputLower)) continue; // skip exact match of input
            boolean allMatch = true;
            for (String word : words) {
                if (!name.contains(word)) { allMatch = false; break; }
            }
            if (allMatch) scores.put(name, words.length);
        }
        return scores.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(3)
            .map(e -> toTitleCase(e.getKey()))
            .collect(Collectors.toList());
    }

    private static final java.util.Set<String> LOWER_WORDS = new java.util.HashSet<>(
        java.util.Arrays.asList("of", "the", "a", "an", "and", "or", "in", "on", "at", "to", "for"));

    private static String toTitleCase(String s) {
        String[] parts = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (sb.length() > 0) sb.append(' ');
            if (!p.isEmpty()) {
                if (i > 0 && LOWER_WORDS.contains(p.toLowerCase())) {
                    sb.append(p.toLowerCase());
                } else {
                    sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
                }
            }
        }
        return sb.toString();
    }

    private static boolean matchesEnchantFilter(ApiClient.VariantResponse v, String filter) {
        if (v.enchantments == null || v.enchantments.isEmpty()) return false;
        String label = v.getLabel().toLowerCase();
        String normalized = normalizeRoman(filter.toLowerCase());
        for (String word : normalized.split("\\s+")) {
            if (!word.isEmpty() && !label.contains(word)) return false;
        }
        return true;
    }

    private static String normalizeRoman(String s) {
        Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, toRoman(n));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String toRoman(int n) {
        String[] r = {"", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};
        return (n >= 1 && n <= 10) ? r[n] : String.valueOf(n);
    }

    private static String buildMessage(String itemName, List<ApiClient.VariantResponse> variants) {
        String sep = "§8§m                          §r";

        if (variants.size() == 1) {
            ApiClient.StatsResponse stats = ApiClient.fetchStats(itemName);
            ApiClient.VariantResponse v = variants.get(0);
            String enchantLine = v.enchantments != null
                ? "§8 [§7" + v.getLabel() + "§8]\n" : "";

            // Collect data lines so box-drawing ┌│└ always looks right
            List<String> lines = new ArrayList<>();
            String label24h;

            if (stats != null) {
                label24h = "§724h avg  §a$" + fmt(stats.daily_avg);
                lines.add(label24h);
                if (DonutAHConfig.cmdShowRange)
                    lines.add("§7  range  §e$" + fmt(stats.daily_min) + " §8→ §c$" + fmt(stats.daily_max));
                if (DonutAHConfig.cmdShowWeeklyAvg)
                    lines.add("§7 7d avg  §a$" + fmt(stats.weekly_avg));
                if (DonutAHConfig.cmdShowTrend)
                    lines.add("§7  trend  " + trendStr(stats.price_change_pct));
                if (DonutAHConfig.cmdShowVolume)
                    lines.add("§7    vol  §f" + stats.daily_volume + "§7/day  §f" + stats.weekly_volume + "§7/week");
            } else {
                // No recent activity — fall back to all-time variant data
                lines.add("§7all-time avg  §a$" + fmt(v.avg));
                if (DonutAHConfig.cmdShowRange)
                    lines.add("§7      range  §e$" + fmt(v.min) + " §8→ §c$" + fmt(v.max));
                if (DonutAHConfig.cmdShowVolume)
                    lines.add("§7        vol  §f" + v.volume + "§7 scans total");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n").append(sep).append("\n");
            sb.append(" §b§l⬡ §r§f§l").append(stats != null ? stats.item_name : itemName).append("\n");
            sb.append(enchantLine);
            if (stats == null) sb.append("§8 §o(no activity in last 24h)\n");
            sb.append(sep).append("\n");

            for (int i = 0; i < lines.size(); i++) {
                String box = (i == 0) ? " §8┌ " : (i == lines.size() - 1 ? " §8└ " : " §8│ ");
                sb.append(box).append(lines.get(i)).append("\n");
            }
            sb.append(sep);
            return sb.toString();

        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("\n").append(sep).append("\n");
            sb.append(" §b§l⬡ §r§f§l").append(itemName)
              .append(" §8[§7").append(variants.size()).append(" variants§8]\n");
            sb.append(sep).append("\n");

            for (ApiClient.VariantResponse v : variants) {
                String label = truncate(v.getLabel(), 28);
                sb.append(" §8▸ §f").append(label).append("\n");
                sb.append("    §7avg §a$").append(fmt(v.avg));
                if (DonutAHConfig.cmdShowRange)
                    sb.append("  §7range §e$").append(fmt(v.min)).append("§8→§c$").append(fmt(v.max));
                if (DonutAHConfig.cmdShowVolume)
                    sb.append("  §7vol §f").append(v.volume).append("§7/day");
                sb.append("\n");
            }

            sb.append(sep);
            return sb.toString();
        }
    }

    private static void lookupTop(FabricClientCommandSource source) {
        if (!DonutAH.onTargetServer) {
            source.sendFeedback(Component.literal("§c✗ §7DonutAH only works on DonutSMP."));
            return;
        }
        source.sendFeedback(Component.literal("§8[§b⬡§8] §7Fetching top flip opportunities..."));
        Thread.ofVirtual().start(() -> {
            List<ApiClient.TopItem> items = ApiClient.fetchTop(10);
            String sep = "§8§m                          §r";
            if (items.isEmpty()) {
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§8[§b⬡§8] §cNo flip data yet — more scans needed.")));
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("\n").append(sep).append("\n");
            sb.append(" §b§l⬡ §r§f§lTop Flip Opportunities\n");
            sb.append(sep).append("\n");
            for (int i = 0; i < items.size(); i++) {
                ApiClient.TopItem t = items.get(i);
                sb.append(" §8").append(i + 1).append(". §f").append(truncate(t.getLabel(), 30)).append("\n");
                sb.append("    §7avg §a$").append(fmt(t.avg))
                  .append("  §7spread §e").append(t.spread_pct).append("%")
                  .append("  §7vol §f").append(t.volume).append("\n");
            }
            sb.append(sep);
            final String msg = sb.toString();
            Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal(msg)));
        });
    }

    private static String trendStr(double pct) {
        if (pct > 0) return String.format("§a▲ +%.1f%%§7 vs yesterday", pct);
        if (pct < 0) return String.format("§c▼ %.1f%%§7 vs yesterday", pct);
        return "§7Stable";
    }

    private static void sendHelp(FabricClientCommandSource source) {
        StringBuilder help = new StringBuilder();
        help.append("\n§8[§b⬡§8] §b§lDonutAH §8│ §7Commands:\n");
        if (BuildConstants.STAGING) help.append("§8  ├ §c§o[STAGING BUILD]\n");
        help.append("§8  ├ §f/donutah §e<item> §8— §7look up price & variants\n");
        help.append("§8  ├ §f/donutah §e<item> §b<enchants> §8— §7filter by enchants §8(e.g. §7sharpness 5 knockback 1§8)\n");
        help.append("§8  ├ §f/donutah top §8— §7top flip opportunities\n");
        help.append("§8  ├ §f/donutah reload §8— §7clear all caches & resync\n");
        if (BuildConstants.STAGING) {
            help.append("§8  ├ §f/donutah settings §8— §7open settings screen\n");
            help.append("§8  └ §f/donutah §cbreak §8— §7run staging test suite");
        } else {
            help.append("§8  └ §f/donutah settings §8— §7open settings screen");
        }
        source.sendFeedback(Component.literal(help.toString()));
    }

    private static void runBreakTest(FabricClientCommandSource source) {
        String sep = "§b§m                              §r";
        source.sendFeedback(Component.literal("\n" + sep + "\n §b§l⬡ §f§lDonutAH Staging Break Test\n" + sep));
        source.sendFeedback(Component.literal(" §7Running auto checks..."));

        Thread.ofVirtual().start(() -> {
            String localBase = ApiClient.STAGING_LOCAL_BASE;
            String vpsBase   = ApiClient.STAGING_BACKUP_BASE;

            boolean homeOk = ApiClient.isReachable(localBase, 2000);
            boolean vpsOk  = ApiClient.isReachable(vpsBase,  3000);

            // GET /api/latest
            String latestBody = doGetRaw(localBase + "/latest", 5000);
            boolean latestOk  = latestBody != null;
            int latestCount   = latestOk ? (int) latestBody.chars().filter(c -> c == '{').count() : 0;

            // GET /api/dashboard
            boolean dashOk = doGetRaw(localBase + "/dashboard", 5000) != null;

            // GET /api/static-prices
            String staticBody  = doGetRaw(localBase + "/static-prices", 5000);
            boolean staticOk   = staticBody != null;
            int staticCount    = staticOk ? (int) staticBody.chars().filter(c -> c == '{').count() : 0;

            // GET /api/top
            boolean topOk = doGetRaw(localBase + "/top", 5000) != null;

            final boolean fHome = homeOk, fVps = vpsOk, fLatest = latestOk, fDash = dashOk, fStatic = staticOk, fTop = topOk;
            final int fCount = latestCount, fSCount = staticCount;

            Minecraft.getInstance().execute(() -> {
                String ok  = "§a✓";
                String err = "§c✗";
                source.sendFeedback(Component.literal(
                    " §7Auto checks:\n" +
                    " §8┌ §fHome staging       " + (fHome   ? ok + " §7(YOUR_LAN_IP:3002)" : err + " §cUNREACHABLE") + "\n" +
                    " §8│ §fVPS staging        " + (fVps    ? ok + " §7(YOUR_VPS_IP:3003)"   : err + " §cUNREACHABLE") + "\n" +
                    " §8│ §f/api/latest        " + (fLatest ? ok + " §7(" + fCount + " items)"  : err + " §cFAILED") + "\n" +
                    " §8│ §f/api/dashboard     " + (fDash   ? ok                               : err + " §cFAILED") + "\n" +
                    " §8│ §f/api/static-prices " + (fStatic ? ok + " §7(" + fSCount + " entries)" : err + " §cFAILED") + "\n" +
                    " §8└ §f/api/top           " + (fTop    ? ok                               : err + " §cFAILED")
                ));
                source.sendFeedback(Component.literal(
                    "\n §7Manual §8(full list in §bTESTING.md§8):\n" +
                    " §8[§f1§8] §eAH scan §7— open AH page 1, lowest price → scan fires\n" +
                    " §8[§f2§8] §eItem tooltip §7— hover item → price shows\n" +
                    " §8[§f3§8] §eShulker tooltip §7— hover shulker → contents value shows\n" +
                    " §8[§f4§8] §e/donutah §f<item> §7— price lookup works\n" +
                    " §8[§f5§8] §e/donutah top §7— flip list shows (may say no data if DB empty)\n" +
                    " §8[§f6§8] §e/donutah settings §7— config screen opens (check Trend/Volume/Inv key)\n" +
                    " §8[§f7§8] §e/donutah reload §7— cache clears & resyncs (shows shop prices count)\n" +
                    " §8[§f8§8] §eDashboard §7— http://YOUR_LAN_IP:3002\n" +
                    " §8[§f9§8] §eShop price tooltip §7— muted item with static price shows §fShop: $X\n" +
                    " §8[§f10§8] §eInventory value key §7— press V in inventory → total shows in chat\n" +
                    " §8[§f11§8] §eL-key price editor §7— hover item, press L → Shop Price section visible\n" +
                    sep
                ));
            });
        });
    }

    /** Raw GET — returns response body string or null on any failure. */
    private static String doGetRaw(String url, int timeoutMs) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URI(url).toURL().openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            } finally { conn.disconnect(); }
        } catch (Exception e) { return null; }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static String fmt(double p) {
        if (p <= 0) return "N/A";
        return TooltipHandler.formatPrice(p);
    }
}
