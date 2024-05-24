package org.moddingx.modgradle.api;

import groovy.lang.GroovyObject;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.moddingx.modgradle.util.DynamicObject;
import org.moddingx.modgradle.util.MgUtil;

import java.util.Map;
import java.util.Properties;

/**
 * Extension added to the project by every ModGradle plugin.
 * Provides convenient access to the ModGradle API functions.
 */
public class ModGradleExtension {

    public static final String EXTENSION_NAME = "modgradle";

    private final Project project;
    
    public ModGradleExtension(Project project) {
        this.project = project;
    }

    /**
     * Creates a groovy object for easy access to some properties.
     * @see #wrap(Properties)
     */
    public GroovyObject wrap() {
        return new DynamicObject();
    }

    /**
     * Creates a groovy object for easy access to the given properties.
     * The result object can be used to access the properties. Properties can be altered through the putAt method.
     * The existence of a property can be checked through {@code isCase}. {@code toMap()} makes a
     * {@code Map} useful for {@link org.gradle.api.file.FileCopyDetails#expand(Map)}.
     */
    public GroovyObject wrap(Properties properties) {
        return new DynamicObject(properties);
    }

    /**
     * Alias for {@link Versioning#getVersion(Project, String, String)}.
     */
    public String projectVersion(String baseVersion, String mavenRepo) {
        return Versioning.getVersion(this.project, baseVersion, mavenRepo);
    }

    /**
     * Alias for {@link JavaEnvironment#getLibraryPath(Project, JavaCompile)} on the default {@code compileJava} task.
     */
    public Provider<FileCollection> libraryPath() {
        JavaCompile task = MgUtil.task(this.project, "compileJava", JavaCompile.class);
        if (task == null) throw new IllegalStateException("compileJava task not found");
        return this.libraryPath(task);
    }
    
    /**
     * Alias for {@link JavaEnvironment#getLibraryPath(Project, JavaCompile)}.
     */
    public Provider<FileCollection> libraryPath(JavaCompile task) {
        return JavaEnvironment.getLibraryPath(this.project, task);
    }

    /**
     * Gets a groovy object for accessing the minecraft version data for the given minecraft version.
     * Defines the following properties:
     * <ul>
     *     <li>{@code java}: {@code int} The target java version.</li>
     *     <li>{@code resource}: {@code int}: The resource version.</li>
     *     <li>{@code data}: {@code int}: The data version (or the resource version for versions without datapacks).</li>
     *     <li>{@code mixin}: {@code String}: The mixin release suitable for this version. This is absent if there is none.</li>
     * </ul>
     */
    public GroovyObject minecraftVersion(String minecraft) {
        DynamicObject obj = new DynamicObject();
        obj.putAt("java", Versioning.getJavaVersion(minecraft));
        obj.putAt("resource", Versioning.getResourceVersion(minecraft));
        obj.putAt("data", Versioning.getDataVersion(minecraft).orElse(Versioning.getResourceVersion(minecraft)));
        Versioning.getMixinVersion(minecraft).map(MixinVersion::release).ifPresent((String v) -> obj.putAt("mixin", v));
        return obj;
    }
}
