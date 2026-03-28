package net.donutsmp.donutah.mixin;

import net.donutsmp.donutah.AHScraper;
import net.donutsmp.donutah.BuildConstants;
import net.donutsmp.donutah.DonutAH;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class ScreenHandlerMixin extends Screen {

    @Shadow public abstract AbstractContainerMenu getMenu();

    protected ScreenHandlerMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("RETURN"))
    private void onScreenInit(CallbackInfo ci) {
        if (!DonutAH.onTargetServer) return;
        AbstractContainerMenu handler = getMenu();
        if (!(handler instanceof ChestMenu containerHandler)) return;
        if (containerHandler.getRowCount() != 6) return;

        // Only scan page 1 — skip page 2, 3, etc.
        String titleLower = this.getTitle().getString().toLowerCase();
        if (titleLower.contains("page") && !titleLower.contains("page 1")) return;

        DonutAH.LOGGER.info("[DonutAH] 6-row screen '{}' — waiting for slot data...", this.getTitle().getString());
        if (BuildConstants.STAGING) {
            net.donutsmp.donutah.DebugWebhook.send(
                "📦 AH screen opened: **\"" + this.getTitle().getString() + "\"** — waiting for slots...");
        }

        // Poll every 500ms until slots have data (up to 10s)
        Thread.ofVirtual().start(() -> {
            Minecraft client = Minecraft.getInstance();
            boolean dataSeenLastPoll = false;
            for (int attempt = 0; attempt < 20; attempt++) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                if (client.screen == null) return;
                boolean hasData = false;
                for (int s = 0; s < 54; s++) {
                    if (!containerHandler.getSlot(s).getItem().isEmpty()) { hasData = true; break; }
                }
                if (hasData && dataSeenLastPoll) {
                    // Data was present for 2 consecutive polls — all slot packets should be loaded
                    int ms = (attempt + 1) * 500;
                    DonutAH.LOGGER.info("[DonutAH] Slots stable after {}ms", ms);
                    if (BuildConstants.STAGING) {
                        net.donutsmp.donutah.DebugWebhook.send("✅ Slots stable after **" + ms + "ms**");
                    }
                    client.execute(() -> AHScraper.onAHScreenOpen(containerHandler));
                    return;
                }
                dataSeenLastPoll = hasData;
            }
            DonutAH.LOGGER.warn("[DonutAH] Timed out waiting for slot data (10s)");
            if (BuildConstants.STAGING) {
                net.donutsmp.donutah.DebugWebhook.send("⏰ AH slot data timed out (10s) — no data received");
            }
        });
    }
}
