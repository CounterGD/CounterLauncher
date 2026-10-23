package me.countergd.launcher.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.countergd.launcher.model.ElyByAccount;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class LaunchService {

    public Process launch(
            String javaExecutable,
            MinecraftInstaller.InstallResult installed,
            ElyByAccount account,
            int minRamMb,
            int maxRamMb,
            String launcherVersion
    ) throws Exception {

        if (account == null) {
            throw new IOException(
                    "Sign in to Ely.by before launching Minecraft."
            );
        }

        JsonObject metadata = installed.metadata();

        boolean modernArguments =
                metadata.has("arguments")
                && metadata.get("arguments").isJsonObject()
                && metadata.getAsJsonObject("arguments").has("game")
                && metadata.getAsJsonObject("arguments")
                        .get("game").isJsonArray();

        boolean legacyArguments =
                metadata.has("minecraftArguments")
                && metadata.get("minecraftArguments").isJsonPrimitive();

        if (!modernArguments && !legacyArguments) {
            throw new IOException(
                    "Unsupported version metadata: expected arguments.game "
                    + "or minecraftArguments."
            );
        }

        if (!metadata.has("mainClass")
                || !metadata.get("mainClass").isJsonPrimitive()) {
            throw new IOException("Version metadata is missing mainClass.");
        }

        Path clientJar = installed.clientJar();

        if (clientJar == null || !Files.isRegularFile(clientJar)) {
            throw new IOException("Minecraft client JAR is missing.");
        }

        Path installRoot = clientJar.getParent()
                .getParent()
                .getParent();

        Path gameDir = installRoot.resolve("game");
        Files.createDirectories(gameDir);

        Path nativeLibraryRoot = prepareNativeLibraries(
                installed.nativesDir()
        );

        String classpath = buildClasspath(
                installed.libraries(),
                clientJar
        );

        Map<String, String> vars = new HashMap<>();

        vars.put("${auth_player_name}", account.username());
        vars.put("${version_name}", installed.version().id());
        vars.put("${game_directory}", gameDir.toString());
        vars.put("${assets_root}", installed.assetsDir().toString());
        vars.put("${game_assets}", installed.assetsDir().toString());
        vars.put("${auth_uuid}", account.uuid().replace("-", ""));
        vars.put("${auth_access_token}", account.accessToken());
        vars.put("${auth_session}", account.accessToken());
        vars.put("${user_properties}", "{}");
        vars.put("${clientid}", "");
        vars.put("${auth_xuid}", "");
        vars.put("${user_type}", "mojang");
        vars.put("${launcher_name}", "CounterLauncher");
        vars.put("${launcher_version}", launcherVersion);
        vars.put("${classpath}", classpath);
        vars.put(
                "${classpath_separator}",
                System.getProperty("path.separator")
        );
        vars.put(
                "${library_directory}",
                installRoot.resolve("libraries").toString()
        );
        vars.put("${natives_directory}", nativeLibraryRoot.toString());

        String assetIndexName = "legacy";

        if (metadata.has("assetIndex")
                && metadata.get("assetIndex").isJsonObject()
                && metadata.getAsJsonObject("assetIndex").has("id")) {
            assetIndexName = metadata.getAsJsonObject("assetIndex")
                    .get("id").getAsString();
        } else if (metadata.has("assets")
                && metadata.get("assets").isJsonPrimitive()) {
            assetIndexName = metadata.get("assets").getAsString();
        }

        vars.put("${assets_index_name}", assetIndexName);

        String versionType = metadata.has("type")
                && metadata.get("type").isJsonPrimitive()
                ? metadata.get("type").getAsString()
                : "release";

        vars.put("${version_type}", versionType);

        Path loggingConfig = installed.loggingConfigFile();

        vars.put(
                "${path}",
                loggingConfig == null ? "" : loggingConfig.toString()
        );

        List<String> cmd = new ArrayList<>();

        cmd.add(javaExecutable);
        cmd.add("-Xms" + minRamMb + "M");
        cmd.add("-Xmx" + maxRamMb + "M");

        Path injectorJar = installed.authlibInjectorJar();

        if (injectorJar == null || !Files.isRegularFile(injectorJar)) {
            throw new IOException(
                    "Ely.by authlib-injector JAR is missing."
            );
        }

        cmd.add("-javaagent:" + injectorJar + "=ely.by");

        /*
         * Parse JVM arguments from modern metadata when available.
         * Legacy manifests may not contain an arguments.jvm array.
         */
        if (modernArguments) {
            JsonObject arguments = metadata.getAsJsonObject("arguments");

            if (arguments.has("jvm")
                    && arguments.get("jvm").isJsonArray()) {
                appendArguments(
                        cmd,
                        arguments.getAsJsonArray("jvm"),
                        vars
                );
            }
        }

        /*
         * Add native-library properties AFTER metadata JVM arguments,
         * so metadata cannot override the ARM64 native search paths.
         */
        cmd.add("-Djava.library.path=" + nativeLibraryRoot);
        cmd.add("-Dorg.lwjgl.librarypath=" + nativeLibraryRoot);
        cmd.add("-Dorg.lwjgl.util.Debug=true");

        /*
         * Add the logging configuration argument when available.
         */
        if (loggingConfig != null
                && metadata.has("logging")
                && metadata.get("logging").isJsonObject()) {

            JsonObject logging = metadata.getAsJsonObject("logging");

            if (logging.has("client")
                    && logging.get("client").isJsonObject()) {

                JsonObject clientLogging =
                        logging.getAsJsonObject("client");

                if (clientLogging.has("argument")
                        && clientLogging.get("argument").isJsonPrimitive()) {
                    cmd.add(substitute(
                            clientLogging.get("argument").getAsString(),
                            vars
                    ));
                }
            }
        }

        /*
         * Add the classpath unless metadata already supplied one.
         */
        if (!cmd.contains("-cp") && !cmd.contains("-classpath")) {
            cmd.add("-cp");
            cmd.add(classpath);
        }

        cmd.add(metadata.get("mainClass").getAsString());

        /*
         * Modern versions use arguments.game.
         * Legacy versions use a single minecraftArguments string.
         */
        if (modernArguments) {
            appendArguments(
                    cmd,
                    metadata.getAsJsonObject("arguments")
                            .getAsJsonArray("game"),
                    vars
            );
        } else {
            String legacy = metadata.get("minecraftArguments")
                    .getAsString();

            for (String argument : parseLegacyArguments(legacy)) {
                cmd.add(substitute(argument, vars));
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        processBuilder.directory(gameDir.toFile());
        processBuilder.inheritIO();

        return processBuilder.start();
    }

    private static Path prepareNativeLibraries(Path nativesDir)
            throws IOException {

        if (nativesDir == null) {
            throw new IOException("Native-library directory is missing.");
        }

        Path flatDirectory = nativesDir.resolve("flat-libs");
        Files.createDirectories(flatDirectory);

        /*
         * Recursively find extracted native libraries. This supports
         * the existing linux/arm64/org/lwjgl layout and other layouts.
         */
        if (Files.isDirectory(nativesDir)) {
            try (var paths = Files.walk(nativesDir)) {
                for (Path file : paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName()
                                .toString().endsWith(".so"))
                        .filter(path -> !path.startsWith(flatDirectory))
                        .toList()) {

                    Path destination = flatDirectory.resolve(
                            file.getFileName().toString()
                    );

                    if (!file.toAbsolutePath().normalize()
                            .equals(destination.toAbsolutePath().normalize())) {
                        Files.copy(
                                file,
                                destination,
                                StandardCopyOption.REPLACE_EXISTING
                        );
                    }
                }
            }
        }

        return flatDirectory;
    }

    private static String buildClasspath(
            List<Path> libraries,
            Path clientJar
    ) {
        List<Path> entries = new ArrayList<>();

        if (libraries != null) {
            entries.addAll(libraries);
        }

        entries.add(clientJar);

        return entries.stream()
                .map(Path::toString)
                .collect(Collectors.joining(
                        System.getProperty("path.separator")
                ));
    }

    private static void appendArguments(
            List<String> output,
            JsonArray arguments,
            Map<String, String> vars
    ) throws IOException {

        for (JsonElement element : arguments) {
            if (element.isJsonPrimitive()) {
                output.add(substitute(element.getAsString(), vars));
                continue;
            }

            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject object = element.getAsJsonObject();

            if (!RuleEvaluator.allowed(object)
                    || !object.has("value")) {
                continue;
            }

            JsonElement value = object.get("value");

            if (value.isJsonPrimitive()) {
                output.add(substitute(value.getAsString(), vars));
            } else if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        output.add(substitute(
                                item.getAsString(),
                                vars
                        ));
                    }
                }
            }
        }
    }

    /**
     * Splits legacy minecraftArguments into command-line arguments.
     * Quoted whitespace is preserved, and no shell is involved.
     */
    private static List<String> parseLegacyArguments(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean quoted = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '"' ) {
                quoted = !quoted;
                continue;
            }

            if (c == '\\' && quoted && i + 1 < input.length()) {
                char next = input.charAt(i + 1);

                if (next == '"' || next == '\\') {
                    current.append(next);
                    i++;
                    continue;
                }
            }

            if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(c);
        }

        if (quoted) {
            throw new IllegalArgumentException(
                    "Unterminated quote in minecraftArguments."
            );
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }

        return result;
    }

    private static String substitute(
            String input,
            Map<String, String> vars
    ) {
        String output = input;

        for (Map.Entry<String, String> entry : vars.entrySet()) {
            output = output.replace(
                    entry.getKey(),
                    entry.getValue()
            );
        }

        return output;
    }
}

