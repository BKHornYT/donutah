package net.donutsmp.donutah;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class DonutAHConfig {

    // ── Tooltip settings ──────────────────────────────────────────────────
    public static boolean tooltipEnabled    = true;
    public static String  prefix            = "DonutAH";
    public static PriceColor priceColor     = PriceColor.GREEN;
    public static boolean showStackValue    = true;
    public static boolean showMinMax        = false;
    public static boolean showTrend         = false;
    public static boolean showVolume        = false;
    public static boolean showShulkerValue  = true;
    public static boolean compactNumbers    = true;
    public static boolean tooltipSpacer    = true;
    public static InputUtil.Key inventoryValueKey = InputUtil.fromTranslationKey("key.keyboard.v");

    // ── Dev settings (only active in BuildConstants.DEV builds) ──────────
    public static boolean     devShowRawName      = true;
    public static ForceServer devForceServer      = ForceServer.AUTO;
    public static int         devSyncIntervalMin  = 3;
    public static boolean     devShowLabels       = true;
    public static InputUtil.Key devMuteKey  = InputUtil.fromTranslationKey("key.keyboard.b");
    public static InputUtil.Key devLabelKey = InputUtil.fromTranslationKey("key.keyboard.l");
    public static String[] devLabelPresets = {
        "In my shop",
        "§6Shop: $",
        "§aBuying: $",
        "§cOverpriced",
        "§eGood deal",
        ""
    };

    public enum ForceServer {
        AUTO("Auto (Best Available)"), HOME("Always Home"), VPS("Always VPS");
        private final String d;
        ForceServer(String d) { this.d = d; }
        public String getDisplayName() { return d; }
        @Override public String toString() { return d; }
    }

    // ── Command settings ──────────────────────────────────────────────────
    public static boolean cmdShowRange      = true;
    public static boolean cmdShowWeeklyAvg  = true;
    public static boolean cmdShowTrend      = true;
    public static boolean cmdShowVolume     = true;

    // ── Price color enum ──────────────────────────────────────────────────
    public enum PriceColor {
        GREEN   ("§a", "Green"),
        AQUA    ("§b", "Aqua"),
        YELLOW  ("§e", "Yellow"),
        GOLD    ("§6", "Gold"),
        WHITE   ("§f", "White"),
        PURPLE  ("§d", "Purple");

        private final String code;
        private final String displayName;

        PriceColor(String code, String displayName) {
            this.code        = code;
            this.displayName = displayName;
        }

        public String getCode()        { return code; }
        public String getDisplayName() { return displayName; }

        @Override
        public String toString() { return displayName; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Returns the formatted tooltip prefix, e.g. §8[§7DonutAH§8] */
    public static String getFormattedPrefix() {
        return "§8[§7" + prefix + "§8]";
    }

    /** Returns the Minecraft color code for the configured price color. */
    public static String getColorCode() {
        return priceColor.getCode();
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutah.json");
    }

    public static void load() {
        try {
            File f = configPath().toFile();
            if (!f.exists()) { save(); return; }
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                Data d = GSON.fromJson(r, Data.class);
                if (d == null) return;
                if (d.tooltipEnabled   != null) tooltipEnabled   = d.tooltipEnabled;
                if (d.prefix           != null) prefix           = d.prefix;
                if (d.priceColor       != null) { try { priceColor = PriceColor.valueOf(d.priceColor); } catch (Exception ignored) {} }
                if (d.showStackValue   != null) showStackValue   = d.showStackValue;
                if (d.showMinMax       != null) showMinMax       = d.showMinMax;
                if (d.showTrend        != null) showTrend        = d.showTrend;
                if (d.showVolume       != null) showVolume       = d.showVolume;
                if (d.showShulkerValue != null) showShulkerValue = d.showShulkerValue;
                if (d.inventoryValueKey != null) { try { inventoryValueKey = InputUtil.fromTranslationKey(d.inventoryValueKey); } catch (Exception ignored) {} }
                if (d.compactNumbers   != null) compactNumbers   = d.compactNumbers;
                if (d.tooltipSpacer    != null) tooltipSpacer    = d.tooltipSpacer;
                if (d.cmdShowRange     != null) cmdShowRange     = d.cmdShowRange;
                if (d.cmdShowWeeklyAvg != null) cmdShowWeeklyAvg = d.cmdShowWeeklyAvg;
                if (d.cmdShowTrend     != null) cmdShowTrend     = d.cmdShowTrend;
                if (d.cmdShowVolume    != null) cmdShowVolume    = d.cmdShowVolume;
                if (d.devShowRawName     != null) devShowRawName     = d.devShowRawName;
                if (d.devShowLabels      != null) devShowLabels      = d.devShowLabels;
                if (d.devForceServer     != null) { try { devForceServer = ForceServer.valueOf(d.devForceServer); } catch (Exception ignored) {} }
                if (d.devSyncIntervalMin != null) devSyncIntervalMin = Math.max(1, Math.min(10, d.devSyncIntervalMin));
                if (d.devMuteKey  != null) { try { devMuteKey  = InputUtil.fromTranslationKey(d.devMuteKey);  } catch (Exception ignored) {} }
                if (d.devLabelKey != null) { try { devLabelKey = InputUtil.fromTranslationKey(d.devLabelKey); } catch (Exception ignored) {} }
                if (d.devLabelPresets != null) {
                    for (int i = 0; i < Math.min(d.devLabelPresets.length, devLabelPresets.length); i++)
                        devLabelPresets[i] = d.devLabelPresets[i] != null ? d.devLabelPresets[i] : "";
                }
            }
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] Failed to load config: {}", e.getMessage());
        }
    }

    public static void save() {
        try {
            Data d = new Data();
            d.tooltipEnabled   = tooltipEnabled;
            d.prefix           = prefix;
            d.priceColor       = priceColor.name();
            d.showStackValue   = showStackValue;
            d.showMinMax       = showMinMax;
            d.showTrend        = showTrend;
            d.showVolume       = showVolume;
            d.showShulkerValue = showShulkerValue;
            d.compactNumbers   = compactNumbers;
            d.tooltipSpacer    = tooltipSpacer;
            d.inventoryValueKey = inventoryValueKey.getTranslationKey();
            d.cmdShowRange     = cmdShowRange;
            d.cmdShowWeeklyAvg = cmdShowWeeklyAvg;
            d.cmdShowTrend     = cmdShowTrend;
            d.cmdShowVolume    = cmdShowVolume;
            d.devShowRawName     = devShowRawName;
            d.devShowLabels      = devShowLabels;
            d.devForceServer     = devForceServer.name();
            d.devSyncIntervalMin = devSyncIntervalMin;
            d.devMuteKey         = devMuteKey.getTranslationKey();
            d.devLabelKey        = devLabelKey.getTranslationKey();
            d.devLabelPresets    = devLabelPresets.clone();
            File f = configPath().toFile();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                GSON.toJson(d, w);
            }
        } catch (Exception e) {
            DonutAH.LOGGER.warn("[DonutAH] Failed to save config: {}", e.getMessage());
        }
    }

    // Raw DTO for Gson serialisation (nullable fields = "not present in file")
    private static class Data {
        Boolean tooltipEnabled, showStackValue, showMinMax, showTrend, showVolume, showShulkerValue, compactNumbers, tooltipSpacer;
        Boolean cmdShowRange, cmdShowWeeklyAvg, cmdShowTrend, cmdShowVolume;
        String  inventoryValueKey;
        Boolean devShowRawName, devShowLabels;
        String  prefix, priceColor;
        String  devForceServer, devMuteKey, devLabelKey;
        Integer devSyncIntervalMin;
        String[] devLabelPresets;
    }

    // ── Cloth Config screen ───────────────────────────────────────────────

    public static Screen createScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("§b§lDonutAH §r§7Settings"))
                .setSavingRunnable(DonutAHConfig::save);

        ConfigEntryBuilder eb = builder.entryBuilder();

        // ── Tooltip category ──────────────────────────────────────────
        ConfigCategory tooltip = builder.getOrCreateCategory(Text.literal("Tooltip"));

        tooltip.addEntry(eb.startBooleanToggle(Text.literal("Enable Price Tooltip"), tooltipEnabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show DonutAH price info when hovering over items."))
                .setSaveConsumer(v -> tooltipEnabled = v)
                .build());

        tooltip.addEntry(eb.startStrField(Text.literal("Prefix Text"), prefix)
                .setDefaultValue("DonutAH")
                .setTooltip(Text.literal("Text shown in [brackets] on tooltip lines. Default: DonutAH"))
                .setSaveConsumer(v -> prefix = v.isBlank() ? "DonutAH" : v)
                .build());

        tooltip.addEntry(eb.startEnumSelector(Text.literal("Price Color"), PriceColor.class, priceColor)
                .setDefaultValue(PriceColor.GREEN)
                .setTooltip(Text.literal("Color used to display prices in tooltips."))
                .setEnumNameProvider(e -> Text.literal(((PriceColor) e).getDisplayName()))
                .setSaveConsumer(v -> priceColor = v)
                .build());

        tooltip.addEntry(eb.startBooleanToggle(Text.literal("Show Stack Value"), showStackValue)
                .setDefaultValue(true)
                .setTooltip(Text.literal("When holding a stack of 2+ items, show the total stack value below the per-unit price."))
                .setSaveConsumer(v -> showStackValue = v)
                .build());

        tooltip.addEntry(eb.startBooleanToggle(Text.literal("Show Min/Max Range"), showMinMax)
                .setDefaultValue(false)
                .setTooltip(Text.literal("Also show the 24h low and high price in the tooltip."))
                .setSaveConsumer(v -> showMinMax = v)
                .build());

        tooltip.addEntry(eb.startBooleanToggle(Text.literal("Show Shulker Contents Value"), showShulkerValue)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show the estimated total market value of items inside a shulker box."))
                .setSaveConsumer(v -> showShulkerValue = v)
                .build());

        tooltip.addEntry(eb.startBooleanToggle(Text.literal("Show Price Trend"), showTrend)
                .setDefaultValue(false)
                .setTooltip(Text.literal("Show a price trend arrow (▲/▼) and % change in the item tooltip."))
                .setSaveConsumer(v -> showTrend = v)
                .build());

        tooltip.addEntry(eb.startBooleanToggle(Text.literal("Show Daily Volume"), showVolume)
                .setDefaultValue(false)
                .setTooltip(Text.literal("Show how many times this item was scanned today in the tooltip."))
                .setSaveConsumer(v -> showVolume = v)
                .build());

        tooltip.addEntry(eb.startKeyCodeField(Text.literal("Inventory Value Key"), inventoryValueKey)
                .setDefaultValue(InputUtil.fromTranslationKey("key.keyboard.v"))
                .setTooltip(Text.literal("Press this key while in your inventory to calculate the total market value of all items."))
                .setKeySaveConsumer(v -> inventoryValueKey = v)
                .build());

        tooltip.addEntry(eb.startBooleanToggle(Text.literal("Blank Line Before Tooltip"), tooltipSpacer)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Add an empty line between item stats and DonutAH price info."))
                .setSaveConsumer(v -> tooltipSpacer = v)
                .build());

        tooltip.addEntry(eb.startBooleanToggle(Text.literal("Compact Numbers (K/M/B)"), compactNumbers)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Display prices as 1.5K, 2.3M etc. instead of full numbers."))
                .setSaveConsumer(v -> compactNumbers = v)
                .build());

        // ── Command category ──────────────────────────────────────────
        ConfigCategory command = builder.getOrCreateCategory(Text.literal("Command"));

        command.addEntry(eb.startBooleanToggle(Text.literal("Show 24h Range (min → max)"), cmdShowRange)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show the 24h low/high price range in /donutah output."))
                .setSaveConsumer(v -> cmdShowRange = v)
                .build());

        command.addEntry(eb.startBooleanToggle(Text.literal("Show 7-Day Average"), cmdShowWeeklyAvg)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show the 7-day average price in /donutah output."))
                .setSaveConsumer(v -> cmdShowWeeklyAvg = v)
                .build());

        command.addEntry(eb.startBooleanToggle(Text.literal("Show Price Trend"), cmdShowTrend)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show the price trend (▲/▼ vs yesterday) in /donutah output."))
                .setSaveConsumer(v -> cmdShowTrend = v)
                .build());

        command.addEntry(eb.startBooleanToggle(Text.literal("Show Volume"), cmdShowVolume)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show daily/weekly volume in /donutah output."))
                .setSaveConsumer(v -> cmdShowVolume = v)
                .build());

        // ── Dev category (only shown in dev builds) ───────────────────
        if (BuildConstants.DEV) {
            ConfigCategory dev = builder.getOrCreateCategory(Text.literal("§c[Dev]"));

            dev.addEntry(eb.startBooleanToggle(Text.literal("Show Raw Item Name"), devShowRawName)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Show the exact internal item name string in tooltips. Useful for finding names to blacklist."))
                    .setSaveConsumer(v -> devShowRawName = v)
                    .build());

            dev.addEntry(eb.startKeyCodeField(Text.literal("Mute Item Key"), devMuteKey)
                    .setDefaultValue(InputUtil.fromTranslationKey("key.keyboard.b"))
                    .setTooltip(Text.literal("While hovering an item, press this key to toggle mute (hide its tooltip price)."))
                    .setKeySaveConsumer(v -> devMuteKey = v)
                    .build());

            dev.addEntry(eb.startKeyCodeField(Text.literal("Label Item Key"), devLabelKey)
                    .setDefaultValue(InputUtil.fromTranslationKey("key.keyboard.l"))
                    .setTooltip(Text.literal("While hovering an item, press this key to open the label editor."))
                    .setKeySaveConsumer(v -> devLabelKey = v)
                    .build());

            dev.addEntry(eb.startEnumSelector(Text.literal("Force Server"), ForceServer.class, devForceServer)
                    .setDefaultValue(ForceServer.AUTO)
                    .setTooltip(Text.literal("Override automatic server selection. Use Auto unless debugging a specific server."))
                    .setEnumNameProvider(e -> Text.literal(((ForceServer) e).getDisplayName()))
                    .setSaveConsumer(v -> devForceServer = v)
                    .build());

            dev.addEntry(eb.startIntSlider(Text.literal("Cache Re-sync Interval (min)"), devSyncIntervalMin, 1, 10)
                    .setDefaultValue(3)
                    .setTooltip(Text.literal("How often (in minutes) to re-fetch the listing cache and re-select the best server. Default: 3."))
                    .setSaveConsumer(v -> devSyncIntervalMin = v)
                    .build());

            for (int i = 0; i < devLabelPresets.length; i++) {
                final int idx = i;
                dev.addEntry(eb.startStrField(Text.literal("Label Preset " + (i + 1)), devLabelPresets[i])
                        .setDefaultValue(i == 0 ? "In my shop" : "")
                        .setTooltip(Text.literal("Quick-fill preset for the label screen. Supports color codes (e.g. §aGreen, §cRed). Leave empty to hide."))
                        .setSaveConsumer(v -> devLabelPresets[idx] = v)
                        .build());
            }

            dev.addEntry(eb.startBooleanToggle(Text.literal("Show Item Labels in Tooltip"), devShowLabels)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Show custom labels (set via /donutah label) below item prices in tooltips."))
                    .setSaveConsumer(v -> devShowLabels = v)
                    .build());
        }

        return builder.build();
    }
}
