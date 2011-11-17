package net.ocheyedan.ply.exec;

import net.ocheyedan.ply.Output;
import net.ocheyedan.ply.dep.*;
import net.ocheyedan.ply.graph.DirectedAcyclicGraph;
import net.ocheyedan.ply.props.Prop;
import net.ocheyedan.ply.props.PropsExt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * User: blangel
 * Date: 10/2/11
 * Time: 7:29 PM
 */
public final class JarExec {

    /**
     * Translates {@code execution#scriptArgs[0]} into an executable statement for a JVM invoker.
     * The whole command array needs to be processed as parameters to the JVM need to be inserted
     * into the command array.
     * @param execution to invoke
     * @param projectConfigDir the ply configuration directory from which to resolve properties
     * @return the translated execution
     */
    public static Execution createJarExecutable(Execution execution, File projectConfigDir) {
        String script = execution.script;
        String classpath = null;
        AtomicReference<String> mainClass = new AtomicReference<String>();
        AtomicBoolean staticClasspath = new AtomicBoolean(false);
        String[] options = getJarScriptOptions(projectConfigDir, execution.script, execution.scope, staticClasspath);
        if (!staticClasspath.get()) {
            classpath = getClasspathEntries(script, execution.scope, mainClass, projectConfigDir);
        }
        int classpathLength = (staticClasspath.get() ? 0 : classpath == null ? 2 : 3);
        // add the appropriate java command
        script = System.getProperty("ply.java");
        String[] newCmdArray = new String[execution.scriptArgs.length + options.length + classpathLength];
        newCmdArray[0] = script;
        System.arraycopy(options, 0, newCmdArray, 1, options.length);
        // if the '-classpath' option is specified, can't use '-jar' option (or rather vice-versa), so
        // need to explicitly give the Main-Class value (implies, using -classpath implicitly via dependencies
        // file means the jar is built with the Main-Class specified).
        if (classpath != null) {
            newCmdArray[options.length + 1] = "-classpath";
            newCmdArray[options.length + 2] = classpath;
            newCmdArray[options.length + 3] = mainClass.get(); // main-class must be found if using implicit dependencies
        } else if (!staticClasspath.get()) {
            newCmdArray[options.length + 1] = "-jar";
            newCmdArray[options.length + 2] = execution.script;
        }
        System.arraycopy(execution.scriptArgs, 1, newCmdArray, options.length + classpathLength + 1,
                execution.scriptArgs.length - 1);
        return execution.with(newCmdArray);
    }

    /**
     * Constructs a classpath element for {@code jarPath} (including it itself, {@code jarPath}, on the path) by
     * analyzing the jar at {@code jarPath} for a {@literal META-INF/ply/dependencies.properties} file within it.
     * If one is found, its values are resolved and returned as the classpath (with the appropriate path separator),
     * @param jarPath of the jar to get classpath entries
     * @param scope of the execution
     * @param mainClass will be set with the 'Main-Class' value within the jar, if it is present
     * @param projectConfigDir the ply configuration directory from which to resolve properties
     * @return the classpath (including the given {@code jarPath}).
     */
    private static String getClasspathEntries(String jarPath, String scope, AtomicReference<String> mainClass,
                                              File projectConfigDir) {
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(jarPath, false);
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                mainClass.set(manifest.getMainAttributes().getValue("Main-Class"));
            }
            JarEntry dependenciesJarEntry = jarFile.getJarEntry("META-INF/ply/dependencies.properties");
            if (dependenciesJarEntry == null) {
                return null;
            }
            InputStream dependenciesStream = jarFile.getInputStream(dependenciesJarEntry);
            Properties dependencies = new Properties();
            dependencies.load(dependenciesStream);
            // if there are no dependencies, the 'dependencies.properties' may still exist, just empty; so ignore
            if (dependencies.isEmpty()) {
                return null;
            }
            List<DependencyAtom> deps = Deps.parse(dependencies);
            RepositoryRegistry repos = createRepositoryList(projectConfigDir, scope);
            DirectedAcyclicGraph<Dep> depGraph = Deps.getDependencyGraph(deps, repos);
            Properties resolvedDependencies = Deps.convertToResolvedPropertiesFile(depGraph);
            StringBuilder classpath = new StringBuilder();
            for (String resolvedDependency : resolvedDependencies.stringPropertyNames()) {
                classpath.append(resolvedDependencies.getProperty(resolvedDependency));
                classpath.append(File.pathSeparator);
            }
            classpath.append(jarPath);
            return classpath.toString();
        } catch (IOException ioe) {
            Output.print(ioe);
            System.exit(1);
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close(); // note, closes entry's input-streams as well
                } catch (IOException ioe) {
                    throw new AssertionError(ioe);
                }
            }
        }
        return null;
    }

    private static RepositoryRegistry createRepositoryList(File projectConfigDir, String scope) {
        Prop prop = PropsExt.get(projectConfigDir, "depmngr", scope, "localRepo");
        String filteredLocalRepo = PropsExt.filterForPly(projectConfigDir, prop, scope);
        RepositoryAtom localRepo = RepositoryAtom.parse(filteredLocalRepo);
        if (localRepo == null) {
            Output.print("^error^ Local repository not defined.  Set 'localRepo' property in context 'depmngr'");
            System.exit(1);
        }
        List<RepositoryAtom> repositoryAtoms = new ArrayList<RepositoryAtom>();
        Map<String, Prop> repositoryProps = PropsExt.getPropsForScope(projectConfigDir, "repositories", scope); // TODO - filter the props?
        if (repositoryProps != null) {
            for (String repoUri : repositoryProps.keySet()) {
                if (localRepo.getPropertyName().equals(repoUri)) {
                    continue;
                }
                String repoType = repositoryProps.get(repoUri).value;
                String repoAtom = repoType + ":" + repoUri;
                RepositoryAtom repo = RepositoryAtom.parse(repoAtom);
                if (repo == null) {
                    Output.print("^warn^ Invalid repository declared %s, ignoring.", repoAtom);
                } else {
                    repositoryAtoms.add(repo);
                }
            }
        }
        Collections.sort(repositoryAtoms, RepositoryAtom.LOCAL_COMPARATOR);
        return new RepositoryRegistry(localRepo, repositoryAtoms, null);
    }

    /**
     * Retrieves the jvm options for {@code script} or the default options if none have been specified.
     * @param projectConfigDir the ply configuration directory from which to resolve properties
     * @param script for which to find options
     * @param scope for the {@code script}
     * @param staticClasspath will be set by this method to true iff the resolved options contains a
     *        {@literal -classpath} or {@literal -cp} value.
     * @return the split jvm options for {@code script}
     */
    private static String[] getJarScriptOptions(File projectConfigDir, String script, String scope, AtomicBoolean staticClasspath) {
        // strip the resolved path (just use the jar name)
        int index = script.lastIndexOf(File.separator);
        if (index != -1) {
            script = script.substring(index + 1);
        }
        String options = PropsExt.getValue(projectConfigDir, "scripts-jar", scope, "options." + script);
        if (options.isEmpty()) {
            options = PropsExt.getValue(projectConfigDir, "scripts-jar", scope, "options.default");
        }
        options = PropsExt.filterForPly(projectConfigDir, new Prop("scripts-jar", scope, "", options, true), scope);
        if (options.contains("-cp") || options.contains("-classpath")) {
            staticClasspath.set(true);
        }
        return options.split(" ");
    }

    private JarExec() { }

}