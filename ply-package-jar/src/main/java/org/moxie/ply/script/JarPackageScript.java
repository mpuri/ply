package org.moxie.ply.script;

import org.moxie.ply.Output;
import org.moxie.ply.PropertiesFileUtil;
import org.moxie.ply.props.Prop;
import org.moxie.ply.props.Props;

import java.io.*;
import java.util.Map;
import java.util.Properties;

/**
 * User: blangel
 * Date: 9/24/11
 * Time: 1:13 PM
 *
 * Packages all files within {@literal compiler.buildPath} into a jar file and stores within {@literal project.build.dir}
 * as {@literal package-jar.jar-name}.jar
 * The property file used to configure this script is {@literal package-jar.properties} and so the context is
 * {@literal package-jar}.
 * The following properties exist:
 * jarName=string [[default=${project.artifact.name}]] (the name of the jar file to create [excluding the '.jar'])
 * verbose=boolean [[default=false]] (print verbose output).
 * compress=boolean [[default=true]] (if true, the jar file will be compressed).
 * manifest.version=number [[default=1.0]] (the manifest version number to use).
 * manifest.createdBy=string [[default=ply]] (who created the manifest).
 * manifest.mainClass=string [[default=""]] (the main class to make the jar executable).
 * manifest.classPath=string [[default=""]] (the class path associated with the jar).
 * manifest.spec.title=string [[default=${project.name}]] (the specification title).
 * manifest.spec.version=string [[default=${project.version}]] (the specification version).
 * manifest.impl.title=string [[default=${project.name}]] (the implementation title).
 * manifest.impl.version=string [[default=${project.version}]] (the implementation title).
 * Additionally, any other project name starting with package-jar.manifest.* will be included.  For instance, if there
 * is a property=value of manifest.Implementation-Vendor=Moxie in the package-jar.properties file then there will be
 * an entry in the manifest for 'Implementation-Vendor' with value 'Moxie'.
 * Any manifest property with a null or empty-string value will not be included in the manifest.
 *
 * TODO
 *   - Handle resources
 */
public class JarPackageScript {

    public static void main(String[] args) {
        JarPackageScript jarPackageScript = new JarPackageScript();
        try {
            jarPackageScript.invoke();
        } catch (IOException ioe) {
            Output.print(ioe);
        } catch (InterruptedException ie) {
            Output.print(ie);
        }
    }

    private void invoke() throws IOException, InterruptedException {
        createManifestFile();
        String[] cmdArgs = createArgs();
        ProcessBuilder processBuilder = new ProcessBuilder(cmdArgs).redirectErrorStream(true);
        Process process = processBuilder.start();
        InputStream processStdout = process.getInputStream();
        BufferedReader lineReader = new BufferedReader(new InputStreamReader(processStdout));
        String processStdoutLine;
        while ((processStdoutLine = lineReader.readLine()) != null) {
            System.out.println(processStdoutLine); // don't use Output, just print directly and let ply itself handle
        }
        int result = process.waitFor();
        System.exit(result);
    }

