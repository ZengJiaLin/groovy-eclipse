/*
 * Copyright 2009-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.jdt.groovy.internal.compiler;

import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.clearExtensionMethodCache;
import static org.eclipse.jdt.internal.core.ClasspathEntry.NO_ENTRIES;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import groovy.lang.GroovyClassLoader;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.eclipse.GroovyLogManager;
import org.codehaus.groovy.eclipse.TraceCategory;
import org.codehaus.groovy.runtime.m12n.ExtensionModuleScanner;
import org.codehaus.groovy.runtime.m12n.SimpleExtensionModule;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.groovy.core.util.ReflectionUtils;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.core.ExternalJavaProject;
import org.eclipse.jdt.internal.core.util.Util;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;

public final class GroovyClassLoaderFactory {

    /*
     * Each project is allowed a GroovyClassLoader that will be used to load transform definitions and supporting classes. A cache
     * is maintained from project names to the current classpath and associated loader. If the classpath matches the cached version
     * on a call to build a parser then it is reused. If it does not match then a new loader is created and stored (storing it
     * orphans the previously cached one). When either a full build or a clean or project close occurs, we also discard the loader
     * instances associated with the project.
     */
    private static Map<String, Map.Entry<IClasspathEntry[], GroovyClassLoader[]>> projectClassLoaderCache = new ConcurrentHashMap<>();

    public  static void clearCache(final String projectName) {
        Map.Entry<?, GroovyClassLoader[]> entry = projectClassLoaderCache.remove(projectName);
        if (entry != null) {
            Arrays.stream(entry.getValue()).filter(Objects::nonNull).forEach(GroovyClassLoaderFactory::close);
        }
    }

    private static void close(final ClassLoader classLoader) {
        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
            } catch (Exception e) {
                Util.log(e);
            }
        }
        ClassLoader parentLoader = getParent(classLoader);
        if (parentLoader instanceof URLClassLoader) {
            // created by newClassLoader(...)
            close(parentLoader);
        }
    }

    private static ClassLoader getParent(final ClassLoader classLoader) {
        if (classLoader instanceof org.apache.xbean.classloader.MultiParentClassLoader) {
            return ((org.apache.xbean.classloader.MultiParentClassLoader) classLoader).getParents()[0];
        }
        return classLoader.getParent();
    }

    //--------------------------------------------------------------------------

    private GroovyClassLoader batchLoader;
    private final CompilerOptions compilerOptions;
    private final LookupEnvironment lookupEnvironment;

    public GroovyClassLoaderFactory(final CompilerOptions compilerOptions, final LookupEnvironment lookupEnvironment) {
        this.compilerOptions = compilerOptions;
        this.lookupEnvironment = lookupEnvironment;
    }

    //--------------------------------------------------------------------------

    public GroovyClassLoader[] getGroovyClassLoaders(final CompilerConfiguration compilerConfiguration) {
        if (compilerOptions.groovyProjectName == null) {
            return getBatchGroovyClassLoaders(compilerConfiguration);
        } else {
            return getProjectGroovyClassLoaders(compilerConfiguration);
        }
    }

    private GroovyClassLoader[] getBatchGroovyClassLoaders(final CompilerConfiguration compilerConfiguration) {
        if (batchLoader == null && lookupEnvironment != null) {
            try {
                INameEnvironment nameEnvironment = lookupEnvironment.nameEnvironment;
                if (nameEnvironment.getClass().getName().contains(".InMemoryNameEnvironment")) {
                    nameEnvironment = ((INameEnvironment[]) ReflectionUtils.throwableGetPrivateField(nameEnvironment.getClass(), "classLibs", nameEnvironment))[0];
                }
                if (nameEnvironment instanceof FileSystem) {
                    FileSystem.Classpath[] classpaths = ReflectionUtils.throwableGetPrivateField(FileSystem.class, "classpaths", nameEnvironment);
                    if (classpaths != null) {
                        batchLoader = new GroovyClassLoader();
                        for (FileSystem.Classpath classpath : classpaths) {
                            batchLoader.addClasspath(classpath.getPath());
                        }
                    }
                }
            } catch (Exception e) {
                Util.log(e, "Unexpected problem computing classpath for batch compiler");
            }
        }
        return new GroovyClassLoader[] {new GrapeAwareGroovyClassLoader(batchLoader, compilerConfiguration), batchLoader};
    }

    private GroovyClassLoader[] getProjectGroovyClassLoaders(final CompilerConfiguration compilerConfiguration) {
        String projectName = compilerOptions.groovyProjectName;
        IProject project = findProject(projectName);
        try {
            IJavaProject javaProject = ExternalJavaProject.EXTERNAL_PROJECT_NAME.equals(projectName)
                ? new ExternalJavaProject(JavaCore.create(project).getRawClasspath()) : JavaCore.create(project);
            IClasspathEntry[] classpathEntries = javaProject.exists() ? javaProject.getResolvedClasspath(true) : NO_ENTRIES;

            Map.Entry<IClasspathEntry[], GroovyClassLoader[]> entry = projectClassLoaderCache.computeIfAbsent(projectName, x -> {
                final Set<String> classPaths = new LinkedHashSet<>(), xformPaths = new LinkedHashSet<>();
                if (javaProject.exists()) calculateClasspath(javaProject, false, classPaths, xformPaths);

                if (GroovyLogManager.manager.hasLoggers()) {
                    GroovyLogManager.manager.log(TraceCategory.AST_TRANSFORM,
                        "Transform classpath: " + String.join(File.pathSeparator, xformPaths));
                }

                ClassLoader classLoader = getClass().getClassLoader();
                if (javaProject.exists()) {
                    Set<String> dontCare = new LinkedHashSet<>();
                    Set<String> mainOnly = new LinkedHashSet<>();
                    calculateClasspath(javaProject, true, dontCare, mainOnly);

                    if (!mainOnly.equals(xformPaths)) {
                        classLoader = newClassLoader(mainOnly, classLoader);
                        xformPaths.removeAll(mainOnly); // retain test paths
                    }
                }
                classLoader = newClassLoader(xformPaths, classLoader);

                return new java.util.AbstractMap.SimpleEntry<>(classpathEntries, new GroovyClassLoader[] {
                    new GrapeAwareGroovyClassLoader(project, newClassLoader(classPaths, ClassLoader.getSystemClassLoader()), compilerConfiguration),
                    new EclipseGroovyClassLoader(project, classLoader, compilerConfiguration), // "test" and "main" and "eclipse" class loader chain
                });
            });

            if (Arrays.equals(classpathEntries, entry.getKey())) {
                return entry.getValue();
            } else {
                // project classpath has changed; remove and reload
                clearCache(projectName);
                return getProjectGroovyClassLoaders(compilerConfiguration);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to bootstrap GroovyClassLoaders for project '" + projectName + "'", e);
        }
    }

    //--------------------------------------------------------------------------

    private static IProject findProject(String projectName) {
        return ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
    }

    private static void calculateClasspath(IJavaProject javaProject, boolean mainOnly, Set<String> classPaths, Set<String> xformPaths) {
        try {
            IRuntimeClasspathEntry[] entries = JavaRuntime.computeUnresolvedRuntimeClasspath(javaProject);
            Arrays.sort(entries, Comparator.comparing(IRuntimeClasspathEntry::getType));
            for (IRuntimeClasspathEntry unresolved : entries) {
                Set<String> paths = (unresolved.getType() == IRuntimeClasspathEntry.CONTAINER ? classPaths : xformPaths);
                for (IRuntimeClasspathEntry resolved : resolveRuntimeClasspathEntry(unresolved, mainOnly)) {
                    paths.add(getAbsoluteLocation(resolved));
                }
            }
            classPaths.addAll(xformPaths);
            assert classPaths.stream().map(File::new).allMatch(File::isAbsolute);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static IRuntimeClasspathEntry[] resolveRuntimeClasspathEntry(IRuntimeClasspathEntry classpathEntry, boolean excludeTestCode) throws ReflectiveOperationException {
        //return JavaRuntime.resolveRuntimeClasspathEntry(classpathEntry, javaProject, excludeTestCode); // indirect dependency on org.eclipse.debug.core.ILaunchConfiguration
        return (IRuntimeClasspathEntry[]) JavaRuntime.class.getDeclaredMethod("resolveRuntimeClasspathEntry", IRuntimeClasspathEntry.class, IJavaProject.class, boolean.class).invoke(JavaRuntime.class, classpathEntry, classpathEntry.getJavaProject(), excludeTestCode);
    }

    private static String getAbsoluteLocation(IRuntimeClasspathEntry classpathEntry) {
        if (classpathEntry.getType() == IRuntimeClasspathEntry.PROJECT) {
            try {
                // entry.getLocation() logs if project.getOutputLocation() throws, so test it first
                ((IJavaProject) JavaCore.create(classpathEntry.getResource())).getOutputLocation();
            } catch (NullPointerException | JavaModelException ignore) {
                return classpathEntry.getResource().getLocation().toOSString();
            }
        }

        String location = classpathEntry.getLocation();
        if (!new File(location).exists()) {
            IPath path = new Path(location);
            IProject project = findProject(path.segment(0));
            IResource resource = (path.segmentCount() == 1 ? project : project.getFile(path.removeFirstSegments(1)));
            if (resource.getLocation() != null) {
                location = resource.getLocation().toOSString();
            }
        }
        return location;
    }

    private static URLClassLoader newClassLoader(Set<String> classpath, ClassLoader parent) {
        URL[] urls = classpath.stream().map(file -> {
            try {
                return new File(file).toURI().toURL();
            } catch (MalformedURLException ignore) {
                return null;
            }
        }).filter(Objects::nonNull).toArray(URL[]::new);

        if (NONLOCKING) {
            if (parent == null) parent = URLClassLoader.newInstance(new URL[0], null);
            return new org.apache.xbean.classloader.NonLockingJarFileClassLoader("GDT non-locking loader", urls, parent);
        } else {
            return URLClassLoader.newInstance(urls, parent);
        }
    }

    private static final boolean NONLOCKING = Boolean.getBoolean("greclipse.nonlocking");
    static {
        if (NONLOCKING) {
            System.out.println("property set: greclipse.nonlocking: will try to avoid locking jars");
        }
    }

    //--------------------------------------------------------------------------

    private static class EclipseGroovyClassLoader extends GroovyClassLoader {

        private EclipseGroovyClassLoader(final IProject project, final ClassLoader parent) {
            this(project, parent, CompilerConfiguration.DEFAULT);
        }

        private EclipseGroovyClassLoader(final IProject project, final ClassLoader parent, final CompilerConfiguration config) {
            super(parent, config, false);
            this.project = project;
        }

        private final IProject project;

        //

        @Override
        public void close() throws IOException {
            clearExtensionMethodCache(this);
            super.close();
        }

        @Override
        public Enumeration<URL> getResources(final String name) throws IOException {
            Enumeration<URL> resources = super.getResources(name);
            // GRECLIPSE-1762: exclude project's own extension definitions
            if (project != null && resources.hasMoreElements() && (name.startsWith("META-INF/groovy/") || name.startsWith("META-INF/services/"))) {
                String exclude = project.getLocation().toOSString();
                if (!exclude.startsWith("/")) {
                    try {
                        // normalize "C:\b\a" to "/C:/b/a" for URL comparison
                        exclude = new File(exclude).toURI().toURL().getPath();
                    } catch (Exception ignore) {
                    }
                }

                List<URL> list = new ArrayList<>();
                while (resources.hasMoreElements()) {
                    URL resource = resources.nextElement();
                    if (!resource.getProtocol().equals("file") || !resource.getPath().startsWith(exclude)) {
                        list.add(resource);
                    }
                }
                resources = Collections.enumeration(list);
            }
            return resources;
        }
    }

    @SuppressWarnings("rawtypes")
    public static class GrapeAwareGroovyClassLoader extends EclipseGroovyClassLoader {

        public  GrapeAwareGroovyClassLoader(final ClassLoader parent, final CompilerConfiguration config) {
            super(null, parent, config);
        }

        private GrapeAwareGroovyClassLoader(final IProject project, final ClassLoader parent, final CompilerConfiguration config) {
            super(project, parent, config);
        }

        @Override
        public void addURL(final URL url) {
            this.grabbed = true;
            super.addURL(url);
        }

        /** {@code true} if any grabbing is done */
        public boolean grabbed;

        private volatile Set<Class> defaultCategories;
        private volatile Set<Class> defaultStaticCategories;

        public Set<Class> getDefaultCategories() {
            if (defaultCategories == null) {
                synchronized (this) {
                    if (defaultCategories == null) {
                        Set<Class> objectCategories = new LinkedHashSet<>(), staticCategories = new LinkedHashSet<>();
                        try {
                            Class dgm = loadClass("org.codehaus.groovy.runtime.DefaultGroovyMethods", false, true);
                            Class dgsm = loadClass("org.codehaus.groovy.runtime.DefaultGroovyStaticMethods", false, true);

                            Collections.addAll(objectCategories, (Class[]) dgm.getField("DGM_LIKE_CLASSES").get(dgm));
                            Collections.addAll(objectCategories, (Class[]) dgm.getField("ADDITIONAL_CLASSES").get(dgm));

                            Class vmpf = loadClass("org.codehaus.groovy.vmplugin.VMPluginFactory", false, true);
                            @SuppressWarnings("unchecked") Object vmp = vmpf.getMethod("getPlugin").invoke(vmpf);

                            Collections.addAll(objectCategories, (Class[]) vmp.getClass().getMethod("getPluginDefaultGroovyMethods").invoke(vmp));
                            Collections.addAll(staticCategories, (Class[]) vmp.getClass().getMethod("getPluginStaticGroovyMethods").invoke(vmp));

                            new ExtensionModuleScanner(module -> {
                                if (module instanceof SimpleExtensionModule) {
                                    objectCategories.addAll(((SimpleExtensionModule) module).getInstanceMethodsExtensionClasses());
                                    staticCategories.addAll(((SimpleExtensionModule) module).getStaticMethodsExtensionClasses());
                                }
                            }, this).scanClasspathModules();

                            staticCategories.add(dgsm);
                            objectCategories.addAll(staticCategories);

                            defaultCategories = objectCategories;
                            defaultStaticCategories = staticCategories;
                        } catch (ReflectiveOperationException | LinkageError e) {
                            defaultCategories = Collections.EMPTY_SET;
                            defaultStaticCategories = Collections.EMPTY_SET;

                            if (GroovyLogManager.manager.hasLoggers()) {
                                GroovyLogManager.manager.log(TraceCategory.CLASSPATH,
                                    "Failed to find Default Groovy Methods with " + this + "\n\t" + e.getMessage());
                            }
                        }
                    }
                }
            }
            return Collections.unmodifiableSet(defaultCategories);
        }

        public boolean isDefaultStaticCategory(final String name) {
            if (defaultStaticCategories == null) getDefaultCategories();
            return defaultStaticCategories.stream().map(Class::getName).anyMatch(name::equals);
        }
    }
}
