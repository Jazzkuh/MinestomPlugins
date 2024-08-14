package com.jazzkuh.minestomplugins;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public final class CombinedClassLoader extends ClassLoader {

    private final List<PluginClassLoader> loaders;

    public CombinedClassLoader(List<PluginClassLoader> loaders) {
        super(Thread.currentThread().getContextClassLoader());
        this.loaders = new ArrayList<>(loaders);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        for (PluginClassLoader loader : loaders) {
            try {
                return loader.findClass(name);
            } catch (ClassNotFoundException e) {
                // Ignore and try the next loader
            }
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    public URL findResource(String name) {
        for (PluginClassLoader loader : loaders) {
            URL resource = loader.findResource(name);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        List<URL> resources = new ArrayList<>();
        for (PluginClassLoader loader : loaders) {
            resources.addAll(Collections.list(loader.findResources(name)));
        }
        return Collections.enumeration(resources);
    }
}
