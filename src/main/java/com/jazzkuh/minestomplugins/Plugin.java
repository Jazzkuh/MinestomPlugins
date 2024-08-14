package com.jazzkuh.minestomplugins;

import lombok.Getter;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

@Getter
public abstract class Plugin<S> {

    protected final Set<String> dependents = new HashSet<>();

    protected Plugin() {

    }

    public void onLoad() {

    }

    public void onEnable() {

    }

    public void onPostEnable() {

    }

    public void onPreDisable() {

    }

    public void onDisable() {

    }

    public void onPostDisable() {

    }

    public PluginClassLoader getPluginClassloader() {
        if (this.getClass().getClassLoader() instanceof PluginClassLoader pluginClassLoader) {
            return pluginClassLoader;
        }

        throw new IllegalStateException("Plugin class loader is not a PluginClassLoader");
    }

    @NotNull
    public DiscoveredPlugin getOrigin() {
        return this.getPluginClassloader().getDiscoveredPlugin();
    }

    @NotNull
    public S getServer() {
        return (S) this.getPluginClassloader().getServer();
    }

    /**
     * Gets the logger for the plugin
     *
     * @return The logger for the plugin
     */
    @NotNull
    public ComponentLogger getLogger() {
        return this.getPluginClassloader().getLogger();
    }

    public @NotNull EventNode<Event> getEventNode() {
        return this.getPluginClassloader().getEventNode();
    }

    public @NotNull Path getDataDirectory() {
        return this.getOrigin().getDataDirectory();
    }

    /**
     * Gets a resource from the plugin directory, or from inside the jar if it does not
     * exist in the plugin directory.
     * <p>
     * If it does not exist in the plugin directory, it will be copied from inside the jar.
     * <p>
     * The caller is responsible for closing the returned {@link InputStream}.
     *
     * @param fileName The file to read
     * @return The file contents, or null if there was an issue reading the file.
     */
    public @Nullable InputStream getResource(@NotNull String fileName) {
        return this.getResource(Paths.get(fileName));
    }

    /**
     * Gets a resource from the plugin directory, or from inside the jar if it does not
     * exist in the plugin directory.
     * <p>
     * If it does not exist in the plugin directory, it will be copied from inside the jar.
     * <p>
     * The caller is responsible for closing the returned {@link InputStream}.
     *
     * @param target The file to read
     * @return The file contents, or null if there was an issue reading the file.
     */
    public @Nullable InputStream getResource(@NotNull Path target) {
        final Path targetFile = this.getDataDirectory().resolve(target);
        try {
            // Copy from jar if the file does not exist in the plugin data directory.
            if (!Files.exists(targetFile)) {
                this.savePackagedResource(target);
            }

            return Files.newInputStream(targetFile);
        } catch (IOException ex) {
            this.getLogger().info("Failed to read resource {}.", target, ex);
            return null;
        }
    }

    /**
     * Gets a resource from inside the plugin jar.
     * <p>
     * The caller is responsible for closing the returned {@link InputStream}.
     *
     * @param fileName The file to read
     * @return The file contents, or null if there was an issue reading the file.
     */
    public @Nullable InputStream getPackagedResource(@NotNull String fileName) {
        try {
            final URL url = this.getOrigin().getClassLoader().getResource(fileName);
            if (url == null) {
                this.getLogger().debug("Resource not found: {}", fileName);
                return null;
            }

            return url.openConnection().getInputStream();
        } catch (IOException ex) {
            this.getLogger().debug("Failed to load resource {}.", fileName, ex);
            return null;
        }
    }

    /**
     * Gets a resource from inside the plugin jar.
     * <p>
     * The caller is responsible for closing the returned {@link InputStream}.
     *
     * @param target The file to read
     * @return The file contents, or null if there was an issue reading the file.
     */
    public @Nullable InputStream getPackagedResource(@NotNull Path target) {
        return this.getPackagedResource(target.toString().replace('\\', '/'));
    }

    /**
     * Copies a resource file to the plugin directory, replacing any existing copy.
     *
     * @param fileName The resource to save
     * @return True if the resource was saved successfully, null otherwise
     */
    public boolean savePackagedResource(@NotNull String fileName) {
        return this.savePackagedResource(Paths.get(fileName));
    }

    /**
     * Copies a resource file to the plugin directory, replacing any existing copy.
     *
     * @param target The resource to save
     * @return True if the resource was saved successfully, null otherwise
     */
    public boolean savePackagedResource(@NotNull Path target) {
        final Path targetFile = this.getDataDirectory().resolve(target);
        try (InputStream is = this.getPackagedResource(target)) {
            if (is == null) {
                return false;
            }

            Files.createDirectories(targetFile.getParent());
            Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            getLogger().debug("Failed to save resource {}.", target, ex);
            return false;
        }
    }

}