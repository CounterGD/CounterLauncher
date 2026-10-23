package me.countergd.launcher.service;

import com.google.gson.*;
import me.countergd.launcher.model.GameVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class VersionService {
    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private final HttpService http; private final Path root;
    public VersionService(HttpService http, Path root) { this.http = http; this.root = root; }

    public List<GameVersion> fetchVersions() throws IOException, InterruptedException {
        String json = http.getText(MANIFEST_URL); Files.createDirectories(root);
        Files.writeString(root.resolve("version_manifest_v2.json"), json);
        JsonArray versions = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("versions");
        List<GameVersion> result = new ArrayList<>();
        for (JsonElement e : versions) { JsonObject o=e.getAsJsonObject(); result.add(new GameVersion(
                o.get("id").getAsString(), o.get("type").getAsString(), o.get("url").getAsString(),
                o.has("releaseTime") ? o.get("releaseTime").getAsString() : "")); }
        result.sort(Comparator.comparing(GameVersion::releaseTime).reversed()); return result;
    }

    public JsonObject fetchVersionMetadata(GameVersion version) throws IOException, InterruptedException {
        Path file = root.resolve("versions").resolve(version.id()).resolve(version.id()+".json");
        Files.createDirectories(file.getParent());
        // Metadata is small and cheap to re-fetch; refreshing avoids stale manifests.
        String json = http.getText(version.url()); Files.writeString(file, json);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    public Path downloadClient(GameVersion version) throws IOException, InterruptedException {
        JsonObject metadata = fetchVersionMetadata(version);
        JsonObject client = metadata.has("downloads") ? metadata.getAsJsonObject("downloads").getAsJsonObject("client") : null;
        if (client == null) throw new IOException("Version " + version.id() + " has no client download.");
        Path destination = root.resolve("versions").resolve(version.id()).resolve(version.id()+".jar");
        http.downloadVerified(client.get("url").getAsString(), destination,
                client.has("sha1") ? client.get("sha1").getAsString() : null,
                client.has("size") ? client.get("size").getAsLong() : -1);
        return destination;
    }
    public Path getRoot() { return root; }
    public HttpService http() { return http; }
}
