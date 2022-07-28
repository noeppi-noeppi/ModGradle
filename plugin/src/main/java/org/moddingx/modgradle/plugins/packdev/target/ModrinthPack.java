package org.moddingx.modgradle.plugins.packdev.target;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.moddingx.modgradle.ModGradle;
import org.moddingx.modgradle.plugins.packdev.PackSettings;
import org.moddingx.modgradle.plugins.packdev.platform.ModFile;
import org.moddingx.modgradle.plugins.packdev.platform.ModdingPlatform;
import org.moddingx.modgradle.plugins.packdev.platform.modrinth.ModrinthFile;
import org.moddingx.modgradle.plugins.packdev.platform.modrinth.api.ModrinthAPI;
import org.moddingx.modgradle.plugins.packdev.platform.modrinth.api.VersionInfo;
import org.moddingx.modgradle.util.ComputedHash;
import org.moddingx.modgradle.util.Side;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class ModrinthPack<T extends ModFile> extends BaseTargetTask<T> {

    @Inject
    public ModrinthPack(ModdingPlatform<T> platform, PackSettings settings, List<T> files) {
        super(platform, settings, files);
        
        this.getArchiveExtension().convention(this.getProject().provider(() -> "mrpack"));
    }

    @Override
    protected void generate(Path target) throws IOException {
        List<ResolvedFile> files = this.resolveFiles();
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + target.toUri()), Map.of(
                "create", String.valueOf(!Files.exists(target))
        ))) {
            this.copyOverrideDataTo(fs.getPath("/overrides"), Side.COMMON);
            this.copyOverrideDataTo(fs.getPath("/client-overrides"), Side.CLIENT);
            this.copyOverrideDataTo(fs.getPath("/server-overrides"), Side.SERVER);
            this.generateIndex(fs.getPath("/modrinth.index.json"), files);
        }
    }

    private void generateIndex(Path target, List<ResolvedFile> files) throws IOException {
        JsonObject json = new JsonObject();

        json.addProperty("formatVersion", 1);
        json.addProperty("game", "minecraft");
        json.addProperty("name", this.settings.name());
        json.addProperty("versionId", this.settings.version());

        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", this.settings.minecraft());
        dependencies.addProperty("forge", this.settings.forge());
        json.add("dependencies", dependencies);

        JsonArray fileArray = new JsonArray();
        for (ResolvedFile file : files.stream().sorted(Comparator.comparing(f -> f.file().projectSlug())).toList()) {
            Map<String, ComputedHash> hashes;
            try {
                hashes = file.file().hashes(Set.of("size", "sha1", "sha512"));
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("Can't build modrinth pack, not all required hashes are supported.", e);
            }

            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("path", "mods/" + file.file().fileName());
            fileObj.addProperty("fileSize", hashes.get("size").longValue());
            
            JsonObject hashesObj = new JsonObject();
            hashesObj.addProperty("sha1", hashes.get("sha1").hexDigest());
            hashesObj.addProperty("sha512", hashes.get("sha512").hexDigest());
            fileObj.add("hashes", hashesObj);
            
            JsonObject envObj = new JsonObject();
            envObj.addProperty("client", file.file().fileSide().client ? "required" : "unsupported");
            envObj.addProperty("server", file.file().fileSide().server ? "required" : "unsupported");
            fileObj.add("env", envObj);
            
            JsonArray downloads = new JsonArray();
            downloads.add(file.downloadUrl().normalize().toString());
            fileObj.add("downloads", downloads);

            fileArray.add(fileObj);
        }
        json.add("files", fileArray);

        Writer writer = Files.newBufferedWriter(target, StandardOpenOption.CREATE_NEW);
        writer.write(ModGradle.GSON.toJson(json) + "\n");
        writer.close();
    }
    
    private List<ResolvedFile> resolveFiles() throws IOException {
        try {
            List<ResolvedFile> files = new ArrayList<>();
            List<ResolvableFile> missing = new ArrayList<>();
            List<ModFile> failed = new ArrayList<>();
            for (ModFile file : this.files) {
                if (file instanceof ModrinthFile mf) {
                    files.add(new ResolvedFile(mf.downloadURL(), mf));
                } else {
                    missing.add(new ResolvableFile(file.hash("sha512"), file));
                }
            }
            if (!missing.isEmpty()) {
                Map<ComputedHash, VersionInfo> resolved = ModrinthAPI.files(missing.stream().map(ResolvableFile::sha512).collect(Collectors.toUnmodifiableSet()));
                for (ResolvableFile file : missing) {
                    VersionInfo info = resolved.get(file.sha512());
                    if (info != null) {
                        files.add(new ResolvedFile(info.url(), file.file()));
                    } else {
                        failed.add(file.file());
                    }
                }
            }
            if (files.size() != this.files.size() || !failed.isEmpty()) {
                throw new IOException("Can't build modrinth pack: Not all files found on modrinth platform: Missing files: " + failed.stream().map(ModFile::toString).collect(Collectors.joining(", ")));
            }
            return Collections.unmodifiableList(files);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Can't resolve files for modrinth platform: sha512 not supported", e);
        }
    }

    private record ResolvableFile(ComputedHash sha512, ModFile file) {}
    private record ResolvedFile(URI downloadUrl, ModFile file) {}
}