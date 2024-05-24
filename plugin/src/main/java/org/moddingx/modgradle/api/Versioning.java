package org.moddingx.modgradle.api;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.gradle.api.Project;
import org.gradle.api.invocation.Gradle;
import org.moddingx.modgradle.ModGradle;
import org.moddingx.modgradle.util.StringUtil;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Utilities for versioning and to get data for a minecraft version..
 */
public class Versioning {

    private static final Object LOCK = new Object();
    private static Map<String, VersionInfo> VERSION_MAP = null;

    private static Map<String, VersionInfo> versionMap() {
        synchronized (LOCK) {
            if (VERSION_MAP == null) {
                try {
                    URL url = new URL("https://assets.moddingx.org/versions.json");
                    
                    Gradle gradle = ModGradle.gradle();
                    Path cachePath = null;
                    if (gradle != null) {
                        cachePath = gradle.getGradleUserHomeDir().toPath().resolve("caches").resolve("modgradle").resolve("versions.json").toAbsolutePath().normalize();
                    }
                    
                    JsonObject json = null;
                    try (Reader in = new InputStreamReader(url.openStream())) {
                        json = ModGradle.INTERNAL.fromJson(in, JsonObject.class);
                        if (cachePath != null) {
                            try {
                                Files.createDirectories(cachePath.getParent());
                                Writer writer = Files.newBufferedWriter(cachePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                writer.write(ModGradle.INTERNAL.toJson(json) + "\n");
                                writer.close();
                            } catch (IOException e) {
                                Files.deleteIfExists(cachePath);
                            }
                        }
                    } catch (IOException | JsonSyntaxException e) {
                        if (json == null && cachePath != null) {
                            // Failed to load from server, use local cache
                            try (Reader in = Files.newBufferedReader(cachePath)) {
                                json = ModGradle.INTERNAL.fromJson(in, JsonObject.class);
                            } catch (IOException | JsonSyntaxException s) {
                                e.addSuppressed(s);
                                throw new RuntimeException(e);
                            }
                        }
                    }

                    Objects.requireNonNull(json, "Version data not loaded");

                    ImmutableMap.Builder<String, VersionInfo> builder = ImmutableMap.builder();
                    for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                        String version = entry.getKey().strip();
                        
                        JsonObject versionObj = entry.getValue().getAsJsonObject();
                        
                        int java = versionObj.get("java").getAsInt();
                        int resource = versionObj.get("resource").getAsInt();
                        OptionalInt data = versionObj.has("data") ? OptionalInt.of(versionObj.get("data").getAsInt()) : OptionalInt.empty();
                        
                        MixinVersion mixin = null;
                        if (versionObj.has("mixin")) {
                            String compatibility = versionObj.getAsJsonObject("mixin").get("compatibility").getAsString();
                            String release = versionObj.getAsJsonObject("mixin").get("release").getAsString();
                            mixin = new MixinVersion(compatibility, release);
                        }

                        builder.put(version, new VersionInfo(java, resource, data, mixin));
                    }
                    
                    VERSION_MAP = builder.build();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return VERSION_MAP;
        }
    }

    /**
     * Gets the current project build version based on the files in a maven repository.
     * @param baseVersion The project version without the release number.
     * @param mavenRepo The local path of the maven repository.
     */
    public static String getVersion(Project project, String baseVersion, String mavenRepo) {
        try {
            String group = project.getGroup().toString();
            String artifact = project.getName();
            if (group.isEmpty()) throw new IllegalStateException("Can't get version: Group not set.");
            if (artifact.isEmpty()) throw new IllegalStateException("Can't get version: Artifact not set.");
            if (mavenRepo.startsWith("http://") || mavenRepo.startsWith("https://")) {
                URL url = new URL(mavenRepo + "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml");
                return Versioning.getVersion(baseVersion, url);
            }

            Path mavenPath = project.file(mavenRepo).toPath().resolve(group.replace('.', '/')).resolve(artifact);
            if (!Files.isDirectory(mavenPath)) {
                return baseVersion + ".0";
            }
            try (Stream<Path> paths = Files.walk(mavenPath)) {
                return baseVersion + "." + paths
                        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".pom"))
                        .map(path -> {
                            String fileName = path.getFileName().toString();
                            return fileName.substring(fileName.indexOf('-', artifact.length()) + 1, fileName.length() - 4);
                        })
                        .filter(version -> version.startsWith(baseVersion))
                        .max(Comparator.comparing(ComparableVersion::new))
                        .map(ver -> ver.substring(StringUtil.lastIndexWhere(ver, chr -> "0123456789".indexOf(chr) < 0) + 1))
                        .map(ver -> ver.isEmpty() ? "-1" : ver)
                        .map(ver -> Integer.toString(Integer.parseInt(ver) + 1))
                        .orElse("0");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getVersion(String baseVersion, URL url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(connection.getInputStream());
            NodeList versionNodes = doc.getElementsByTagName("version");

            String latestVersion = null;
            for (int i = 0; i < versionNodes.getLength(); i++) {
                String version = versionNodes.item(i).getTextContent();
                if (version.startsWith(baseVersion)) {
                    latestVersion = version;
                }
            }

            if (latestVersion == null) {
                return baseVersion + ".0";
            }

            return baseVersion + "." + (Integer.parseInt(latestVersion.substring(latestVersion.lastIndexOf('.') + 1)) + 1);
        } catch (FileNotFoundException e) {
            // This exception is thrown if the maven-metadata.xml file doesn't exist at the provided URL,
            // which would be the case if there were no previous publications to this Maven repository with this project
            return baseVersion + ".0";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the major java version for a given version of minecraft.
     */
    public static int getJavaVersion(String minecraft) {
        return getMinecraftVersion(minecraft).java();
    }

    /**
     * Gets the resource pack version for a given version of minecraft.
     */
    public static int getResourceVersion(String minecraft) {
        return getMinecraftVersion(minecraft).resource();
    }

    /**
     * Gets the datapack version for a given version of minecraft.
     * If datapacks were not yet introduced in that version, the returned optional will be empty.
     */
    public static OptionalInt getDataVersion(String minecraft) {
        return getMinecraftVersion(minecraft).data();
    }

    /**
     * Gets the mixin version for a given version of minecraft.
     */
    public static Optional<MixinVersion> getMixinVersion(String minecraft) {
        return Optional.ofNullable(getMinecraftVersion(minecraft).mixin());
    }

    private static VersionInfo getMinecraftVersion(String minecraft) {
        Map<String, VersionInfo> map = versionMap();
        if (map.containsKey(minecraft)) {
            return map.get(minecraft);
        }
        
        // No data for that version
        // For new minor releases, take the data of the previous minor release
        ArtifactVersion v = new DefaultArtifactVersion(minecraft);
        if (v.getMajorVersion() > 0) {
            for (int release = v.getIncrementalVersion() - 1; release >= 0; release -= 1) {
                String ver = v.getMajorVersion() + "." + v.getMinorVersion() + "." + release;
                if (map.containsKey(ver)) {
                    return map.get(ver);
                }
            }
        }
        
        // New non-minor release, fail here, the data needs to be updated.
        throw new IllegalStateException("Version information missing for " + minecraft);
    }

    private record VersionInfo(int java, int resource, OptionalInt data, @Nullable MixinVersion mixin) {}
}
