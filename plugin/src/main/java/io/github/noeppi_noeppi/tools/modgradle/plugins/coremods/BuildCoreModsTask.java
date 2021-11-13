package io.github.noeppi_noeppi.tools.modgradle.plugins.coremods;

import io.github.noeppi_noeppi.tools.modgradle.util.HashCache;
import io.github.noeppi_noeppi.tools.modgradle.util.IOUtil;
import io.github.noeppi_noeppi.tools.modgradle.util.ProcessUtil;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.InputChanges;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public abstract class BuildCoreModsTask extends DefaultTask {
    
    public BuildCoreModsTask() {
        this.getTargetDir().set(this.getProject().file("build").toPath().resolve("coremods").toFile());
    }

    @InputFile
    public abstract RegularFileProperty getCoreModTypes();

    @InputFiles
    public abstract Property<FileCollection> getCoreModSources();

    @OutputDirectory
    public abstract DirectoryProperty getTargetDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void compileCoreMods(InputChanges changes) throws IOException {
        FileCollection sources = this.getCoreModSources().get();
        Path install = this.getTargetDir().get().getAsFile().toPath();
        Path target = this.getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(target);

        // These need to be saved if everything works.
        List<HashCache> caches = new ArrayList<>();
        List<Pair<Path, Path>> compileResultCopy = new ArrayList<>();
        boolean needsToCompile = false;
        
        for (File srcDirFile : sources.getFiles()) {
            Path srcDir = srcDirFile.toPath().toAbsolutePath().normalize();
            HashCache cache = HashCache.create(srcDir.resolve("cache"));
            caches.add(cache);
            List<Path> coreMods = Files.walk(srcDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ts"))
                    .map(p -> srcDir.relativize(p.toAbsolutePath()))
                    .toList();
            for (Path loc : coreMods) {
                Path src = srcDir.resolve(loc).normalize();
                String fileName = loc.getFileName().toString();
                String jsFileName = fileName.substring(0, fileName.length() - 3) + ".js";
                Path jsSrc = src.getParent().resolve(jsFileName);
                if (!Files.exists(jsSrc) || cache.compareAndStage(src)) {
                    Path dest = target.resolve(loc);
                    PathUtils.createParentDirectories(dest);
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    needsToCompile = true;
                    compileResultCopy.add(Pair.of(dest.getParent().resolve(jsFileName), jsSrc));
                } else {
                    Path dest = target.resolve(loc).getParent().resolve(jsFileName);
                    PathUtils.createParentDirectories(dest);
                    Files.copy(jsSrc, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        
        if (needsToCompile) {
            try (FileSystem fs = IOUtil.getFileSystem(URI.create("jar:" + this.getCoreModTypes().get().getAsFile().toPath().toUri()))) {
                Files.copy(fs.getPath("coremods.d.ts"), target.resolve("coremods.d.ts"), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(fs.getPath("tsconfig.json"), target.resolve("tsconfig.json"), StandardCopyOption.REPLACE_EXISTING);
            }

            ProcessUtil.run(install, "npm", "install", "typescript");
            ProcessUtil.run(target, "npx", "tsc");
            
            for (Pair<Path, Path> entry : compileResultCopy) {
                Files.copy(entry.getKey(), entry.getValue(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        
        for (HashCache cache : caches) {
            cache.apply();
            cache.save();
        }
    }
}
