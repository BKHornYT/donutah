package net.donutsmp.donutah;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Fire-and-forget Discord webhook debug logger.
 * Only active when BuildConstants.STAGING is true — compiles to nothing in release/dev builds.
 */
public class DebugWebhook {

    private static final String WEBHOOK_URL =
        "YOUR_DISCORD_WEBHOOK_URL_HERE";
    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    public static void send(String message) {
        if (!BuildConstants.STAGING) return;
        Thread.ofVirtual().start(() -> {
            try {
                String ts = TS_FMT.format(Instant.now());
                String full = "`[STAGING " + ts + " UTC]` " + message;
                if (full.length() > 2000) full = full.substring(0, 1997) + "...";
                String json = "{\"content\":" + jsonStr(full) + "}";
                HttpURLConnection c = (HttpURLConnection)
                    new URI(WEBHOOK_URL).toURL().openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception e) {
                DonutAH.LOGGER.debug("[DonutAH] DebugWebhook failed: {}", e.getMessage());
            }
        });
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r")
                        .replace("\t", "\\t") + "\"";
    }
}
