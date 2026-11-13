package com.tracker.dungeonloot.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AncientTrackerClient implements ClientModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File SAVE_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "ancient_tracker.json");
    private static TrackerData data = new TrackerData();
    private static long runStartTime = 0L;

    public static class TrackerData {
        public int completed = 0;
        public double credits = 0.0;
        public long bestTimeSeconds = -1;
        public long lastTimeSeconds = -1;
        public int dungeonsSinceCredit = 0;
        public int dungeonsSinceHaunted = 0;
        public Map<String, Integer> drops = new LinkedHashMap<>();
        public Map<String, Double> prices = new LinkedHashMap<>();

        public TrackerData() {
            prices.put("CREDIT CAPSULE", 23000000.0);
            prices.put("HAUNTED CAPSULE", 35000000.0);
            prices.put("6-STAR CAPSULE", 2500000.0);
        }
    }

    @Override
    public void onInitializeClient() {
        loadData();

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String raw = message.getString().replaceAll("§[0-9a-fk-or]", "").trim();
            String lower = raw.toLowerCase();

            // 1. Засичане на старт на дънджън
            if (lower.contains("ancient sands") && (lower.contains("started") || lower.contains("entered") || lower.contains("wave 1"))) {
                runStartTime = System.currentTimeMillis();
            }

            // 2. Засичане на завършен дънджън (Completed)
            if ((lower.contains("dungeon") || lower.contains("ancient sands")) && 
                (lower.contains("completed") || lower.contains("complete") || lower.contains("finished"))) {
                
                data.completed++;
                data.credits += 0.5;
                data.dungeonsSinceCredit++;
                data.dungeonsSinceHaunted++;

                long parsedTime = extractTimeSeconds(raw);
                if (parsedTime > 0) {
                    data.lastTimeSeconds = parsedTime;
                } else if (runStartTime > 0) {
                    data.lastTimeSeconds = (System.currentTimeMillis() - runStartTime) / 1000L;
                    runStartTime = 0L;
                }

                if (data.lastTimeSeconds > 0 && (data.bestTimeSeconds == -1 || data.lastTimeSeconds < data.bestTimeSeconds)) {
                    data.bestTimeSeconds = data.lastTimeSeconds;
                }

                saveData();
            }

            // 3. Засичане и stack-ване само на 3-те основни капсули
            checkDrop(raw, lower, "credit capsule", "CREDIT CAPSULE");
            checkDrop(raw, lower, "haunted capsule", "HAUNTED CAPSULE");
            checkDrop(raw, lower, "6-star capsule", "6-STAR CAPSULE");
        });

        // Рисуване на панела с полупрозрачна черна рамка горе вляво
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("ancient-tracker", "dungeon_hud"),
            (GuiGraphicsExtractor context, DeltaTracker tickCounter) -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player == null) return;

                List<String> lines = new ArrayList<>();
                lines.add("§6§lAncient Sands Dungeon Tracker");
                lines.add("§fCompleted: §e" + data.completed);

                String creditsText = (data.credits % 1 == 0) ? String.valueOf((int) data.credits) : String.format("%.1f", data.credits);
                lines.add("§fCredits: §e" + creditsText);
                lines.add("§fBest Time: §a" + formatDuration(data.bestTimeSeconds));
                lines.add("§fLast Time: §7" + formatDuration(data.lastTimeSeconds));

                // Дропове с точните им цветове и имена
                for (Map.Entry<String, Integer> entry : data.drops.entrySet()) {
                    String coloredName = getColoredDropName(entry.getKey());
                    lines.add(coloredName + "§f: §e" + entry.getValue());
                }

                lines.add("§fDungeons since last §bCREDIT CAPSULE§f: §6" + data.dungeonsSinceCredit);
                lines.add("§fDungeons since last §dHAUNTED CAPSULE§f: §6" + data.dungeonsSinceHaunted);

                double totalProfit = calculateTotalProfit();
                lines.add("§a§lProfit: §2$§a" + formatMoney(totalProfit));

                // Изчисляваме размерите на рамката според най-дългия ред
                int x = 6;
                int y = 6;
                int padding = 5;
                int lineHeight = 11;
                int maxWidth = 0;

                for (String line : lines) {
                    int w = client.font.width(line);
                    if (w > maxWidth) {
                        maxWidth = w;
                    }
                }

                int boxLeft = x - padding;
                int boxTop = y - padding;
                int boxRight = x + maxWidth + padding;
                int boxBottom = y + (lines.size() * lineHeight) + padding - 2;

                // Полупрозрачен черен фон (0xAA000000)
                context.fill(boxLeft, boxTop, boxRight, boxBottom, 0xAA000000);

                // Тънка златиста/тъмносива рамка (1px от всички страни)
                int borderColor = 0xFF555555;
                context.fill(boxLeft, boxTop, boxRight, boxTop + 1, borderColor); // Горе
                context.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, borderColor); // Долу
                context.fill(boxLeft, boxTop, boxLeft + 1, boxBottom, borderColor); // Ляво
                context.fill(boxRight - 1, boxTop, boxRight, boxBottom, borderColor); // Дясно

                // Изписваме редовете вътре в рамката
                int currentY = y;
                for (String line : lines) {
                    context.text(client.font, line, x, currentY, 0xFFFFFFFF, true);
                    currentY += lineHeight;
                }
            }
        );
    }

    private void checkDrop(String raw, String lower, String keyword, String cleanKey) {
        // Игнорираме съобщения от играчи в чата (които съдържат двуеточие или @)
        if (lower.contains(keyword) && !raw.contains(":") && !raw.contains("@")) {
            data.drops.put(cleanKey, data.drops.getOrDefault(cleanKey, 0) + 1);

            if (cleanKey.equals("CREDIT CAPSULE")) {
                data.dungeonsSinceCredit = 0;
            } else if (cleanKey.equals("HAUNTED CAPSULE")) {
                data.dungeonsSinceHaunted = 0;
            }
            saveData();
        }
    }

    private double calculateTotalProfit() {
        double sum = 0.0;
        for (Map.Entry<String, Integer> entry : data.drops.entrySet()) {
            double price = data.prices.getOrDefault(entry.getKey(), 0.0);
            sum += price * entry.getValue();
        }
        return sum;
    }

    private String getColoredDropName(String key) {
        return switch (key) {
            case "CREDIT CAPSULE" -> "§bCREDIT CAPSULE";
            case "HAUNTED CAPSULE" -> "§dHAUNTED CAPSULE";
            case "6-STAR CAPSULE" -> "§66-STAR CAPSULE ☆☆☆☆☆☆";
            default -> "§f" + key;
        };
    }

    private String formatMoney(double amount) {
        if (amount >= 1_000_000_000) return String.format("%.2fB", amount / 1_000_000_000.0);
        if (amount >= 1_000_000) return String.format("%.2fM", amount / 1_000_000.0);
        if (amount >= 1_000) return String.format("%.1fK", amount / 1_000.0);
        return String.format("%.0f", amount);
    }

    private long extractTimeSeconds(String text) {
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(text);
        if (m.find()) {
            long mins = Long.parseLong(m.group(1));
            long secs = Long.parseLong(m.group(2));
            return mins * 60 + secs;
        }
        return -1;
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) return "N/A";
        long mins = totalSeconds / 60;
        long secs = totalSeconds % 60;
        return String.format("%02d:%02d", mins, secs);
    }

    private void loadData() {
        if (SAVE_FILE.exists()) {
            try (FileReader reader = new FileReader(SAVE_FILE)) {
                TrackerData loaded = GSON.fromJson(reader, TrackerData.class);
                if (loaded != null) {
                    data = loaded;
                    if (data.drops == null) data.drops = new LinkedHashMap<>();
                    if (data.prices == null) data.prices = new TrackerData().prices;
                }
            } catch (Exception ignored) {}
        } else {
            saveData();
        }
    }

    private void saveData() {
        try (FileWriter writer = new FileWriter(SAVE_FILE)) {
            GSON.toJson(data, writer);
        } catch (Exception ignored) {}
    }
}
