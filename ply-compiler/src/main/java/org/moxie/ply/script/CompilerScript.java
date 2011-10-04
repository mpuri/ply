package org.moxie.ply.script;

import org.moxie.ply.Output;
import org.moxie.ply.PropertiesUtil;

import javax.tools.*;
import java.io.*;
import java.util.*;

/**
 * User: blangel
 * Date: 9/17/11
 * Time: 8:58 AM
 *
 * Script used to compile source code for the ply build system.  This script is dependent upon
 * the {@literal ply-file-changed} script as it uses this information to determine which files to recompile.
 * The property file used to configure this script is {@literal compiler.properties} and so the context is {@literal compiler}.
 * The following properties exist:
 * buildPath=string [[default=${project.build.dir}/classes]] (where to place the compiled files)
 * debug=boolean [[default=true]] (true to include debug information in the compiled file)
 * verbose=boolean [[default=false]] (true to print messages from the concrete compiler)
 * optimize=boolean [[default=true]] (true to optimize the compiled code using the concrete compiler's optimization mechanisms)
 * warnings=boolean [[default=true]] (true to show concrete compiler's warning messages)
 * java.source=string [[default=value of {@link System#getProperty(String)} with argument "java.version"]]
 *                    (the -source argument for the java compiler, note only 1.6+ is supported)
 * java.target=string [[default=value of {@link System#getProperty(String)} with argument "java.version"]]
 *                    (the -target argument for the java compiler, note only 1.6+ is supported)
 * java.bootclasspath=string [[default=""]] (used in conjunction with java.target for cross-compilation)
 * java.extdirs=string [[default=""]] (used in conjunction with java.target for cross-compilation)
 * java.debugLevel=string [[default=""]] (comma-separated list of levels to be appended to the '-g' debug switch
 *                    for the java compiler.  Valid levels are 'lines', 'vars', 'source'.  If 'debug' property
 *                    is false, this property is irrelevant.
 * java.warningsLevel=string [[default="" which means all]] (a comma delimited list of warning names (which may be
 *                    prefaced with a '-' to indicate disabling). For a complete list of available names for 1.6 see
 *                    xlint warnings here http://download.oracle.com/javase/6/docs/technotes/tools/solaris/javac.html
 *                    and for 1.7 see http://download.oracle.com/javase/7/docs/technotes/tools/solaris/javac.html#xlintwarnings
 *                    If 'warnings' property is false, this property is irrelevant.
 * java.deprecation=boolean [[default=false]] (true to output deprecation warnings in the java compiler).
 * java.encoding=string [[default="", will result in the platform encoding]] (the source file encoding).
 * java.generatedSrc=string [[default=${project.src.dir}]] (the directory where generated source files will be saved.
 *                   sources may be generated by annotation processors).
 * java.processorPath=string [[default=""]] (the path to find processors)
 * java.processors=string [[default=""]] (a comma delimited list of processors to use, which take precedent over
 *                 the 'java.processorPath' option.
 * compiler=string [[default=java]] (so far only a java concrete compiler is defined, more to come in the future)
 *
 * Note, the source directory is managed by the {@literal project} context, {@literal project.src.dir}.
 *
 * More detailed descriptions of the javac processor options can be found:
 * 1.6 = http://download.oracle.com/javase/6/docs/technotes/tools/solaris/javac.html
 * 1.7 = http://download.oracle.com/javase/7/docs/technotes/tools/solaris/javac.html
 *
 * TODO
 * - handle multiple source paths
 * - handle cross-compilation (i.e., java.target/java.bootclasspath/java.extdirs
 * - handle annotation processors
 */
public class CompilerScript {

    /**
     * @param args are either null or contains the src and build directories.
     */
    public static void main(String[] args) {
        CompilerScript script;
        if (args.length == 2) {
            script = new CompilerScript(args[0], args[1]);
        } else {
            script = new CompilerScript(System.getenv("project.src.dir"), System.getenv("project.build.dir"));
        }
        script.invoke();
    }

    private static boolean isSupportedJavaVersion(String version) {
        if (isEmpty(version)) {
            return false;
        }
        try {
            Float javaVersion = Float.valueOf(version);
            if (javaVersion < 1.6f) {
                Output.print("^error^ only JDK 1.6+ is supported for compilation [ running %f ].", javaVersion);
                return false;
            }
        } catch (NumberFormatException nfe) {
            throw new AssertionError(nfe);
        } catch (NullPointerException npe) {
            throw new AssertionError(npe);
        }
        return true;
    }

    private static String getJavaVersion() {
        String version = System.getProperty("java.version");
        // only take the first decimal if multiple
        if ((version.length() > 2) && (version.charAt(1) == '.')) {
            version = version.substring(0, 3);
        }
        return version;
    }

    private static boolean isEmpty(String value) {
        return ((value == null) || value.isEmpty());
    }

    private static boolean getBoolean(String value) {
        return ((value != null) && value.equalsIgnoreCase("true"));
    }

    private final String srcDir;

    private final Set<String> sourceFilePaths;

