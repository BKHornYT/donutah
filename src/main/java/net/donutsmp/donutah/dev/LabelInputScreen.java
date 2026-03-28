package net.donutsmp.donutah.dev;

import net.donutsmp.donutah.DonutAH;
import net.donutsmp.donutah.DonutAHConfig;
import net.donutsmp.donutah.TooltipHandler;
import net.donutsmp.donutah.network.ApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class LabelInputScreen extends Screen {

    private final String itemName;
    private final Screen parent;
    private EditBox labelInput;
    private EditBox priceInput;

    // Presets that are non-empty (built at init time)
    private final List<String> activePresets = new ArrayList<>();

    public LabelInputScreen(String itemName, Screen parent) {
        super(Component.literal("DonutAH - Set Label"));
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
        addRenderableWidget(new StringWidget(px, py + 8, panelW, 10,
                Component.literal("DonutAH - Set Label & Shop Price"), this.font));
        addRenderableWidget(new StringWidget(px, py + 20, panelW, 10,
                Component.literal(itemName), this.font));
        String existing = DonutAH.itemLabels.get(itemName.toLowerCase());
        addRenderableWidget(new StringWidget(px, py + 32, panelW, 10,
                Component.literal(existing != null ? "Current: * " + existing : "No label set"), this.font));

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
            addRenderableWidget(Button.builder(Component.literal(display), btn -> {
                        labelInput.setValue(preset);
                        labelInput.setFocused(true);
                    })
                    .bounds(bx, by, presetBtnW, 18).build());
        }

        // ── Label text input ─────────────────────────────────────────────
        int labelInputY = py + 48 + presetBlock;
        int fieldW = panelW - 8;
        labelInput = new EditBox(this.font, px + 4, labelInputY, fieldW, 20, Component.literal(""));
        labelInput.setMaxLength(64);
        labelInput.setValue(existing != null ? existing : "");
        labelInput.setHint(Component.literal("Type label... (§e§l = bold yellow, etc.)"));
        labelInput.setFocused(true);
        addRenderableWidget(labelInput);

        // ── Label Save / Clear buttons ────────────────────────────────────
        int labelBtnY = labelInputY + 24;
        addRenderableWidget(Button.builder(Component.literal("Save Label"),  btn -> saveLabel())
                .bounds(cx - 52, labelBtnY, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear Label"), btn -> clearLabel())
                .bounds(cx + 2,  labelBtnY, 50, 20).build());

        // ── Divider + static price section ──────────────────────────────
        int dividerY = labelBtnY + 28;
        Double existingPrice = DonutAH.staticPrices.get(itemName.toLowerCase());
        String priceStatus = existingPrice != null
            ? "Shop price: $" + TooltipHandler.formatPrice(existingPrice)
            : "No shop price set";
        addRenderableWidget(new StringWidget(px, dividerY, panelW, 10,
                Component.literal("§8── Shop Price ── §7" + priceStatus), this.font));

        int priceInputY = dividerY + 14;
        priceInput = new EditBox(this.font, px + 4, priceInputY, fieldW, 20, Component.literal(""));
        priceInput.setMaxLength(20);
        priceInput.setValue(existingPrice != null ? String.valueOf(existingPrice.longValue()) : "");
        priceInput.setHint(Component.literal("Enter fixed shop price..."));
        addRenderableWidget(priceInput);

        int priceBtnY = priceInputY + 24;
        addRenderableWidget(Button.builder(Component.literal("Set Price"),   btn -> setPrice())
                .bounds(cx - 52, priceBtnY, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear Price"), btn -> clearPrice())
                .bounds(cx + 2,  priceBtnY, 50, 20).build());

        // ── Hint ─────────────────────────────────────────────────────────
        addRenderableWidget(new StringWidget(px, priceBtnY + 24, panelW, 10,
                Component.literal("Esc to cancel  |  Edit presets in DonutAH Settings"), this.font));

        // Store panel geometry for render
        this.panelX = px; this.panelY = py; this.panelW2 = panelW; this.panelH2 = panelH;
    }

    // Panel coords set during init, used in render
    private int panelX, panelY, panelW2, panelH2;

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        ctx.fill(panelX - 6, panelY - 2, panelX + panelW2 + 6, panelY + panelH2 + 2, 0xFF555555);
        ctx.fill(panelX - 5, panelY - 1, panelX + panelW2 + 5, panelY + panelH2 + 1, 0xFF1e1e1e);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    private void saveLabel() {
        String label = labelInput.getValue().trim();
        if (label.isEmpty()) { clearLabel(); return; }
        String name = itemName;
        onClose();
        Thread.ofVirtual().start(() -> {
            boolean ok = ApiClient.setLabel(name, label);
            if (ok) {
                DonutAH.itemLabels.put(name.toLowerCase(), label);
                TooltipHandler.clearCache();
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendSystemMessage(Component.literal(ok
                        ? "§8[§b⬡§8] §aLabel set: §e" + label + " §aon §f" + name
                        : "§8[§b⬡§8] §cFailed to set label (server error)"));
            });
        });
    }

    private void clearLabel() {
        String name = itemName;
        onClose();
        Thread.ofVirtual().start(() -> {
            boolean ok = ApiClient.clearLabel(name);
            if (ok) {
                DonutAH.itemLabels.remove(name.toLowerCase());
                TooltipHandler.clearCache();
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendSystemMessage(Component.literal(ok
                        ? "§8[§b⬡§8] §7Label cleared for §f" + name
                        : "§8[§b⬡§8] §cFailed to clear label (server error)"));
            });
        });
    }

    private void setPrice() {
        String raw = priceInput.getValue().trim();
        double price;
        try {
            price = Double.parseDouble(raw);
            if (price <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendSystemMessage(Component.literal("§8[§b⬡§8] §cInvalid price — enter a positive number"));
            });
            return;
        }
        String name = itemName;
        double finalPrice = price;
        onClose();
        Thread.ofVirtual().start(() -> {
            boolean ok = ApiClient.setStaticPrice(name, finalPrice);
            if (ok) {
                DonutAH.staticPrices.put(name.toLowerCase(), finalPrice);
                TooltipHandler.clearCache();
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendSystemMessage(Component.literal(ok
                        ? "§8[§b⬡§8] §aShop price set: §f" + name + " §7= §a$" + TooltipHandler.formatPrice(finalPrice)
                        : "§8[§b⬡§8] §cFailed to set shop price (server error)"));
            });
        });
    }

    private void clearPrice() {
        String name = itemName;
        onClose();
        Thread.ofVirtual().start(() -> {
            boolean ok = ApiClient.clearStaticPrice(name);
            if (ok) {
                DonutAH.staticPrices.remove(name.toLowerCase());
                TooltipHandler.clearCache();
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.sendSystemMessage(Component.literal(ok
                        ? "§8[§b⬡§8] §7Shop price cleared for §f" + name
                        : "§8[§b⬡§8] §cFailed to clear shop price (server error)"));
            });
        });
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
