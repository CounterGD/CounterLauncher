package me.countergd.launcher.service;

import com.google.gson.*;
import me.countergd.launcher.model.GameVersion;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs official version metadata, client, applicable library artifacts, natives and asset objects. */
public final class MinecraftInstaller {
    public record InstallResult(GameVersion version, JsonObject metadata, Path clientJar,
                                List<Path> libraries, Path nativesDir, Path assetsDir,
                                Path assetIndexFile, Path loggingConfigFile, Path authlibInjectorJar) {}
    private final VersionService versions;
    private final Path root;
    public MinecraftInstaller(VersionService versions, Path root) { this.versions=versions; this.root=root; }

    public InstallResult install(GameVersion version, Consumer<String> progress) throws Exception {
        Consumer<String> log = progress == null ? s -> {} : progress;
        log.accept("Fetching metadata for " + version.id());
        JsonObject metadata = versions.fetchVersionMetadata(version);
        Path versionDir = root.resolve("versions").resolve(version.id());
        Files.createDirectories(versionDir);
        JsonObject client = metadata.getAsJsonObject("downloads").getAsJsonObject("client");
        Path clientJar = versionDir.resolve(version.id()+".jar");
        versions.http().downloadVerified(client.get("url").getAsString(), clientJar,
                string(client,"sha1"), number(client,"size",-1));
        Path librariesDir = root.resolve("libraries");
        Path nativesDir = root.resolve("versions").resolve(version.id()).resolve("natives");
        Files.createDirectories(nativesDir);
        List<Path> classpath = new ArrayList<>();
        List<RunnableDownload> jobs = new ArrayList<>();
        JsonArray libs = metadata.has("libraries") ? metadata.getAsJsonArray("libraries") : new JsonArray();
        boolean linuxArm64 = RuleEvaluator.osName().equals("linux") && isArm64();
        for (JsonElement element : libs) {
            JsonObject lib=element.getAsJsonObject();
            if (!RuleEvaluator.allowed(lib)) continue;
            String coordinate = lib.has("name") ? lib.get("name").getAsString() : "";
            JsonObject downloads = lib.has("downloads") && lib.get("downloads").isJsonObject()
                    ? lib.getAsJsonObject("downloads") : new JsonObject();
            JsonObject artifact = downloads.has("artifact") ? downloads.getAsJsonObject("artifact") : null;

            // Newer Minecraft metadata may list native classifier JARs as separate libraries
            // (for example org.lwjgl:lwjgl:3.4.1:natives-linux). They must be extracted, not
            // placed on the Java classpath, or LWJGL detects x64 natives on ARM64.
            boolean isNativeArtifact = isNativeCoordinate(coordinate);
            if (artifact != null && artifact.has("url")) {
                String effectiveCoordinate = coordinate;
                String url = artifact.get("url").getAsString();
                String sha1 = string(artifact,"sha1");
                long size = number(artifact,"size",-1);
                boolean skip = false;
                if (isNativeArtifact && linuxArm64) {
                    String armCoordinate = lwjglArm64Coordinate(coordinate);
                    if (armCoordinate != null) {
                        effectiveCoordinate = armCoordinate;
                        url = mavenCentralUrl(armCoordinate);
                        sha1 = null; size = -1;
                    } else {
                        log.accept("Skipping incompatible Linux native on ARM64: " + coordinate);
                        skip = true;
                    }
                }
                if (!skip) {
                    Path dest = librariesDir.resolve(isNativeArtifact ? mavenPath(effectiveCoordinate)
                            : (artifact.has("path") ? artifact.get("path").getAsString() : mavenPath(effectiveCoordinate)));
                    jobs.add(new RunnableDownload(dest,url,sha1,size,isNativeArtifact ? "native" : "library"));
                    if (!isNativeArtifact) classpath.add(dest);
                }
            } else if (lib.has("name") && lib.has("url") && downloads.size()==0 && !coordinate.contains("native")) {
                Path dest=librariesDir.resolve(mavenPath(coordinate));
                jobs.add(new RunnableDownload(dest,lib.get("url").getAsString()+mavenPath(coordinate),null,-1,"library"));
                classpath.add(dest);
            }

            JsonObject classifiers = downloads.has("classifiers") ? downloads.getAsJsonObject("classifiers") : null;
            JsonObject natives = lib.has("natives") ? lib.getAsJsonObject("natives") : null;
            if (classifiers != null && natives != null && natives.has(RuleEvaluator.osName())) {
                String classifier = natives.get(RuleEvaluator.osName()).getAsString()
                        .replace("${arch}", System.getProperty("os.arch", "").contains("64") ? "64" : "32");
                JsonObject nativeObj = null;
                String nativeCoordinate = null;
                String nativeUrl = null;
                String nativeSha1 = null;
                long nativeSize = -1;
                if (linuxArm64) {
                    if (classifiers.has("natives-linux-arm64")) {
                        classifier = "natives-linux-arm64";
                        nativeObj = classifiers.getAsJsonObject(classifier);
                    } else {
                        String armCoordinate = lwjglArm64Coordinate(coordinate + ":" + classifier);
                        if (armCoordinate != null) {
                            nativeCoordinate = armCoordinate;
                            nativeUrl = mavenCentralUrl(armCoordinate);
                        } else {
                            log.accept("No ARM64 native published in metadata for " + coordinate + "; skipping " + classifier);
                        }
                    }
                } else if (classifiers.has(classifier)) {
                    nativeObj = classifiers.getAsJsonObject(classifier);
                }
                if (nativeObj != null) {
                    nativeCoordinate = coordinate + ":" + classifier;
                    nativeUrl = nativeObj.get("url").getAsString();
                    nativeSha1 = string(nativeObj,"sha1");
                    nativeSize = number(nativeObj,"size",-1);
                }
                if (nativeCoordinate != null && nativeUrl != null) {
                    Path dest = librariesDir.resolve(mavenPath(nativeCoordinate));
                    jobs.add(new RunnableDownload(dest,nativeUrl,nativeSha1,nativeSize,"native"));
                }
            }
        }
        downloadJobs(jobs, log);
        for (RunnableDownload job : jobs) {
            if (job.kind.equals("native")) extractNativeJar(job.destination, nativesDir);
        }
        // Native libraries are not on the Java classpath.
        for (RunnableDownload job : jobs) if (job.kind.equals("library")) classpath.add(job.destination);
        classpath = new ArrayList<>(new LinkedHashSet<>(classpath));

        Path assetsDir=root.resolve("assets"); Files.createDirectories(assetsDir);
        Path indexFile=null;
        if (metadata.has("assetIndex")) {
            JsonObject index=metadata.getAsJsonObject("assetIndex");
            String indexId=index.has("id") ? index.get("id").getAsString() : metadata.get("assets").getAsString();
            indexFile=assetsDir.resolve("indexes").resolve(indexId+".json");
            versions.http().downloadVerified(index.get("url").getAsString(), indexFile,
                    string(index,"sha1"), number(index,"size",-1));
            JsonObject indexJson=JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
            JsonObject objects=indexJson.getAsJsonObject("objects");
            List<RunnableDownload> assetJobs=new ArrayList<>();
            for (Map.Entry<String,JsonElement> entry : objects.entrySet()) {
                JsonObject obj=entry.getValue().getAsJsonObject(); String hash=obj.get("hash").getAsString();
                Path dest=assetsDir.resolve("objects").resolve(hash.substring(0,2)).resolve(hash);
                String url="https://resources.download.minecraft.net/"+hash.substring(0,2)+"/"+hash;
                assetJobs.add(new RunnableDownload(dest,url,hash,number(obj,"size",-1),"asset"));
            }
            downloadJobs(assetJobs, log);
            log.accept("Assets ready: " + assetJobs.size() + " objects.");
            if (indexJson.has("virtual") && indexJson.get("virtual").getAsBoolean()) {
                Path virtual=assetsDir.resolve("virtual").resolve(indexId); Files.createDirectories(virtual);
                for (Map.Entry<String,JsonElement> entry : objects.entrySet()) {
                    JsonObject obj=entry.getValue().getAsJsonObject(); String hash=obj.get("hash").getAsString();
                    Path src=assetsDir.resolve("objects").resolve(hash.substring(0,2)).resolve(hash);
                    Path dst=virtual.resolve(entry.getKey()); Files.createDirectories(dst.getParent());
                    if (!Files.exists(dst)) try { Files.createLink(dst,src); } catch (Exception e) { Files.copy(src,dst,StandardCopyOption.REPLACE_EXISTING); }
                }
            }
        } else {
            log.accept("This version has no assetIndex metadata; legacy asset layout may be required.");
        }
        Path authlibInjectorJar = root.resolve("libraries").resolve("authlib-injector.jar");
        log.accept("Checking Ely.by authlib-injector...");
        JsonObject injectorMeta = JsonParser.parseString(versions.http().getText("https://authlib-injector.yushi.moe/artifact/latest.json")).getAsJsonObject();
        JsonObject checksums = injectorMeta.has("checksums") ? injectorMeta.getAsJsonObject("checksums") : new JsonObject();
        versions.http().downloadVerifiedSha256(injectorMeta.get("download_url").getAsString(), authlibInjectorJar,
                string(checksums, "sha256"));

        Path loggingConfig = null;
        if (metadata.has("logging") && metadata.getAsJsonObject("logging").has("client")) {
            JsonObject loggingClient = metadata.getAsJsonObject("logging").getAsJsonObject("client");
            if (loggingClient.has("file")) {
                JsonObject file = loggingClient.getAsJsonObject("file");
                Path logDir = assetsDir.resolve("log_configs");
                loggingConfig = logDir.resolve(file.get("id").getAsString());
                versions.http().downloadVerified(file.get("url").getAsString(), loggingConfig,
                        string(file, "sha1"), number(file, "size", -1));
            }
        }
        log.accept("Install complete: " + version.id() + " (" + classpath.size() + " library JARs).");
        return new InstallResult(version, metadata, clientJar, classpath, nativesDir, assetsDir, indexFile, loggingConfig, authlibInjectorJar);
    }

