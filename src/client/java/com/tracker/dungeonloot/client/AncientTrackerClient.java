package com.tracker.dungeonloot.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DungeonLootTrackerClient implements ClientModInitializer {

    private static final List<DropEntry> DROPS = new ArrayList<>();
    private static final long ONE_HOUR_MS = 3600000L;

    // КЛЮЧОВИ ДУМИ В ЧАТА (Промени ги според сървъра ти!)
    private static final String[] TRIGGER_WORDS = {
        "drop", "дроп", "получихте", "намерихте", "you found", "loot"
    };

    private static class DropEntry {
        String itemName;
        long timestamp;

        DropEntry(String itemName, long timestamp) {
            this.itemName = itemName;
            this.timestamp = timestamp;
        }
    }

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String rawText = message.getString();
            String lowerText = rawText.toLowerCase();

            for (String trigger : TRIGGER_WORDS) {
                if (lowerText.contains(trigger)) {
                    DROPS.add(0, new DropEntry(rawText.trim(), System.currentTimeMillis()));
                    break;
                }
            }
        });

        HudRenderCallback.EVENT.register((GuiGraphics context, float tickDelta) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || DROPS.isEmpty()) return;

            long now = System.currentTimeMillis();

            Iterator<DropEntry> it = DROPS.iterator();
            while (it.hasNext()) {
                if (now - it.next().timestamp > ONE_HOUR_MS) {
                    it.remove();
                }
            }

            if (DROPS.isEmpty()) return;

            int x = 10;
            int y = 40;

            context.drawString(client.font, "§6§l[Loot Tracker - 1ч] §f(" + DROPS.size() + ")", x, y, 0xFFFFFF, true);
            y += 14;

            int shown = 0;
            for (DropEntry entry : DROPS) {
                if (shown >= 12) break;

                long diffSeconds = (now - entry.timestamp) / 1000;
                String timeAgo = formatTime(diffSeconds);

                String displayItem = entry.itemName.length() > 35 
                    ? entry.itemName.substring(0, 35) + "..." 
                    : entry.itemName;

                String line = "§a+ " + displayItem + " §7(" + timeAgo + ")";
                context.drawString(client.font, line, x, y, 0xFFFFFF, true);
                y += 11;
                shown++;
            }
        });
    }

    private String formatTime(long totalSeconds) {
        if (totalSeconds < 60) {
            return "преди " + totalSeconds + "с";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return "преди " + minutes + "м " + seconds + "с";
    }
}
