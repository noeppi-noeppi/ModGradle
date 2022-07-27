package org.moddingx.modgradle.plugins.packdev.target;

import org.moddingx.modgradle.api.Versioning;
import org.moddingx.modgradle.plugins.packdev.PackDevPlugin;
import org.moddingx.modgradle.plugins.packdev.PackSettings;
import org.moddingx.modgradle.plugins.packdev.platform.ModFile;
import org.moddingx.modgradle.plugins.packdev.platform.ModdingPlatform;
import org.moddingx.modgradle.util.IOUtil;
import org.moddingx.modgradle.util.Side;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ServerPack<T extends ModFile> extends BaseTargetTask<T> {

    @Inject
    public ServerPack(ModdingPlatform<T> platform, PackSettings settings, List<T> files) {
        super(platform, settings, files);
    }

    @Override
    protected void generate(Path target) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + target.toUri()), Map.of(
                "create", String.valueOf(!Files.exists(target))
        ))) {
            this.copyAllDataTo(fs.getPath("/"), Side.SERVER);

            InputStream installScript = PackDevPlugin.class.getResourceAsStream("/" + PackDevPlugin.class.getPackage().getName().replace('.', '/') + "/install_server.py");
            if (installScript == null) {
                throw new IllegalStateException("Can't build server pack: Install script not found in ModGradle.");
            }
            Files.copy(installScript, fs.getPath("install.py"));
            installScript.close();

            try {
                Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(fs.getPath("install.py")));
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(fs.getPath("install.py"), perms);
            } catch (Exception e) {
                //
            }

            InputStream dockerFile = PackDevPlugin.class.getResourceAsStream("/" + PackDevPlugin.class.getPackage().getName().replace('.', '/') + "/Dockerfile");
            if (dockerFile == null) {
                throw new IllegalStateException("Can't build server pack: Dockerfile not found in ModGradle.");
            }
            IOUtil.copyFile(dockerFile, fs.getPath("Dockerfile"), Map.of(
                    "jdk", Integer.toString(Versioning.getJavaVersion(this.settings.minecraft()))
            ), true);
            dockerFile.close();

            this.generateServerInfo(fs.getPath("server.txt"));
        }
    }

    private void generateServerInfo(Path target) throws IOException {
        Writer writer = Files.newBufferedWriter(target, StandardOpenOption.CREATE_NEW);
        writer.write(this.settings.minecraft() + "/" + this.settings.forge() + "\n");
        for (ModFile file : this.files.stream().sorted(this.platform.internalOrder()).toList()) {
            if (file.fileSide().server) {
                writer.write(file.fileName().replace("/", "") + "/" + file.downloadURL().normalize() + "\n");
            }
        }
        writer.close();
    }
}