    private void downloadJobs(List<RunnableDownload> jobs, Consumer<String> log) throws Exception {
        if (jobs.isEmpty()) return;
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors())));
        try {
            List<Future<?>> futures=new ArrayList<>(); int total=jobs.size();
            for (int i=0;i<jobs.size();i++) { final int n=i+1; RunnableDownload job=jobs.get(i);
                futures.add(pool.submit(() -> { try { versions.http().downloadVerified(job.url,job.destination,job.sha1,job.size); }
                    catch (Exception e) { throw new CompletionException(new IOException("Failed " + job.kind + " download: " + job.destination, e)); }
                    if (n==total || n%25==0) log.accept("Downloading " + job.kind + "s: " + n + "/" + total); })); }
            for (Future<?> f:futures) try { f.get(); } catch (ExecutionException e) {
                Throwable cause=e.getCause(); if (cause instanceof CompletionException && cause.getCause()!=null) cause=cause.getCause();
                if (cause instanceof Exception ex) throw ex; throw new RuntimeException(cause);
            }
        } finally { pool.shutdownNow(); }
    }
    private static void extractNativeJar(Path jar, Path dest) throws IOException {
        try (ZipInputStream in=new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry=in.getNextEntry())!=null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                Path out=dest.resolve(entry.getName()).normalize(); if (!out.startsWith(dest.normalize())) continue;
                Files.createDirectories(out.getParent()); Files.copy(in,out,StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    private static boolean isArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64");
    }
    private static boolean isNativeCoordinate(String coordinate) {
        String[] p = coordinate.split(":");
        return p.length >= 4 && p[3].startsWith("natives-");
    }
    /** Returns an LWJGL Linux ARM64 Maven coordinate when this is an LWJGL Linux native. */
    private static String lwjglArm64Coordinate(String coordinate) {
        String[] p = coordinate.split(":");
        if (p.length < 4 || !p[0].equals("org.lwjgl")) return null;
        if (p[3].equals("natives-linux-arm64")) return coordinate;
        if (!p[3].equals("natives-linux")) return null;
        p[3] = "natives-linux-arm64";
        return String.join(":", p);
    }
    private static String mavenCentralUrl(String coordinate) {
        return "https://repo.maven.apache.org/maven2/" + mavenPath(coordinate);
    }
    private static String mavenPath(String coordinate) {
        String[] p=coordinate.split(":"); if(p.length<3) return coordinate.replace(':','/') + ".jar";
        String group=p[0].replace('.','/'), artifact=p[1], version=p[2];
        String classifier=p.length>3 ? "-"+p[3] : "";
        String ext=p.length>4 ? p[4] : "jar";
        return group+"/"+artifact+"/"+version+"/"+artifact+"-"+version+classifier+"."+ext;
    }
    private static String string(JsonObject o,String k) { return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():null; }
    private static long number(JsonObject o,String k,long d) { return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsLong():d; }
    private record RunnableDownload(Path destination,String url,String sha1,long size,String kind) {}
}
