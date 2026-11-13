package com.tracker.dungeonloot.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
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
        
        // Цени на предметите (можеш да ги променяш директно в config/ancient_tracker.json)
        public Map<String, Double> prices = new LinkedHashMap<>();

        public TrackerData() {
            prices.put("Credit Capsule", 1000000.0);
            prices.put("Haunted Capsule", 2500000.0);
            prices.put("6-STAR CAPSULE", 500000.0);
            prices.put("5-STAR CAPSULE", 150000.0);
            prices.put("DIVINE Drop", 50000.0);
            prices.put("SECRET Drop", 25000.0);
        }
    }

    @Override
    public void onInitializeClient() {
        loadData();

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String raw = message.getString().replaceAll("§[0-9a-fk-or]", "").trim();
            String lower = raw.toLowerCase();

            // 1. Засичане на старт на дънджън
            if (lower.contains("ancient sands") && (lower.contains("started") || lower.contains("entered") || lower.contains("започна"))) {
                runStartTime = System.currentTimeMillis();
            }

            // 2. Засичане на завършен дънджън (Completed)
            if ((lower.contains("dungeon") || lower.contains("ancient sands")) && 
                (lower.contains("completed") || lower.contains("complete") || lower.contains("finished") || lower.contains("завършен"))) {
                
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

            // 3. Засичане и stack-ване на дроповете
            checkDrop(raw, lower, "credit capsule", "Credit Capsule");
            checkDrop(raw, lower, "haunted capsule", "Haunted Capsule");
            checkDrop(raw, lower, "6-star capsule", "6-STAR CAPSULE");
            checkDrop(raw, lower, "5-star capsule", "5-STAR CAPSULE");
            checkDrop(raw, lower, "divine", "DIVINE Drop");
            checkDrop(raw, lower, "secret", "SECRET Drop");
        });

        // Рисуване на панела горе вляво
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("ancient-tracker", "dungeon_hud"),
            (GuiGraphicsExtractor context, float tickDelta) -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player == null) return;

                int x = 6;
                int y = 6;
                int lineHeight = 10;

                // Заглавие
                context.drawString(client.font, "§6§lAncient Sands Dungeon Tracker", x, y, 0xFFFFFFFF, true);
                y += lineHeight;

                // Основни статистики
                context.drawString(client.font, "§fCompleted: §e" + data.completed, x, y, 0xFFFFFFFF, true);
                y += lineHeight;

                String creditsText = (data.credits % 1 == 0) ? String.valueOf((int) data.credits) : String.format("%.1f", data.credits);
                context.drawString(client.font, "§fCredits: §e" + creditsText, x, y, 0xFFFFFFFF, true);
                y += lineHeight;

                context.drawString(client.font, "§fBest Time: §a" + formatDuration(data.bestTimeSeconds), x, y, 0xFFFFFFFF, true);
                y += lineHeight;

                context.drawString(client.font, "§fLast Time: §7" + formatDuration(data.lastTimeSeconds), x, y, 0xFFFFFFFF, true);
                y += lineHeight;

                // Дропове с цветове
                for (Map.Entry<String, Integer> entry : data.drops.entrySet()) {
                    String coloredName = getColoredDropName(entry.getKey());
                    context.drawString(client.font, coloredName + "§f: §e" + entry.getValue(), x, y, 0xFFFFFFFF, true);
                    y += lineHeight;
                }

                // Броячи от последния дроп на капсули
                context.drawString(client.font, "§fDungeons since last §eCredit Capsule§f: §6" + data.dungeonsSinceCredit, x, y, 0xFFFFFFFF, true);
                y += lineHeight;

                context.drawString(client.font, "§fDungeons since last §dHaunted Capsule§f: §6" + data.dungeonsSinceHaunted, x, y, 0xFFFFFFFF, true);
                y += lineHeight + 2;

                // Обща печалба (Profit) най-долу
                double totalProfit = calculateTotalProfit();
                context.drawString(client.font, "§a§lProfit: §2$§a" + formatMoney(totalProfit), x, y, 0xFFFFFFFF, true);
            }
        );
    }

    private void checkDrop(String raw, String lower, String keyword, String cleanKey) {
        if (lower.contains(keyword) && !raw.contains(":")) {
            data.drops.put(cleanKey, data.drops.getOrDefault(cleanKey, 0) + 1);

            if (cleanKey.equals("Credit Capsule")) {
                data.dungeonsSinceCredit = 0;
            } else if (cleanKey.equals("Haunted Capsule")) {
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
            case "Credit Capsule" -> "§eCredit Capsule";
            case "Haunted Capsule" -> "§dHaunted Capsule";
            case "6-STAR CAPSULE" -> "§66-STAR CAPSULE";
            case "5-STAR CAPSULE" -> "§a5-STAR CAPSULE";
            case "DIVINE Drop" -> "§bDIVINE Drop";
            case "SECRET Drop" -> "§cSECRET Drop";
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
