package com.jazzkuh.minestomplugins;

import lombok.Getter;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class PluginClassLoader<S> extends URLClassLoader {

    private final List<PluginClassLoader> children = new ArrayList<>();

    private static final List<PluginClassLoader> LOADERS = new ArrayList<>();

    @Getter
    private final DiscoveredPlugin discoveredPlugin;

    @Getter
    private final S server;

    private EventNode<Event> eventNode;
    private ComponentLogger logger;

    public PluginClassLoader(String name, URL[] urls, DiscoveredPlugin discoveredPlugin, S server) {
        super("Pl_" + name, urls, MinecraftServer.class.getClassLoader());
        this.discoveredPlugin = discoveredPlugin;
        this.server = server;

        synchronized (LOADERS) {
            LOADERS.add(this);
        }
    }

    public PluginClassLoader(String name, URL[] urls, ClassLoader parent, DiscoveredPlugin discoveredPlugin, S server) {
        super("Pl_" + name, urls, parent);
        this.discoveredPlugin = discoveredPlugin;
        this.server = server;

        synchronized (LOADERS) {
            LOADERS.add(this);
        }
    }

    @Override
    public void addURL(@NotNull URL url) {
        super.addURL(url);
    }

    public void addChild(@NotNull PluginClassLoader loader) {
        this.children.add(loader);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            return super.loadClass(name, resolve);
        } catch (ClassNotFoundException e) {
            for (PluginClassLoader child : this.children) {
                try {
                    return child.loadClass(name, resolve);
                } catch (ClassNotFoundException ignored) {
                }
            }
            throw e;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return super.findClass(name);
    }


    @Override
    public URL findResource(String name) {
        return super.findResource(name);
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        return super.findResources(name);
    }

    public static void clearRegistry() {
        synchronized (LOADERS) {
            LOADERS.clear();
        }
    }

    public InputStream getResourceAsStreamWithChildren(@NotNull String name) {
        InputStream in = getResourceAsStream(name);
        if (in != null) return in;

        for (PluginClassLoader child : this.children) {
            InputStream childInput = child.getResourceAsStreamWithChildren(name);
            if (childInput != null)
                return childInput;
        }

        return null;
    }

    public EventNode<Event> getEventNode() {
        if (this.eventNode == null) {
            this.eventNode = EventNode.all(this.discoveredPlugin.getName());
            MinecraftServer.getGlobalEventHandler().addChild(this.eventNode);
        }

        return this.eventNode;
    }

    public ComponentLogger getLogger() {
        if (this.logger == null) {
            this.logger = ComponentLogger.logger(this.discoveredPlugin.getName());
        }

        return this.logger;
    }

    void terminate() {
        if (this.eventNode != null) {
            MinecraftServer.getGlobalEventHandler().removeChild(this.eventNode);
        }
    }
}