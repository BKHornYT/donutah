package net.donutsmp.donutah.dev;

import net.donutsmp.donutah.DonutAH;
import net.donutsmp.donutah.DonutAHConfig;
import net.donutsmp.donutah.TooltipHandler;
import net.donutsmp.donutah.network.ApiClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class LabelInputScreen extends Screen {

    private final String itemName;
    private final Screen parent;
    private TextFieldWidget labelInput;
    private TextFieldWidget priceInput;

    // Presets that are non-empty (built at init time)
    private final List<String> activePresets = new ArrayList<>();

    public LabelInputScreen(String itemName, Screen parent) {
        super(Text.literal("DonutAH - Set Label"));
        this.itemName = itemName;
        this.parent   = parent;
    }

    @Override
    protected void init() {
        activePresets.clear();
        for (String p : DonutAHConfig.devLabelPresets)
            if (p != null && !p.isBlank()) activePresets.add(p);

        int cx     = this.width  / 2;
        int cy     = this.height / 2;
        int panelW = Math.min(320, this.width - 40);
        int px     = cx - panelW / 2;

        // Preset row height: 20px per row of up to 3 presets
        int presetRows  = (int) Math.ceil(activePresets.size() / 3.0);
        int presetBlock = presetRows > 0 ? presetRows * 22 + 4 : 0;

        // Total panel height: header (50) + presets + label input (24) + label buttons (24)
        //                     + divider (14) + price section (14 + 24 + 24) + hint (16)
        int panelH = 50 + presetBlock + 24 + 28 + 14 + 14 + 24 + 28 + 16;
        int py     = cy - panelH / 2;

        // ── Header text widgets ──────────────────────────────────────────
        addDrawableChild(new TextWidget(px, py + 8, panelW, 10,
                Text.literal("DonutAH - Set Label & Shop Price"), textRenderer));
        addDrawableChild(new TextWidget(px, py + 20, panelW, 10,
                Text.literal(itemName), textRenderer));
        String existing = DonutAH.itemLabels.get(itemName.toLowerCase());
        addDrawableChild(new TextWidget(px, py + 32, panelW, 10,
                Text.literal(existing != null ? "Current: * " + existing : "No label set"), textRenderer));

        // ── Preset buttons ───────────────────────────────────────────────
        int presetBtnW = (panelW - 8) / 3;
        for (int i = 0; i < activePresets.size(); i++) {
            String preset = activePresets.get(i);
            int col = i % 3;
            int row = i / 3;
            int bx  = px + 4 + col * (presetBtnW + 2);
            int by  = py + 48 + row * 22;
            // Truncate display label to fit button
            String display = preset.length() > 14 ? preset.substring(0, 13) + "…" : preset;
            addDrawableChild(ButtonWidget.builder(Text.literal(display), btn -> {
                        labelInput.setText(preset);
                        labelInput.setFocused(true);
                    })
                    .dimensions(bx, by, presetBtnW, 18).build());
        }

        // ── Label text input ─────────────────────────────────────────────
        int labelInputY = py + 48 + presetBlock;
        int fieldW = panelW - 8;
        labelInput = new TextFieldWidget(textRenderer, px + 4, labelInputY, fieldW, 20, Text.literal(""));
        labelInput.setMaxLength(64);
        labelInput.setText(existing != null ? existing : "");
        labelInput.setPlaceholder(Text.literal("Type label... (§e§l = bold yellow, etc.)"));
        labelInput.setFocused(true);
        addDrawableChild(labelInput);

        // ── Label Save / Clear buttons ────────────────────────────────────
        int labelBtnY = labelInputY + 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Save Label"),  btn -> saveLabel())
                .dimensions(cx - 52, labelBtnY, 50, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear Label"), btn -> clearLabel())
                .dimensions(cx + 2,  labelBtnY, 50, 20).build());

        // ── Divider + static price section ──────────────────────────────
        int dividerY = labelBtnY + 28;
        Double existingPrice = DonutAH.staticPrices.get(itemName.toLowerCase());
        String priceStatus = existingPrice != null
            ? "Shop price: $" + TooltipHandler.formatPrice(existingPrice)
            : "No shop price set";
        addDrawableChild(new TextWidget(px, dividerY, panelW, 10,
                Text.literal("§8── Shop Price ── §7" + priceStatus), textRenderer));

        int priceInputY = dividerY + 14;
        priceInput = new TextFieldWidget(textRenderer, px + 4, priceInputY, fieldW, 20, Text.literal(""));
        priceInput.setMaxLength(20);
        priceInput.setText(existingPrice != null ? String.valueOf(existingPrice.longValue()) : "");
        priceInput.setPlaceholder(Text.literal("Enter fixed shop price..."));
        addDrawableChild(priceInput);

        int priceBtnY = priceInputY + 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Set Price"),   btn -> setPrice())
                .dimensions(cx - 52, priceBtnY, 50, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear Price"), btn -> clearPrice())
                .dimensions(cx + 2,  priceBtnY, 50, 20).build());

        // ── Hint ─────────────────────────────────────────────────────────
        addDrawableChild(new TextWidget(px, priceBtnY + 24, panelW, 10,
                Text.literal("Esc to cancel  |  Edit presets in DonutAH Settings"), textRenderer));

        // Store panel geometry for render
        this.panelX = px; this.panelY = py; this.panelW2 = panelW; this.panelH2 = panelH;
    }

    // Panel coords set during init, used in render
    private int panelX, panelY, panelW2, panelH2;

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        ctx.fill(panelX - 6, panelY - 2, panelX + panelW2 + 6, panelY + panelH2 + 2, 0xFF555555);
        ctx.fill(panelX - 5, panelY - 1, panelX + panelW2 + 5, panelY + panelH2 + 1, 0xFF1e1e1e);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void saveLabel() {
        String label = labelInput.getText().trim();
        if (label.isEmpty()) { clearLabel(); return; }
        String name = itemName;
        close();
        Thread.ofVirtual().start(() -> {
            boolean ok = ApiClient.setLabel(name, label);
            if (ok) {
                DonutAH.itemLabels.put(name.toLowerCase(), label);
                TooltipHandler.clearCache();
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal(ok
                        ? "§8[§b⬡§8] §aLabel set: §e" + label + " §aon §f" + name
                        : "§8[§b⬡§8] §cFailed to set label (server error)"), false);
            });
        });
    }

    private void clearLabel() {
        String name = itemName;
        close();
        Thread.ofVirtual().start(() -> {
            boolean ok = ApiClient.clearLabel(name);
            if (ok) {
                DonutAH.itemLabels.remove(name.toLowerCase());
                TooltipHandler.clearCache();
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal(ok
                        ? "§8[§b⬡§8] §7Label cleared for §f" + name
                        : "§8[§b⬡§8] §cFailed to clear label (server error)"), false);
            });
        });
    }

    private void setPrice() {
        String raw = priceInput.getText().trim();
        double price;
        try {
            price = Double.parseDouble(raw);
            if (price <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal("§8[§b⬡§8] §cInvalid price — enter a positive number"), false);
            });
            return;
        }
        String name = itemName;
        double finalPrice = price;
        close();
        Thread.ofVirtual().start(() -> {
            boolean ok = ApiClient.setStaticPrice(name, finalPrice);
            if (ok) {
                DonutAH.staticPrices.put(name.toLowerCase(), finalPrice);
                TooltipHandler.clearCache();
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal(ok
                        ? "§8[§b⬡§8] §aShop price set: §f" + name + " §7= §a$" + TooltipHandler.formatPrice(finalPrice)
                        : "§8[§b⬡§8] §cFailed to set shop price (server error)"), false);
            });
        });
    }

    private void clearPrice() {
        String name = itemName;
        close();
        Thread.ofVirtual().start(() -> {
            boolean ok = ApiClient.clearStaticPrice(name);
            if (ok) {
                DonutAH.staticPrices.remove(name.toLowerCase());
                TooltipHandler.clearCache();
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal(ok
                        ? "§8[§b⬡§8] §7Shop price cleared for §f" + name
                        : "§8[§b⬡§8] §cFailed to clear shop price (server error)"), false);
            });
        });
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
