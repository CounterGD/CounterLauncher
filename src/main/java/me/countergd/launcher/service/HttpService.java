package me.countergd.launcher.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

public final class HttpService {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(25))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String getText(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "CounterLauncher/0.2.0")
                .header("Accept", "application/json, */*")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("GET returned HTTP " + response.statusCode() + " for " + safeUrl(url));
        return response.body();
    }

    public String postForm(String url, String form) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "CounterLauncher/0.2.0")
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("OAuth request returned HTTP " + response.statusCode() + ": " + truncate(response.body()));
        return response.body();
    }

    public String getAuthorizedText(String url, String bearer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer " + bearer)
                .header("User-Agent", "CounterLauncher/0.2.0").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("API request returned HTTP " + response.statusCode() + ": " + truncate(response.body()));
        return response.body();
    }

    public void download(String url, Path destination) throws IOException, InterruptedException {
        downloadVerified(url, destination, null, -1);
    }

    public void downloadVerified(String url, Path destination, String expectedSha1, long expectedSize)
            throws IOException, InterruptedException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        if (Files.exists(destination) && valid(destination, expectedSha1, expectedSize)) return;
        Files.deleteIfExists(destination);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(temporary);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(15))
                .header("User-Agent", "CounterLauncher/0.2.0").GET().build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(temporary);
            throw new IOException("Download returned HTTP " + response.statusCode() + " for " + safeUrl(url));
        }
        if (!valid(temporary, expectedSha1, expectedSize)) {
            Files.deleteIfExists(temporary);
            throw new IOException("Downloaded file failed size/SHA-1 verification: " + destination.getFileName());
        }
        try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void downloadVerifiedSha256(String url, Path destination, String expectedSha256)
            throws IOException, InterruptedException {
        download(url, destination);
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var in = Files.newInputStream(destination)) {
                    byte[] buffer = new byte[64 * 1024]; int read;
                    while ((read = in.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equalsIgnoreCase(expectedSha256)) {
                    Files.deleteIfExists(destination);
                    throw new IOException("Downloaded authlib-injector failed SHA-256 verification.");
                }
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }

    private boolean valid(Path path, String sha1, long size) throws IOException {
        if (!Files.isRegularFile(path)) return false;
        if (size >= 0 && Files.size(path) != size) return false;
        if (sha1 != null && !sha1.isBlank() && !sha1.equalsIgnoreCase(sha1(path))) return false;
        return Files.size(path) > 0;
    }

    public static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = in.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String safeUrl(String url) { return url.replaceAll("(?i)(access_token|token|code|secret)=[^&]+", "$1=<redacted>"); }
    private static String truncate(String s) { return s == null ? "" : s.substring(0, Math.min(500, s.length())); }
}
