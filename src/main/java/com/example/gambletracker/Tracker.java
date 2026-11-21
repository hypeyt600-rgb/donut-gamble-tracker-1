package com.example.gambletracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Tracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("gambletracker.json");

    // Wins: "You won $X", "You received $X", ... and incoming payments: "<Player> paid you $X".
    // The suffix (k/m/b/t) only counts if it is NOT followed by a letter, so "$5 to play" stays 5.
    static final String DEFAULT_WIN =
            "(?i)(?:you (?:won|win|received|collected)|paid you)\\s*\\$?(\\d[\\d.,]*(?:\\s?[kmbt](?![a-z]))?)";
    static final String DEFAULT_LOSS =
            "(?i)you (?:lost|lose)\\s*\\$?(\\d[\\d.,]*(?:\\s?[kmbt](?![a-z]))?)";

    // Older saved defaults. If the config still holds one of these, it is upgraded automatically.
    private static final String OLD_WIN_1 =
            "(?i)you (?:won|win|received|collected)\\s*\\$?([\\d.,]+\\s?[kmbt]?)";
    private static final String OLD_WIN_2 =
            "(?i)(?:you (?:won|win|received|collected)|paid you)\\s*\\$?([\\d.,]+\\s?[kmbt]?)";
    private static final String OLD_LOSS =
            "(?i)you (?:lost|lose)\\s*\\$?([\\d.,]+\\s?[kmbt]?)";

    public double profit;
    public double biggestWin;
    public double biggestLoss;   // stored as a positive number
    public double totalWon;
    public double totalLost;     // stored as a positive number
    public int wins;
    public int losses;
    public int streak;           // > 0 = win streak, < 0 = loss streak
    public boolean hudVisible = true;
    public int hudMode = 0;      // 0 = full, 1 = compact, 2 = minimal
    public int hudX = 6;
    public int hudY = 6;
    public String winRegex = DEFAULT_WIN;
    public String lossRegex = DEFAULT_LOSS;

    public transient long sessionStart = System.currentTimeMillis();
    public transient double sessionProfit;
    public transient int sessionWins;
    public transient int sessionLosses;
    private transient Pattern winP;
    private transient Pattern lossP;

    public Tracker() {
        compile();
    }

    public static Tracker load() {
        Tracker t = null;
        try {
            if (Files.exists(FILE)) {
                t = GSON.fromJson(Files.readString(FILE), Tracker.class);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (t == null) t = new Tracker();

        if (t.winRegex == null || t.winRegex.isBlank()
                || t.winRegex.equals(OLD_WIN_1) || t.winRegex.equals(OLD_WIN_2)) {
            t.winRegex = DEFAULT_WIN;
        }
        if (t.lossRegex == null || t.lossRegex.isBlank() || t.lossRegex.equals(OLD_LOSS)) {
            t.lossRegex = DEFAULT_LOSS;
        }
        t.sessionStart = System.currentTimeMillis();
        t.compile();
        t.save();
        return t;
    }

    public void save() {
        try {
            Files.writeString(FILE, GSON.toJson(this));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void compile() {
        try {
            winP = Pattern.compile(winRegex);
        } catch (PatternSyntaxException | NullPointerException e) {
            winRegex = DEFAULT_WIN;
            winP = Pattern.compile(DEFAULT_WIN);
        }
        try {
            lossP = Pattern.compile(lossRegex);
        } catch (PatternSyntaxException | NullPointerException e) {
            lossRegex = DEFAULT_LOSS;
            lossP = Pattern.compile(DEFAULT_LOSS);
        }
    }

    /** Returns true if the line was recognised as a win or a loss and recorded. */
    public boolean onChat(String line) {
        if (line == null || line.isEmpty()) return false;
        if (winP == null || lossP == null) compile();

        Matcher m = winP.matcher(line);
        if (m.find()) {
            Double amt = parse(m.group(1));
            if (amt != null && amt > 0) {
                record(amt);
                return true;
            }
        }
        m = lossP.matcher(line);
        if (m.find()) {
            Double amt = parse(m.group(1));
            if (amt != null && amt > 0) {
                record(-amt);
                return true;
            }
        }
        return false;
    }

    public void record(double delta) {
        profit += delta;
        sessionProfit += delta;
        if (delta >= 0) {
            wins++;
            sessionWins++;
            totalWon += delta;
            biggestWin = Math.max(biggestWin, delta);
            streak = streak > 0 ? streak + 1 : 1;
        } else {
            losses++;
            sessionLosses++;
            totalLost += -delta;
            biggestLoss = Math.max(biggestLoss, -delta);
            streak = streak < 0 ? streak - 1 : -1;
        }
        save();
    }

    public void resetAll() {
        profit = 0;
        biggestWin = 0;
        biggestLoss = 0;
        totalWon = 0;
        totalLost = 0;
        wins = 0;
        losses = 0;
        streak = 0;
        resetSession();
        save();
    }

    public void resetSession() {
        sessionStart = System.currentTimeMillis();
        sessionProfit = 0;
        sessionWins = 0;
        sessionLosses = 0;
    }

    /** Win percentage, 0-100. */
    public double winRate() {
        int n = wins + losses;
        return n == 0 ? 0 : wins * 100.0 / n;
    }

    /** Session profit per hour. */
    public double perHour() {
        double hrs = (System.currentTimeMillis() - sessionStart) / 3_600_000.0;
        return sessionProfit / Math.max(hrs, 1.0 / 60.0);
    }

    /** "2.5m" -> 2500000.0, "1,250" -> 1250.0, null if it can't be read. */
    public static Double parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase().replace(",", "").replace(" ", "");
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        if (t.isEmpty()) return null;

        double mult = 1;
        char c = t.charAt(t.length() - 1);
        switch (c) {
            case 'k': mult = 1e3; break;
            case 'm': mult = 1e6; break;
            case 'b': mult = 1e9; break;
            case 't': mult = 1e12; break;
            default: break;
        }
        if (mult != 1) t = t.substring(0, t.length() - 1);

        try {
            return Double.parseDouble(t) * mult;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 2500000 -> "$2.50M", -1500 -> "-$1.5K" */
    public static String fmt(double v) {
        String sign = v < 0 ? "-" : "";
        double a = Math.abs(v);
        String out;
        if (a >= 1e12) out = String.format("%.2fT", a / 1e12);
        else if (a >= 1e9) out = String.format("%.2fB", a / 1e9);
        else if (a >= 1e6) out = String.format("%.2fM", a / 1e6);
        else if (a >= 1e3) out = String.format("%.1fK", a / 1e3);
        else out = String.format("%.0f", a);
        return sign + "$" + out;
    }
}
