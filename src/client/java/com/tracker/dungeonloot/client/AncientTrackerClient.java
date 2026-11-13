package com.tracker.dungeonloot.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

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
    private static long lastDungeonFinishTime = 0L;
    private static double lastScoreboardCredits = -1.0;

    public static class TrackerData {
        public int completed = 0;
        public double credits = 0.0;
        public double bossCredits = 0.0;
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

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("tracker")
                .then(ClientCommands.literal("reset").executes(ctx -> {
                    resetAllStats();
                    ctx.getSource().sendFeedback(Component.literal("§a[Ancient Tracker] Статистиката беше нулирана!"));
                    return 1;
                }))
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            double currentCredits = readScoreboardCredits(client);
            if (currentCredits >= 0) {
                if (lastScoreboardCredits >= 0) {
                    double diff = currentCredits - lastScoreboardCredits;
                    boolean justFinishedDungeon = (System.currentTimeMillis() - lastDungeonFinishTime) < 5000L;

                    if (Math.abs(diff - 0.5) < 0.05 && runStartTime == 0L && !justFinishedDungeon) {
                        data.bossCredits += 0.5;
                        saveData();
                    }
                }
                lastScoreboardCredits = currentCredits;
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String raw = message.getString().replaceAll("§[0-9a-fk-or]", "").trim();
            String lower = raw.toLowerCase();

            if (lower.contains("clear each wave to open the way forward") || lower.contains("10:00 on the clock")) {
                runStartTime = System.currentTimeMillis();
            }

            if (lower.contains("akhet, the buried sovereign is slain")) {
                lastDungeonFinishTime = System.currentTimeMillis();
                data.completed++;
                data.credits += 0.5;
                data.dungeonsSinceCredit++;
                data.dungeonsSinceHaunted++;

                if (runStartTime > 0) {
                    data.lastTimeSeconds = (System.currentTimeMillis() - runStartTime) / 1000L;
                    runStartTime = 0L;

                    if (data.lastTimeSeconds > 0 && (data.bestTimeSeconds == -1 || data.lastTimeSeconds < data.bestTimeSeconds)) {
                        data.bestTimeSeconds = data.lastTimeSeconds;
                    }
                }
                saveData();
            }

            Minecraft client = Minecraft.getInstance();
            String myName = (client.player != null) ? client.player.getName().getString().toLowerCase() : "_silentsin";

            boolean isMine = lower.contains(myName) || lower.contains("you looted") || lower.contains("you found") || lower.contains("you received");
            boolean isFakeSource = lower.contains("opened") || lower.contains("forged") || lower.contains("unboxed") || lower.contains("»") || lower.contains("@");

            if (isMine && !isFakeSource) {
                checkDrop(lower, "credit capsule", "CREDIT CAPSULE");
                checkDrop(lower, "haunted capsule", "HAUNTED CAPSULE");
                checkDrop(lower, "6-star capsule", "6-STAR CAPSULE");
            }
        });
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

                String bossCreditsText = (data.bossCredits % 1 == 0) ? String.valueOf((int) data.bossCredits) : String.format("%.1f", data.bossCredits);
                lines.add("§fBoss Credits: §b" + bossCreditsText);

                lines.add("§fBest Time: §a" + formatDuration(data.bestTimeSeconds));
                lines.add("§fLast Time: §7" + formatDuration(data.lastTimeSeconds));

                for (Map.Entry<String, Integer> entry : data.drops.entrySet()) {
                    String coloredName = getColoredDropName(entry.getKey());
                    lines.add(coloredName + "§f: §e" + entry.getValue());
                }

                lines.add("§fDungeons since last §b§lCREDIT CAPSULE§f: §6" + data.dungeonsSinceCredit);
                lines.add("§fDungeons since last §d§lHAUNTED CAPSULE§f: §6" + data.dungeonsSinceHaunted);

                double totalProfit = calculateTotalProfit();
                lines.add("§a§lProfit: §2$§a" + formatMoney(totalProfit));
                lines.add("§7Reset: §c/tracker reset");

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

                context.fill(boxLeft, boxTop, boxRight, boxBottom, 0xAA000000);
                int borderColor = 0xFF555555;
                context.fill(boxLeft, boxTop, boxRight, boxTop + 1, borderColor);
                context.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, borderColor);
                context.fill(boxLeft, boxTop, boxLeft + 1, boxBottom, borderColor);
                context.fill(boxRight - 1, boxTop, boxRight, boxBottom, borderColor);

                int currentY = y;
                for (String line : lines) {
                    context.text(client.font, line, x, currentY, 0xFFFFFFFF, true);
                    currentY += lineHeight;
                }
            }
        );
    }

    private double readScoreboardCredits(Minecraft client) {
        try {
            Scoreboard sb = client.level.getScoreboard();
            Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (obj == null) return -1.0;

            for (ScoreHolder holder : sb.getTrackedPlayers()) {
                PlayerTeam team = sb.getPlayersTeam(holder.getScoreboardName());
                String fullLine = PlayerTeam.formatNameForTeam(team, Component.literal(holder.getScoreboardName())).getString();
                String clean = fullLine.replaceAll("§[0-9a-fk-or]", "").trim();
                if (clean.toLowerCase().contains("credits:")) {
                    Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(clean);
                    if (m.find()) {
                        return Double.parseDouble(m.group(1));
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1.0;
    }

    private void checkDrop(String lower, String keyword, String cleanKey) {
        if (lower.contains(keyword)) {
            data.drops.put(cleanKey, data.drops.getOrDefault(cleanKey, 0) + 1);

            if (cleanKey.equals("CREDIT CAPSULE")) {
                data.dungeonsSinceCredit = 0;
            } else if (cleanKey.equals("HAUNTED CAPSULE")) {
                data.dungeonsSinceHaunted = 0;
            }
            saveData();
        }
    }

    private void resetAllStats() {
        Map<String, Double> savedPrices = data.prices;
        data = new TrackerData();
        data.prices = savedPrices;
        runStartTime = 0L;
        saveData();
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
            case "CREDIT CAPSULE" -> "§b§lCREDIT CAPSULE";
            case "HAUNTED CAPSULE" -> "§d§lHAUNTED CAPSULE";
            case "6-STAR CAPSULE" -> "§6§l6-STAR CAPSULE ☆☆☆☆☆☆";
            default -> "§f§l" + key;
        };
    }

    private String formatMoney(double amount) {
        if (amount >= 1_000_000_000) return String.format("%.2fB", amount / 1_000_000_000.0);
        if (amount >= 1_000_000) return String.format("%.2fM", amount / 1_000_000.0);
        if (amount >= 1_000) return String.format("%.1fK", amount / 1_000.0);
        return String.format("%.0f", amount);
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