    private void createManifestFile() {
        Map<String, Prop> manifestProps = Props.getPropertiesWithCollapsedScope("package-jar", "manifest.*");
        // filter out and handle the short-named manifest properties
        String version = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "manifest.version");
        manifestProps.remove("manifest.version");
        if (isEmpty(version)) {
            version = "1.0";
        }
        String createdBy = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "manfiest.createdBy");
        manifestProps.remove("manfiest.createdBy");
        if (isEmpty(createdBy)) {
            createdBy = "Ply";
        }
        String mainClass = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "manifest.mainClass");
        manifestProps.remove("manifest.mainClass");
        String classPath = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "manifest.classPath");
        manifestProps.remove("manifest.classPath");
        String specTitle = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "manifest.spec.title");
        manifestProps.remove("manifest.spec.title");
        String specVersion = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "manifest.spec.version");
        manifestProps.remove("manifest.spec.version");
        String implTitle = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "manifest.impl.title");
        manifestProps.remove("manifest.impl.title");
        String implVersion = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "manifest.impl.version");
        manifestProps.remove("manifest.impl.version");

        StringBuilder buffer = new StringBuilder();
        appendManifestInformation("Manifest-Version", version, buffer);
        appendManifestInformation("Created-By", createdBy, buffer);
        appendManifestInformation("Main-Class", mainClass, buffer);
        appendManifestInformation("Class-Path", classPath, buffer);
        appendManifestInformation("Specification-Title", specTitle, buffer);
        appendManifestInformation("Specification-Version", specVersion, buffer);
        appendManifestInformation("Implementation-Title", implTitle, buffer);
        appendManifestInformation("Implementation-Version", implVersion, buffer);

        // add user defined information, if any.
        for (String property : manifestProps.keySet()) {
            appendManifestInformation(property, manifestProps.get(property).value, buffer);
        }
        File manifestFile = new File(getManifestFilePath());
        PrintWriter writer = null;
        try {
            manifestFile.createNewFile();
            writer = new PrintWriter(manifestFile);
            // important, manifest files must end in a new line
            writer.println(buffer.toString());
            writer.flush();
        } catch (IOException ioe) {
            Output.print(ioe);
            System.exit(1);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private static void appendManifestInformation(String name, String value, StringBuilder buffer) {
        if (!isEmpty(value)) {
            buffer.append(name);
            buffer.append(": ");
            buffer.append(value);
            buffer.append('\n');
        }
    }

    private String[] createArgs() {
        String jarScript = Props.getValue("java").replace("bin" + File.separator + "java",
                "bin" + File.separator + "jar");
        String options = "cfm";
        if (getBoolean(Props.getValue("package-jar", Props.DEFAULT_SCOPE, "verbose"))) {
            options += "v";
        }
        if (!getBoolean(Props.getValue("package-jar", Props.DEFAULT_SCOPE, "compress"))) {
            options += "0";
        }
        String jarName = Props.getValue("package-jar", Props.DEFAULT_SCOPE, "jarName");
        if (isEmpty(jarName)) {
            Output.print("^warn^ Property 'package-jar.jarName' was empty, defaulting to value of ${project.artifact.name}.");
            jarName = Props.getValue("project", Props.DEFAULT_SCOPE, "artifact.name");
            if (isEmpty(jarName)) {
                Output.print("^warn^ Property 'project.artifact.name' was empty, defaulting to value of ${project.name}.");
                jarName = Props.getValue("project", Props.DEFAULT_SCOPE, "name");
                if (isEmpty(jarName)) {
                    Output.print("^warn^ Property 'project.name' was empty, defaulting to 'no-name'.");
                    jarName = "no-name";
                }
            }
            jarName = jarName + ".jar";
        }
        jarName = getJarFilePath(jarName);
        String manifestFile = getManifestFilePath();
        String inputFiles = Props.getValue("compiler", Props.DEFAULT_SCOPE, "buildPath");

        String buildDir = Props.getValue("project", Props.DEFAULT_SCOPE, "build.dir");
        buildDir = buildDir + (buildDir.endsWith(File.separator) ? "" : File.separator);
        File dependenciesFile = createDependenciesFile(buildDir);
        if (dependenciesFile == null) {
            Output.print("^error^ Error creating the %sMETA-INF/ply/dependencies.properties file.", buildDir);
            System.exit(1);
        }

        return new String[] { jarScript, options, jarName, manifestFile, "-C", inputFiles, ".", "-C", buildDir, "META-INF/ply" };
    }

    private static String getManifestFilePath() {
        String buildDirPath = Props.getValue("project", Props.DEFAULT_SCOPE, "build.dir");
        File metaInfDir = new File(buildDirPath + (buildDirPath.endsWith(File.separator) ? "" : File.separator) + "META-INF");
        metaInfDir.mkdir();
        return (metaInfDir.getPath() + (metaInfDir.getPath().endsWith(File.separator) ? "" : File.separator) + "Manifest.mf");
    }

    private static String getJarFilePath(String jarName) {
        String buildDirPath = Props.getValue("project", Props.DEFAULT_SCOPE, "build.dir");
        return buildDirPath + (buildDirPath.endsWith(File.separator) ? "" : File.separator) + jarName;
    }

    /**
     * Reads in the {@literal resolved-deps.properties} file stored at {@literal project.build.dir} and copies it
     * to {@literal project.build.dir}/META-INF/ply/dependencies.properties stripping away the property values (as
     * the values are the resolved local-repo paths to the dependencies).  If there is no {@literal resolved-deps.properties}
     * file then a blank file will be copied to {@literal project.build.dir}/META-INF/ply/dependencies.properties.
     * @param buildDirPath the build directory path (assumed to end in {@link File#separator}).
     * @return the handle to the created dependencies.properties file or null if an error occurred while creating the file.
     */
    private static File createDependenciesFile(String buildDirPath) {
        // read in resolved-deps.properties file
        Properties dependencies = new Properties();
        Properties resolvedDeps = PropertiesFileUtil.load(buildDirPath + "resolved-deps.properties", true);
        for (String propertyName : resolvedDeps.stringPropertyNames()) {
            dependencies.put(propertyName, "");
        }
        File metaInfPlyDepFile = new File(buildDirPath + "META-INF/ply/dependencies.properties");
        PropertiesFileUtil.store(dependencies, metaInfPlyDepFile.getPath(), true);
        return metaInfPlyDepFile;
    }

    private static boolean isEmpty(String value) {
        return ((value == null) || value.isEmpty());
    }

    private static boolean getBoolean(String value) {
        return ((value != null) && value.equalsIgnoreCase("true"));
    }

}