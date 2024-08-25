package com.jazzkuh.minestomplugins;

import com.google.gson.Gson;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PluginManager {

    public final static Logger LOGGER = LoggerFactory.getLogger("PluginManager");

    public final static String INDEV_CLASSES_FOLDER = "minestom.plugin.indevfolder.classes";
    public final static String INDEV_RESOURCES_FOLDER = "minestom.plugin.indevfolder.resources";
    private final static Gson GSON = new Gson();

    private final ServerProcess serverProcess;
    private final MinecraftServer server;

    // LinkedHashMaps are HashMaps that preserve order
    private final Map<String, Plugin> plugins = new LinkedHashMap<>();
    private final Map<String, Plugin> immutablePlugins = Collections.unmodifiableMap(this.plugins);

    private final File pluginFolder = new File(System.getProperty("minestom.plugin.folder", "plugins"));
    private final File dependenciesFolder = new File(this.pluginFolder, ".libs");
    private Path pluginDataRoot = this.pluginFolder.toPath();

    private enum State {DO_NOT_START, NOT_STARTED, STARTED, PRE_INIT, INIT, POST_INIT}

    private State state = State.NOT_STARTED;

    public PluginManager(ServerProcess serverProcess, MinecraftServer server) {
        this.serverProcess = serverProcess;
        this.server = server;
    }

    /**
     * Gets if the plugins should be loaded during startup.
     * <p>
     * Default value is 'true'.
     *
     * @return true if plugins are loaded in {@link net.minestom.server.MinecraftServer#start(java.net.SocketAddress)}
     */
    public boolean shouldLoadOnStartup() {
        return this.state != State.DO_NOT_START;
    }

    /**
     * Used to specify if you want plugins to be loaded and initialized during startup.
     * <p>
     * Only useful before the server start.
     *
     * @param loadOnStartup true to load plugins on startup, false to do nothing
     */
    public void setLoadOnStartup(boolean loadOnStartup) {
        Check.stateCondition(this.state.ordinal() > State.NOT_STARTED.ordinal(), "Plugins have already been initialized");
        this.state = loadOnStartup ? State.NOT_STARTED : State.DO_NOT_START;
    }

    @NotNull
    public File getPluginFolder() {
        return this.pluginFolder;
    }

    public @NotNull Path getPluginDataRoot() {
        return this.pluginDataRoot;
    }

    public void setPluginDataRoot(@NotNull Path dataRoot) {
        this.pluginDataRoot = dataRoot;
    }

    @NotNull
    public Collection<Plugin> getPlugins() {
        return this.immutablePlugins.values();
    }

    @Nullable
    public Plugin getPlugin(@NotNull String name) {
        return this.plugins.get(name.toLowerCase());
    }

    public boolean hasPlugin(@NotNull String name) {
        return this.plugins.containsKey(name);
    }

    //
    // Init phases
    //

    @ApiStatus.Internal
    public void start() {
        if (this.state == State.DO_NOT_START) {
            LOGGER.warn("Plugin loadOnStartup option is set to false, plugins are therefore neither loaded or initialized.");
            return;
        }
        Check.stateCondition(this.state != State.NOT_STARTED, "PluginManager has already been started");
        this.loadPlugins();

        this.state = State.STARTED;
    }

    @ApiStatus.Internal
    public void gotoPreInit() {
        if (this.state == State.DO_NOT_START) return;
        Check.stateCondition(this.state != State.STARTED, "Plugins have already done pre initialization");
        this.plugins.values().forEach(Plugin::onLoad);
        this.state = State.PRE_INIT;
    }

    @ApiStatus.Internal
    public void gotoInit() {
        if (this.state == State.DO_NOT_START) return;
        Check.stateCondition(this.state != State.PRE_INIT, "Plugins have already done initialization");
        this.plugins.values().forEach(Plugin::onEnable);
        this.state = State.INIT;
    }

    @ApiStatus.Internal
    public void gotoPostInit() {
        if (this.state == State.DO_NOT_START) return;
        Check.stateCondition(this.state != State.INIT, "Plugins have already done post initialization");
        this.plugins.values().forEach(Plugin::onPostEnable);
        this.state = State.POST_INIT;
    }

    //
    // Loading
    //

    /**
     * Loads all plugins in the plugin folder into this server.
     * <br><br>
     * <p>
     * Pipeline:
     * <br>
     * Finds all .jar files in the plugins folder.
     * <br>
     * Per each jar:
     * <br>
     * Turns its plugin.json into a DiscoveredPlugin object.
     * <br>
     * Verifies that all properties of plugin.json are correctly set.
     * <br><br>
     * <p>
     * It then sorts all those jars by their load order (making sure that an plugin's dependencies load before it)
     * <br>
     * Note: Cyclic dependencies will stop both plugins from being loaded.
     * <br><br>
     * <p>
     * Afterwards, it loads all external dependencies and adds them to the plugin's files
     * <br><br>
     * <p>
     * Then removes any invalid plugins (Invalid being its Load Status isn't SUCCESS)
     * <br><br>
     * <p>
     * After that, it set its classloaders so each plugin is self-contained,
     * <br><br>
     * <p>
     * Removes invalid plugins again,
     * <br><br>
     * <p>
     * and loads all of those plugins into Minestom
     * <br>
     * (Plugin fields are set via reflection after each plugin is verified, then loaded.)
     * <br><br>
     * <p>
     * If the plugin successfully loads, add it to the global plugin Map (Name to Plugin)
     * <br><br>
     * <p>
     * And finally make a scheduler to clean observers per plugin.
     */
    private void loadPlugins() {
        // Initialize folders
        {
            // Make plugins folder if necessary
            if (!this.pluginFolder.exists()) {
                if (!this.pluginFolder.mkdirs()) {
                    LOGGER.error("Could not find or create the plugin folder, plugins will not be loaded!");
                    return;
                }
            }

            // Make dependencies folder if necessary
            if (!this.dependenciesFolder.exists()) {
                if (!this.dependenciesFolder.mkdirs()) {
                    LOGGER.error("Could not find nor create the plugin dependencies folder, plugins will not be loaded!");
                    return;
                }
            }
        }

        // Load plugins
        {
            // Get all plugins and order them accordingly.
            List<DiscoveredPlugin> discoveredPlugins = this.discoverPlugins();

            // Don't waste resources on doing extra actions if there is nothing to do.
            if (discoveredPlugins.isEmpty()) return;

            // Create classloaders for each plugin (so that they can be used during dependency resolution)
            Iterator<DiscoveredPlugin> pluginIterator = discoveredPlugins.iterator();
            while (pluginIterator.hasNext()) {
                DiscoveredPlugin discoveredPlugin = pluginIterator.next();
                try {
                    discoveredPlugin.createClassLoader(this.server);
                } catch (Exception e) {
                    discoveredPlugin.loadStatus = DiscoveredPlugin.LoadStatus.FAILED_TO_SETUP_CLASSLOADER;
                    this.serverProcess.exception().handleException(e);
                    LOGGER.error("Failed to load plugin {}", discoveredPlugin.getName());
                    LOGGER.error("Failed to load plugin", e);
                    pluginIterator.remove();
                }
            }

            discoveredPlugins = this.generateLoadOrder(discoveredPlugins);

            // remove invalid plugins
            discoveredPlugins.removeIf(ext -> ext.loadStatus != DiscoveredPlugin.LoadStatus.LOAD_SUCCESS);

            // Load the plugins
            for (DiscoveredPlugin discoveredPlugin : discoveredPlugins) {
                try {
                    this.loadPlugin(discoveredPlugin);
                } catch (Exception e) {
                    discoveredPlugin.loadStatus = DiscoveredPlugin.LoadStatus.LOAD_FAILED;
                    LOGGER.error("Failed to load plugin {}", discoveredPlugin.getName());
                    this.serverProcess.exception().handleException(e);
                }
            }
        }

        List<PluginClassLoader> loaders = new ArrayList<>();
        for (Plugin plugin : this.plugins.values()) {
            loaders.add(plugin.getPluginClassloader());
        }

        CombinedClassLoader combinedClassLoader = new CombinedClassLoader(loaders);
        Thread.currentThread().setContextClassLoader(combinedClassLoader);
    }

    public boolean loadDynamicPlugin(@NotNull File jarFile) throws FileNotFoundException {
        if (!jarFile.exists()) {
            throw new FileNotFoundException("File '" + jarFile.getAbsolutePath() + "' does not exists. Cannot load plugin.");
        }

        LOGGER.info("Discover dynamic plugin from jar {}", jarFile.getAbsolutePath());
        DiscoveredPlugin discoveredPlugin = this.discoverFromJar(jarFile);
        List<DiscoveredPlugin> pluginsToLoad = Collections.singletonList(discoveredPlugin);
        return this.loadPluginList(pluginsToLoad);
    }

    /**
     * Loads a plugin into Minestom.
     *
     * @param discoveredPlugin The plugin. Make sure to verify its integrity, set its class loader, and its files.
     * @return A plugin object made from this DiscoveredPlugin
     */
    @Nullable
    private Plugin loadPlugin(@NotNull DiscoveredPlugin discoveredPlugin) {
        // Create plugin (authors, version etc.)
        String pluginName = discoveredPlugin.getName();
        String mainClass = discoveredPlugin.getEntrypoint();

        PluginClassLoader loader = discoveredPlugin.getClassLoader();

        if (this.plugins.containsKey(pluginName.toLowerCase())) {
            LOGGER.error("A plugin called '{}' has already been registered.", pluginName);
            return null;
        }

        Class<?> jarClass;
        try {
            jarClass = Class.forName(mainClass, true, loader);
        } catch (ClassNotFoundException e) {
            LOGGER.error("Could not find main class '{}' in plugin '{}'.",
                    mainClass, pluginName, e);
            return null;
        }

        Class<? extends Plugin> pluginClass;
        try {
            pluginClass = jarClass.asSubclass(Plugin.class);
        } catch (ClassCastException e) {
            LOGGER.error("Main class '{}' in '{}' does not extend the 'Plugin' superclass.", mainClass, pluginName, e);
            return null;
        }

        Constructor<? extends Plugin> constructor;
        try {
            constructor = pluginClass.getDeclaredConstructor();
            // Let's just make it accessible, plugin creators don't have to make this public.
            constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            LOGGER.error("Main class '{}' in '{}' does not define a no-args constructor.", mainClass, pluginName, e);
            return null;
        }
        Plugin plugin = null;
        try {
            plugin = constructor.newInstance();
        } catch (InstantiationException e) {
            LOGGER.error("Main class '{}' in '{}' cannot be an abstract class.", mainClass, pluginName, e);
            return null;
        } catch (IllegalAccessException ignored) {
            // We made it accessible, should not occur
        } catch (InvocationTargetException e) {
            LOGGER.error(
                    "While instantiating the main class '{}' in '{}' an exception was thrown.",
                    mainClass,
                    pluginName,
                    e.getTargetException()
            );
            return null;
        }

        // add dependents to pre-existing plugins, so that they can easily be found during reloading
        for (String dependencyName : discoveredPlugin.getDependencies()) {
            Plugin dependency = this.plugins.get(dependencyName.toLowerCase());
            if (dependency == null) {
                LOGGER.warn("Dependency {} of {} is null? This means the plugin has been loaded without its dependency, which could cause issues later.", dependencyName, discoveredPlugin.getName());
            } else {
                dependency.getDependents().add(discoveredPlugin.getName());
            }
        }

        // add to a linked hash map, as they preserve order
        this.plugins.put(pluginName.toLowerCase(), plugin);

        return plugin;
    }

    /**
     * Get all plugins from the plugins folder and make them discovered.
     * <p>
     * It skims the plugin folder, discovers and verifies each plugin, and returns those created DiscoveredPlugins.
     *
     * @return A list of discovered plugins from this folder.
     */
    private @NotNull List<DiscoveredPlugin> discoverPlugins() {
        List<DiscoveredPlugin> plugins = new LinkedList<>();

        File[] fileList = this.pluginFolder.listFiles();

        if (fileList != null) {
            // Loop through all files in plugin folder
            for (File file : fileList) {

                // Ignore folders
                if (file.isDirectory()) {
                    continue;
                }

                // Ignore non .jar files
                if (!file.getName().endsWith(".jar")) {
                    continue;
                }

                DiscoveredPlugin plugin = this.discoverFromJar(file);
                if (plugin != null && plugin.loadStatus == DiscoveredPlugin.LoadStatus.LOAD_SUCCESS) {
                    plugins.add(plugin);
                }
            }
        }

        // this allows developers to have their plugin discovered while working on it, without having to build a jar and put in the plugin folder
        if (System.getProperty(INDEV_CLASSES_FOLDER) != null && System.getProperty(INDEV_RESOURCES_FOLDER) != null) {
            LOGGER.info("Found indev folders for plugin. Adding to list of discovered plugins.");
            final String pluginClasses = System.getProperty(INDEV_CLASSES_FOLDER);
            final String pluginResources = System.getProperty(INDEV_RESOURCES_FOLDER);
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(new File(pluginResources, "plugin.json")))) {
                DiscoveredPlugin plugin = GSON.fromJson(reader, DiscoveredPlugin.class);
                plugin.files.add(new File(pluginClasses).toURI().toURL());
                plugin.files.add(new File(pluginResources).toURI().toURL());
                plugin.setDataDirectory(getPluginDataRoot().resolve(plugin.getName()));

                // Verify integrity and ensure defaults
                DiscoveredPlugin.verifyIntegrity(plugin);

                if (plugin.loadStatus == DiscoveredPlugin.LoadStatus.LOAD_SUCCESS) {
                    plugins.add(plugin);
                }
            } catch (IOException e) {
                this.serverProcess.exception().handleException(e);
            }
        }
        return plugins;
    }

    /**
     * Grabs a discovered plugin from a jar.
     *
     * @param file The jar to grab it from (a .jar is a formatted .zip file)
     * @return The created DiscoveredPlugin.
     */
    private @Nullable DiscoveredPlugin discoverFromJar(@NotNull File file) {
        try (ZipFile f = new ZipFile(file)) {

            ZipEntry entry = f.getEntry("plugin.json");

            if (entry == null)
                throw new IllegalStateException("Missing plugin.json in plugin " + file.getName() + ".");

            InputStreamReader reader = new InputStreamReader(f.getInputStream(entry));

            // Initialize DiscoveredPlugin from GSON.
            DiscoveredPlugin plugin = GSON.fromJson(reader, DiscoveredPlugin.class);
            plugin.setOriginalJar(file);
            plugin.files.add(file.toURI().toURL());
            plugin.setDataDirectory(getPluginDataRoot().resolve(plugin.getName()));

            // Verify integrity and ensure defaults
            DiscoveredPlugin.verifyIntegrity(plugin);

            return plugin;
        } catch (IOException e) {
            serverProcess.exception().handleException(e);
            return null;
        }
    }

    @NotNull
    private List<DiscoveredPlugin> generateLoadOrder(@NotNull List<DiscoveredPlugin> discoveredPlugins) {
        // Plugin --> Plugins it depends on.
        Map<DiscoveredPlugin, List<DiscoveredPlugin>> dependencyMap = new HashMap<>();

        // Put dependencies in dependency map
        {
            Map<String, DiscoveredPlugin> pluginMap = new HashMap<>();

            // go through all the discovered plugins and assign their name in a map.
            for (DiscoveredPlugin discoveredPlugin : discoveredPlugins) {
                pluginMap.put(discoveredPlugin.getName().toLowerCase(), discoveredPlugin);
            }

            allPlugins:
            // go through all the discovered plugins and get their dependencies as plugins
            for (DiscoveredPlugin discoveredPlugin : discoveredPlugins) {

                List<DiscoveredPlugin> dependencies = new ArrayList<>(discoveredPlugin.getDependencies().length);

                // Map the dependencies into DiscoveredPlugins.
                for (String dependencyName : discoveredPlugin.getDependencies()) {

                    DiscoveredPlugin dependencyPlugin = pluginMap.get(dependencyName.toLowerCase());
                    // Specifies a plugin we don't have.
                    if (dependencyPlugin == null) {

                        // attempt to see if it is not already loaded (happens with dynamic (re)loading)
                        if (this.plugins.containsKey(dependencyName.toLowerCase())) {

                            dependencies.add(this.plugins.get(dependencyName.toLowerCase()).getOrigin());
                            continue; // Go to the next loop in this dependency loop, this iteration is done.

                        } else {

                            // dependency isn't loaded, move on.
                            LOGGER.error("Plugin {} requires a plugin called {}.", discoveredPlugin.getName(), dependencyName);
                            LOGGER.error("However the plugin {} could not be found.", dependencyName);
                            LOGGER.error("Therefore {} will not be loaded.", discoveredPlugin.getName());
                            discoveredPlugin.loadStatus = DiscoveredPlugin.LoadStatus.MISSING_DEPENDENCIES;
                            continue allPlugins; // the above labeled loop will go to the next plugin as this dependency is invalid.

                        }
                    }
                    // This will add null for an unknown-plugin
                    dependencies.add(dependencyPlugin);

                }

                dependencyMap.put(
                        discoveredPlugin,
                        dependencies
                );

            }
        }

        // List containing the load order.
        LinkedList<DiscoveredPlugin> sortedList = new LinkedList<>();

        // TODO actually have to read this
        {
            // entries with empty lists
            List<Map.Entry<DiscoveredPlugin, List<DiscoveredPlugin>>> loadablePlugins;

            // While there are entries with no more elements (no more dependencies)
            while (!(
                    loadablePlugins = dependencyMap.entrySet().stream().filter(entry -> isLoaded(entry.getValue())).toList()
            ).isEmpty()
            ) {
                // Get all "loadable" (not actually being loaded!) plugins and put them in the sorted list.
                for (var entry : loadablePlugins) {
                    // Add to sorted list.
                    sortedList.add(entry.getKey());
                    // Remove to make the next iterations a little quicker (hopefully) and to find cyclic dependencies.
                    dependencyMap.remove(entry.getKey());

                    // Remove this dependency from all the lists (if they include it) to make way for next level of plugins.
                    for (var dependencies : dependencyMap.values()) {
                        dependencies.remove(entry.getKey());
                    }
                }
            }
        }

        // Check if there are cyclic plugins.
        if (!dependencyMap.isEmpty()) {
            LOGGER.error("Minestom found {} cyclic plugins.", dependencyMap.size());
            LOGGER.error("Cyclic plugins depend on each other and can therefore not be loaded.");
            for (var entry : dependencyMap.entrySet()) {
                DiscoveredPlugin discoveredPlugin = entry.getKey();
                LOGGER.error("{} could not be loaded, as it depends on: {}.",
                        discoveredPlugin.getName(),
                        entry.getValue().stream().map(DiscoveredPlugin::getName).collect(Collectors.joining(", ")));
            }

        }

        return sortedList;
    }

    /**
     * Checks if this list of plugins are loaded
     *
     * @param plugins The list of plugins to check against.
     * @return If all of these plugins are loaded.
     */
    private boolean isLoaded(@NotNull List<DiscoveredPlugin> plugins) {
        return
                plugins.isEmpty() // Don't waste CPU on checking an empty array
                        // Make sure the internal plugins list contains all of these.
                        || plugins.stream().allMatch(ext -> this.plugins.containsKey(ext.getName().toLowerCase()));
    }

    private boolean loadPluginList(@NotNull List<DiscoveredPlugin> pluginsToLoad) {
        // ensure correct order of dependencies
        LOGGER.debug("Reorder plugins to ensure proper load order");
        pluginsToLoad = generateLoadOrder(pluginsToLoad);

        // setup new classloaders for the plugins to reload
        for (DiscoveredPlugin toReload : pluginsToLoad) {
            LOGGER.debug("Setting up classloader for plugin {}", toReload.getName());
//            toReload.setMinestomPluginClassLoader(toReload.makeClassLoader()); //TODO: Fix this
        }

        List<Plugin> newPlugins = new LinkedList<>();
        for (DiscoveredPlugin toReload : pluginsToLoad) {
            // reload plugins
            LOGGER.info("Actually load plugin {}", toReload.getName());
            Plugin loadedPlugin = loadPlugin(toReload);
            if (loadedPlugin != null) {
                newPlugins.add(loadedPlugin);
            }
        }

        if (newPlugins.isEmpty()) {
            LOGGER.error("No plugins to load, skipping callbacks");
            return false;
        }

        LOGGER.info("Load complete, firing preinit, init and then postinit callbacks");
        // retrigger preinit, init and postinit
        newPlugins.forEach(Plugin::onLoad);
        newPlugins.forEach(Plugin::onEnable);
        newPlugins.forEach(Plugin::onPostEnable);
        return true;
    }

    //
    // Shutdown / Unload
    //

    /**
     * Shutdowns all the plugins by unloading them.
     */
    public void shutdown() {// copy names, as the plugins map will be modified via the calls to unload
        Set<String> pluginNames = new HashSet<>(this.plugins.keySet());
        for (String ext : pluginNames) {
            if (this.plugins.containsKey(ext)) { // is still loaded? Because plugins can depend on one another, it might have already been unloaded
                this.unloadPlugin(ext);
            }
        }
    }

    private void unloadPlugin(@NotNull String pluginName) {
        Plugin ext = this.plugins.get(pluginName.toLowerCase());

        if (ext == null) {
            throw new IllegalArgumentException("Plugin " + pluginName + " is not currently loaded.");
        }

        List<String> dependents = new LinkedList<>(ext.getDependents()); // copy dependents list

        for (String dependentID : dependents) {
            Plugin dependentExt = this.plugins.get(dependentID.toLowerCase());
            if (dependentExt != null) { // check if plugin isn't already unloaded.
                LOGGER.info("Unloading dependent plugin {} (because it depends on {})", dependentID, pluginName);
                this.unload(dependentExt);
            }
        }

        LOGGER.info("Unloading plugin {}", pluginName);
        this.unload(ext);
    }

    private void unload(@NotNull Plugin ext) {
        ext.onPreDisable();
        ext.onDisable();

        ext.getPluginClassloader().terminate();

        ext.onPostDisable();

        // remove from loaded plugins
        String id = ext.getOrigin().getName().toLowerCase();
        this.plugins.remove(id);

        // cleanup classloader
        // TODO: Is it necessary to remove the CLs since this is only called on shutdown?
    }

}