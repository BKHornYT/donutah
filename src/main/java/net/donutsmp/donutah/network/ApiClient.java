package net.donutsmp.donutah.network;

import com.google.gson.Gson;
import net.donutsmp.donutah.BuildConstants;
import net.donutsmp.donutah.DonutAH;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ApiClient {

    private static final Gson GSON = new Gson();
    private static final int TIMEOUT = 8000;
    private static final int HEALTH_TIMEOUT = 3000;
    private static final int LAN_TIMEOUT = 500;
    private static final String API_KEY =
        "YOUR_API_KEY_HERE";

    public static final String LOCAL_BASE   = "http://YOUR_LAN_IP:3000/api";
    public static final String PRIMARY_BASE = "http://YOUR_HOME_SERVER_IP:3000/api";
    public static final String BACKUP_BASE  = "http://YOUR_VPS_IP:3001/api";

    // Staging servers — used when BuildConstants.STAGING = true
    public static final String STAGING_LOCAL_BASE   = "http://YOUR_LAN_IP:3002/api";
    public static final String STAGING_PRIMARY_BASE = "http://YOUR_HOME_SERVER_IP:3002/api";
    public static final String STAGING_BACKUP_BASE  = "http://YOUR_VPS_IP:3003/api";

    /** Returns true if the given base URL responds HTTP 200 on /latest within the given timeout. */
    public static boolean isReachable(String baseUrl, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URI(baseUrl + "/latest").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isReachable(String baseUrl) {
        return isReachable(baseUrl, HEALTH_TIMEOUT);
    }

    /**
     * Returns the best reachable server.
     * In DEV builds, respects devForceServer config; tries LAN IP first (only works on home network).
     * In release builds, goes straight to public primary then backup.
     */
    public static String selectBestServer() {
        if (BuildConstants.STAGING) {
            if (isReachable(STAGING_LOCAL_BASE, LAN_TIMEOUT)) return STAGING_LOCAL_BASE;
            if (isReachable(STAGING_PRIMARY_BASE, HEALTH_TIMEOUT)) return STAGING_PRIMARY_BASE;
            return STAGING_BACKUP_BASE;
        }
        if (BuildConstants.DEV) {
            if (net.donutsmp.donutah.DonutAHConfig.devForceServer == net.donutsmp.donutah.DonutAHConfig.ForceServer.HOME)
                return PRIMARY_BASE;
            if (net.donutsmp.donutah.DonutAHConfig.devForceServer == net.donutsmp.donutah.DonutAHConfig.ForceServer.VPS)
                return BACKUP_BASE;
            if (isReachable(LOCAL_BASE, LAN_TIMEOUT)) return LOCAL_BASE;
            if (isReachable(PRIMARY_BASE, HEALTH_TIMEOUT)) return PRIMARY_BASE;
        }
        return BACKUP_BASE;
    }

    // ── GET helpers ────────────────────────────────────────────────────────

    /** Raw GET to a full URL; returns body string or null if non-200. */
    private static String doGet(String url) throws Exception {
        HttpURLConnection conn = openConn(url, "GET");
        int code = conn.getResponseCode();
        if (code != 200) { conn.disconnect(); return null; }
        String body = readBody(conn);
        conn.disconnect();
        return body;
    }

    /**
     * GET /api{path} using current API_BASE.
     * On connection failure, switches to the best available server and retries once.
     */
    private static String getJson(String path) throws Exception {
        // Build ordered list of servers to try, starting from current API_BASE, then all others
        List<String> servers = new java.util.ArrayList<>();
        servers.add(DonutAH.API_BASE);
        if (BuildConstants.STAGING) {
            for (String s : new String[]{STAGING_LOCAL_BASE, STAGING_PRIMARY_BASE, STAGING_BACKUP_BASE})
                if (!servers.contains(s)) servers.add(s);
        } else if (BuildConstants.DEV) {
            for (String s : new String[]{PRIMARY_BASE, BACKUP_BASE})
                if (!servers.contains(s)) servers.add(s);
        } else {
            if (!servers.contains(BACKUP_BASE)) servers.add(BACKUP_BASE);
        }

        IOException lastErr = null;
        for (String server : servers) {
            try {
                String result = doGet(server + path);
                if (!server.equals(DonutAH.API_BASE)) {
                    DonutAH.LOGGER.info("[DonutAH] Switched API {} -> {} (fallback)", DonutAH.API_BASE, server);
                    if (BuildConstants.STAGING) {
                        net.donutsmp.donutah.DebugWebhook.send(
                            "⚠️ Fallback triggered: `" + DonutAH.API_BASE + "` → `" + server + "`");
                    }
                    // Only permanently update API_BASE when switching to VPS
                    boolean switchedToVps = BuildConstants.STAGING
                        ? server.equals(STAGING_BACKUP_BASE)
                        : server.equals(BACKUP_BASE);
                    if (switchedToVps) DonutAH.API_BASE = server;
                }
                return result;
            } catch (IOException e) {
                lastErr = e;
            }
        }
        if (BuildConstants.STAGING) {
            net.donutsmp.donutah.DebugWebhook.send(
                "❌ All servers unreachable on GET `" + path + "` — " + (lastErr != null ? lastErr.getMessage() : "unknown"));
        }
        throw lastErr != null ? lastErr : new IOException("All servers unreachable");
    }

    // ── POST /api/scan ─────────────────────────────────────────────────────
    public static void postScan(String jsonPayload, Runnable onSuccess) {
        Thread.ofVirtual().start(() -> {
            try {
                HttpURLConnection conn = openConn(DonutAH.API_BASE + "/scan", "POST");
                conn.setRequestProperty("x-api-key", API_KEY);
                writeBody(conn, jsonPayload);
                int code = conn.getResponseCode();
                DonutAH.LOGGER.info("[DonutAH] POST /scan -> HTTP {}", code);
                conn.disconnect();
                if (code >= 200 && code < 300) {
                    onSuccess.run();
                } else {
                    if (BuildConstants.STAGING) {
                        net.donutsmp.donutah.DebugWebhook.send("❌ POST /scan → HTTP **" + code + "** (failed)");
                    }
                }
            } catch (Exception e) {
                DonutAH.LOGGER.warn("[DonutAH] postScan failed: {}", e.getMessage());
                if (BuildConstants.STAGING) {
                    net.donutsmp.donutah.DebugWebhook.send("❌ POST /scan exception: `" + e.getMessage() + "`");
                }
            }
        });
    }

    // ── GET /api/latest ────────────────────────────────────────────────────
    public static List<LatestListing> fetchLatest() {
        try {
            String body = getJson("/latest");
            if (body == null) return Collections.emptyList();
            LatestListing[] arr = GSON.fromJson(body, LatestListing[].class);
            return arr != null ? Arrays.asList(arr) : Collections.emptyList();
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchLatest failed: {}", e.getMessage());
            if (BuildConstants.STAGING) {
                net.donutsmp.donutah.DebugWebhook.send("❌ fetchLatest failed: `" + e.getMessage() + "` (tooltips will be empty)");
            }
            return Collections.emptyList();
        }
    }

    // ── GET /api/price/:item ───────────────────────────────────────────────
    public static PriceResponse fetchPrice(String itemName, List<String> enchantments) {
        try {
            String enchStr = enchantments != null ? String.join(",", enchantments) : "";
            String body = getJson("/price/" + encode(itemName) + "?enchants=" + encode(enchStr));
            if (body == null) return null;
            return GSON.fromJson(body, PriceResponse.class);
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchPrice failed: {}", e.getMessage());
            return null;
        }
    }

    // ── GET /api/variants/:item ────────────────────────────────────────────
    public static List<VariantResponse> fetchVariants(String itemName) throws Exception {
        // Note: throws IOException if all servers unreachable (connection failure)
        // Returns empty list if server responded with non-200 (item not found / no recent data)
        String body = getJson("/variants/" + encode(itemName));
        if (body == null) return Collections.emptyList();
        VariantResponse[] arr = GSON.fromJson(body, VariantResponse[].class);
        return arr != null ? Arrays.asList(arr) : Collections.emptyList();
    }

    // ── GET /api/stats/:item ───────────────────────────────────────────────
    public static StatsResponse fetchStats(String itemName) {
        try {
            String body = getJson("/stats/" + encode(itemName));
            if (body == null) return null;
            return GSON.fromJson(body, StatsResponse.class);
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchStats failed: {}", e.getMessage());
            return null;
        }
    }

    // ── GET /api/blacklist ─────────────────────────────────────────────────
    public static Set<String> fetchBlacklist() {
        try {
            HttpURLConnection conn = openConn(DonutAH.API_BASE + "/blacklist", "GET");
            conn.setRequestProperty("x-api-key", API_KEY);
            int code = conn.getResponseCode();
            if (code != 200) { conn.disconnect(); return Collections.emptySet(); }
            String body = readBody(conn);
            conn.disconnect();
            String[] arr = GSON.fromJson(body, String[].class);
            if (arr == null) return Collections.emptySet();
            Set<String> set = new HashSet<>();
            for (String name : arr) { if (name != null) set.add(name.toLowerCase()); }
            return set;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchBlacklist failed: {}", e.getMessage());
            if (BuildConstants.STAGING) {
                net.donutsmp.donutah.DebugWebhook.send("❌ fetchBlacklist failed: `" + e.getMessage() + "`");
            }
            return Collections.emptySet();
        }
    }

    // ── GET /api/muted ────────────────────────────────────────────────────
    public static Set<String> fetchMuted() {
        try {
            String body = getJson("/muted");
            if (body == null) return Collections.emptySet();
            String[] arr = GSON.fromJson(body, String[].class);
            if (arr == null) return Collections.emptySet();
            Set<String> set = new HashSet<>();
            for (String name : arr) { if (name != null) set.add(name.toLowerCase()); }
            return set;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchMuted failed: {}", e.getMessage());
            if (BuildConstants.STAGING) {
                net.donutsmp.donutah.DebugWebhook.send("❌ fetchMuted failed: `" + e.getMessage() + "`");
            }
            return Collections.emptySet();
        }
    }

    // ── POST/DELETE /api/muted ────────────────────────────────────────────
    public static boolean muteItem(String itemName) {
        try {
            HttpURLConnection conn = openConn(DonutAH.API_BASE + "/muted", "POST");
            conn.setRequestProperty("x-api-key", API_KEY);
            String json = "{\"item_name\":\"" + itemName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            writeBody(conn, json);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] muteItem failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean unmuteItem(String itemName) {
        try {
            HttpURLConnection conn = openConn(DonutAH.API_BASE + "/muted/" + encode(itemName), "DELETE");
            conn.setRequestProperty("x-api-key", API_KEY);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] unmuteItem failed: {}", e.getMessage());
            return false;
        }
    }

    // ── POST /api/heartbeat ────────────────────────────────────────────────
    public static void postHeartbeat(String uuid, String username) {
        String safe = username.replace("\\", "\\\\").replace("\"", "\\\"");
        String body = "{\"uuid\":\"" + uuid + "\",\"username\":\"" + safe + "\"}";
        // Try all servers in order — same cascade as getJson
        List<String> servers = new java.util.ArrayList<>();
        servers.add(DonutAH.API_BASE);
        for (String s : BuildConstants.STAGING
                ? new String[]{STAGING_LOCAL_BASE, STAGING_PRIMARY_BASE, STAGING_BACKUP_BASE}
                : BuildConstants.DEV
                    ? new String[]{PRIMARY_BASE, BACKUP_BASE}
                    : new String[]{BACKUP_BASE})
            if (!servers.contains(s)) servers.add(s);
        for (String server : servers) {
            try {
                HttpURLConnection conn = openConn(server + "/heartbeat", "POST");
                conn.setRequestProperty("x-api-key", API_KEY);
                writeBody(conn, body);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code >= 200 && code < 300) return; // success
            } catch (Exception e) {
                DonutAH.LOGGER.debug("[DonutAH] heartbeat failed on {}: {}", server, e.getMessage());
            }
        }
    }

    // ── GET /api/muted-guis ────────────────────────────────────────────────
    public static Set<String> fetchMutedGuis() {
        try {
            String body = getJson("/muted-guis");
            if (body == null) return Collections.emptySet();
            String[] arr = GSON.fromJson(body, String[].class);
            if (arr == null) return Collections.emptySet();
            Set<String> set = new HashSet<>();
            for (String name : arr) { if (name != null) set.add(name.toLowerCase()); }
            return set;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchMutedGuis failed: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    // ── GET /api/labels ────────────────────────────────────────────────────
    public static java.util.Map<String, String> fetchLabels() {
        try {
            String body = getJson("/labels");
            if (body == null) return java.util.Collections.emptyMap();
            LabelEntry[] arr = GSON.fromJson(body, LabelEntry[].class);
            if (arr == null) return java.util.Collections.emptyMap();
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (LabelEntry e : arr) {
                if (e.item_name != null && e.label != null)
                    map.put(e.item_name.toLowerCase(), e.label);
            }
            return map;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchLabels failed: {}", e.getMessage());
            if (BuildConstants.STAGING) {
                net.donutsmp.donutah.DebugWebhook.send("❌ fetchLabels failed: `" + e.getMessage() + "`");
            }
            return java.util.Collections.emptyMap();
        }
    }

    // ── POST /api/labels ───────────────────────────────────────────────────
    public static boolean setLabel(String itemName, String label) {
        try {
            HttpURLConnection conn = openConn(DonutAH.API_BASE + "/labels", "POST");
            conn.setRequestProperty("x-api-key", API_KEY);
            String safe = itemName.replace("\\", "\\\\").replace("\"", "\\\"");
            String safeLabel = label.replace("\\", "\\\\").replace("\"", "\\\"");
            writeBody(conn, "{\"item_name\":\"" + safe + "\",\"label\":\"" + safeLabel + "\"}");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] setLabel failed: {}", e.getMessage());
            return false;
        }
    }

    // ── DELETE /api/labels/:name ───────────────────────────────────────────
    public static boolean clearLabel(String itemName) {
        try {
            HttpURLConnection conn = openConn(DonutAH.API_BASE + "/labels/" + encode(itemName), "DELETE");
            conn.setRequestProperty("x-api-key", API_KEY);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] clearLabel failed: {}", e.getMessage());
            return false;
        }
    }

    // ── POST/DELETE /api/static-prices ────────────────────────────────────
    public static boolean setStaticPrice(String itemName, double price) {
        try {
            HttpURLConnection conn = openConn(DonutAH.API_BASE + "/static-prices", "POST");
            conn.setRequestProperty("x-api-key", API_KEY);
            String safe = itemName.replace("\\", "\\\\").replace("\"", "\\\"");
            writeBody(conn, "{\"item_name\":\"" + safe + "\",\"price\":" + price + "}");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] setStaticPrice failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean clearStaticPrice(String itemName) {
        try {
            HttpURLConnection conn = openConn(DonutAH.API_BASE + "/static-prices/" + encode(itemName), "DELETE");
            conn.setRequestProperty("x-api-key", API_KEY);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] clearStaticPrice failed: {}", e.getMessage());
            return false;
        }
    }

    // ── GET /api/static-prices ─────────────────────────────────────────────
    public static java.util.Map<String, Double> fetchStaticPrices() {
        try {
            String body = getJson("/static-prices");
            if (body == null) return java.util.Collections.emptyMap();
            StaticPriceEntry[] arr = GSON.fromJson(body, StaticPriceEntry[].class);
            if (arr == null) return java.util.Collections.emptyMap();
            java.util.Map<String, Double> map = new java.util.HashMap<>();
            for (StaticPriceEntry e : arr) {
                if (e.item_name != null) map.put(e.item_name.toLowerCase(), e.price);
            }
            return map;
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchStaticPrices failed: {}", e.getMessage());
            if (BuildConstants.STAGING) {
                net.donutsmp.donutah.DebugWebhook.send("❌ fetchStaticPrices failed: `" + e.getMessage() + "`");
            }
            return java.util.Collections.emptyMap();
        }
    }

    // ── GET /api/online ────────────────────────────────────────────────────
    public static OnlineStats fetchOnlineStats() {
        try {
            String body = getJson("/online");
            if (body == null) return null;
            return GSON.fromJson(body, OnlineStats.class);
        } catch (Exception e) {
            DonutAH.LOGGER.debug("[DonutAH] fetchOnlineStats failed: {}", e.getMessage());
            return null;
        }
    }

    public static class OnlineStats {
        public int online;
        public OnlinePlayer[] players;
        public int scans_today;
        public int scans_per_hour;
        public String hottest_item;
        public int total_scans;
    }

    public static class OnlinePlayer {
        public String username;
        public long last_seen;
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private static HttpURLConnection openConn(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static void writeBody(HttpURLConnection conn, String body) throws Exception {
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String encode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    public static class LabelEntry {
        public String item_name;
        public String label;
    }

    public static class StaticPriceEntry {
        public String item_name;
        public double price;
    }

    public static class LatestListing {
        public String item_name;
        public double price;
        public String seller;
        public String enchantments;  // JSON array string from DB, e.g. ["Sharpness IV","Unbreaking III"], or null

        // Builds the same cache key format as AHScraper: "item_name|Enchant A,Enchant B"
        public String getCacheKey() {
            String base = item_name == null ? "" : item_name.toLowerCase();
            if (enchantments == null || enchantments.isEmpty()) return base + "|";
            String[] arr = GSON.fromJson(enchantments, String[].class);
            if (arr == null || arr.length == 0) return base + "|";
            Arrays.sort(arr);
            return base + "|" + String.join(",", arr);
        }
    }

    public static class VariantResponse {
        public String enchantments; // JSON array string or null
        public double avg, min, max;
        public int volume;

        public String getLabel() {
            if (enchantments == null || enchantments.isEmpty()) return "Unenchanted";
            String[] arr = GSON.fromJson(enchantments, String[].class);
            if (arr == null || arr.length == 0) return "Unenchanted";
            return String.join(", ", arr);
        }
    }

    public static class PriceResponse {
        public String item_name;
        public double avg, min, max;
        public int volume;
    }

    public static class StatsResponse {
        public String item_name;
        public double daily_avg, daily_min, daily_max, weekly_avg, price_change_pct;
        public int daily_volume, weekly_volume;
    }

    // ── GET /api/top ───────────────────────────────────────────────────────
    public static List<TopItem> fetchTop(int limit) {
        try {
            String body = getJson("/top?limit=" + limit);
            if (body == null) return Collections.emptyList();
            TopItem[] arr = GSON.fromJson(body, TopItem[].class);
            return arr != null ? Arrays.asList(arr) : Collections.emptyList();
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] fetchTop failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public static class TopItem {
        public String item_name, enchantments;
        public double avg, min, max;
        public int volume, spread_pct;

        public String getLabel() {
            if (enchantments == null || enchantments.isEmpty()) return item_name;
            String[] arr = GSON.fromJson(enchantments, String[].class);
            if (arr == null || arr.length == 0) return item_name;
            return item_name + " §8[§7" + String.join(", ", arr) + "§8]";
        }
    }

}
