package me.countergd.launcher.service;

import com.google.gson.*;
import java.util.Locale;
import java.util.Map;

final class RuleEvaluator {
    private RuleEvaluator() {}
    static boolean allowed(JsonObject item) {
        if (!item.has("rules")) return true;
        boolean allow = false;
        JsonArray rules = item.getAsJsonArray("rules");
        for (JsonElement el : rules) {
            JsonObject rule = el.getAsJsonObject();
            if (matches(rule.has("os") ? rule.getAsJsonObject("os") : null,
                    rule.has("features") ? rule.getAsJsonObject("features") : null)) {
                allow = rule.has("action") && "allow".equals(rule.get("action").getAsString());
            }
        }
        return allow;
    }
    private static boolean matches(JsonObject os, JsonObject features) {
        String name = osName();
        if (os != null) {
            if (os.has("name") && !name.equals(os.get("name").getAsString())) return false;
            if (os.has("arch") && !System.getProperty("os.arch", "").matches(os.get("arch").getAsString())) return false;
            if (os.has("version") && !System.getProperty("os.version", "").matches(os.get("version").getAsString())) return false;
        }
        // The launcher currently supplies no demo/custom resolution features.
        if (features != null) for (Map.Entry<String, JsonElement> e : features.entrySet()) {
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsBoolean()) return false;
        }
        return true;
    }
    static String osName() {
        String n = System.getProperty("os.name", "linux").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return "windows";
        if (n.contains("mac") || n.contains("darwin")) return "osx";
        return "linux";
    }
}