    private CompilerScript(String srcDir, String buildDir) {
        if ((srcDir == null) || (buildDir == null)) {
            Output.print("^error^ could not determine source or build directory for compilation.");
            System.exit(1);
        }
        if (!isSupportedJavaVersion(getJavaVersion())) {
            System.exit(1);
        }
        this.srcDir = srcDir;
        this.sourceFilePaths = new HashSet<String>();
        // ensure the build directories are created
        String buildClassesPath = System.getenv("compiler.buildPath");
        File buildClassesDir = new File(buildClassesPath);
        buildClassesDir.mkdirs();
        // load the src-changed.properties file from the build directory.
        File changedPropertiesFile = new File(buildDir + (buildDir.endsWith(File.separator) ? "" : File.separator) + "src-changed.properties");
        Properties changedProperties = PropertiesUtil.load(changedPropertiesFile.getPath(), false, true);
        if (changedProperties == null) {
            Output.print("^error^ src-changed.properties not found, please run 'file-changed' before 'compiler'.");
        } else {
            for (String filePath : changedProperties.stringPropertyNames()) {
                if (filePath.endsWith(".java")) {
                    sourceFilePaths.add(filePath);
                }
            }
        }
    }

    private void invoke() {
        if (sourceFilePaths.isEmpty()) {
            Output.print("Nothing to compile, everything is up to date.");
            return;
        }
        File sourceDir = new File(srcDir);
        String srcPath;
        try {
            srcPath = sourceDir.getCanonicalPath();
            if (!srcPath.endsWith(File.separator)) {
                srcPath = srcPath + File.separator;
            }
        } catch (IOException ioe) {
            throw new AssertionError(ioe);
        }
        FormattedDiagnosticListener diagnosticListener = new FormattedDiagnosticListener(srcPath);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = javac.getStandardFileManager(diagnosticListener, null, null);
        Iterable<? extends JavaFileObject> sourceFiles = fileManager.getJavaFileObjects(sourceFilePaths.toArray(new String[sourceFilePaths.size()]));
        StringWriter extraPrintStatements = new StringWriter();
        JavaCompiler.CompilationTask compilationTask = javac.getTask(extraPrintStatements, fileManager, diagnosticListener, getCompilerArgs(), null, sourceFiles);
        Output.print("Compiling ^b^%d^r^ source files for ^b^%s^r^\n", sourceFilePaths.size(), System.getenv("project.name"));
        boolean result = compilationTask.call();
        for (String error : diagnosticListener.getErrors()) {
            Output.print(error);
        }
        for (String warning : diagnosticListener.getWarnings()) {
            Output.print(warning);
        }
        for (String notes : diagnosticListener.getNotes()) {
            Output.print(notes);
        }
        if (extraPrintStatements.getBuffer().length() > 0) {
            Output.print(extraPrintStatements.toString());
        }
        if (!result) {
            System.exit(1);
        }
    }

    private List<String> getCompilerArgs() {
        List<String> args = new ArrayList<String>();

        args.add("-d");
        args.add(System.getenv("compiler.buildPath"));

        if (getBoolean(System.getenv("compiler.optimize"))) {
            args.add("-O");
        }

        if (getBoolean(System.getenv("compiler.debug"))) {
            if (!isEmpty(System.getenv("compiler.java.debugLevel"))) {
                args.add("-g:" + System.getenv("compiler.java.debugLevel"));
            } else {
                args.add("-g");
            }
        } else {
            args.add("-g:none");
        }

        if (getBoolean(System.getenv("compiler.verbose"))) {
            args.add( "-verbose" );
        }

        if (getBoolean(System.getenv("compiler.java.deprecation"))) {
            args.add("-deprecation");
        }

        if (!getBoolean(System.getenv("compiler.warnings"))) {
            args.add("-Xlint:none");
        } else {
            if (!isEmpty(System.getenv("compiler.java.warningsLevel"))) {
                String[] tokens = System.getenv("compiler.java.warningsLevel").split(",");
                for (String token : tokens) {
                    args.add("-Xlint:" + token);
                }
            } else {
                args.add("-Xlint");
            }
        }

        args.add("-source");
        if (isSupportedJavaVersion(System.getenv("compiler.java.source"))) {
            args.add(System.getenv("compiler.java.source"));
        } else {
            args.add(getJavaVersion());
        }

        if (!isEmpty(System.getenv("compiler.java.encoding"))) {
            args.add("-encoding");
            args.add(System.getenv("compiler.java.encoding"));
        }

        args.add("-classpath");
        args.add(createClasspath(System.getenv("compiler.buildPath"), addDependenciesToClasspathArgs()));

        args.add("-sourcepath");
        args.add(srcDir);

        return args;
    }

    /**
     * @return the contents of ${project.build.dir}/${resolved-deps.properties}
     */
    private static Properties addDependenciesToClasspathArgs() {
        String buildPath = System.getenv("project.build.dir");
        // load the src-changed.properties file from the build directory.
        File dependenciesFile = new File(buildPath + (buildPath.endsWith(File.separator) ? "" : File.separator) + "resolved-deps.properties");
        if (!dependenciesFile.exists()) {
            return new Properties();
        }
        return PropertiesUtil.load(dependenciesFile.getPath());
    }

    /**
     * Concatenates together {@code localPath} with the keys of {@code dependencies} (if any), separating each
     * by the {@link File#pathSeparator}.
     * @param localPath of the classpath
     * @param dependencies of the project, if any
     * @return the concatenated classpath
     */
    private static String createClasspath(String localPath, Properties dependencies) {
        StringBuilder buffer = new StringBuilder(localPath);
        for (String dependency : dependencies.stringPropertyNames()) {
            buffer.append(File.pathSeparator);
            buffer.append(dependencies.getProperty(dependency));
        }
        return buffer.toString();
    }

}