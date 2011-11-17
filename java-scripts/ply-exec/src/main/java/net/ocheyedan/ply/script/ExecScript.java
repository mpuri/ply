package net.ocheyedan.ply.script;

import net.ocheyedan.ply.FileUtil;
import net.ocheyedan.ply.Output;
import net.ocheyedan.ply.PropertiesFileUtil;
import net.ocheyedan.ply.dep.Deps;
import net.ocheyedan.ply.props.Props;

import java.io.*;
import java.util.Arrays;
import java.util.Properties;

/**
 * User: blangel
 * Date: 11/17/11
 * Time: 10:16 AM
 *
 * Executes the project provided it has a 'exec.class' or 'package.mainClass' property defined.
 */
public class ExecScript {

    public static void main(String[] args) {
        String mainClass = (args.length == 1 ? args[0]
                : !Props.getValue("exec", "class").isEmpty()
                ?  Props.getValue("exec", "class") : Props.getValue("package", "manifest.mainClass"));
        if ((mainClass == null) || mainClass.isEmpty()) {
            Output.print("^warn^ Project doesn't have a 'exec.class' or 'package.manifest.mainClass' property set and none passed in, skipping execution.");
            return;
        }
        String artifactName = Props.getValue("package", "name");
        String buildDirPath = Props.getValue("project", "build.dir");
        String artifactPath = FileUtil.pathFromParts(buildDirPath, artifactName);
        Properties deps = getResolvedProperties();
        String classpath = Deps.getClasspath(deps, artifactPath);
        String java = Props.getValue("ply", "java");
        String[] javaArgs = new String[] { java, "-cp", classpath, mainClass };
        ProcessBuilder processBuilder = new ProcessBuilder(javaArgs).redirectErrorStream(true);
        String outputFilePath = Props.getValue("exec", "output");
        PrintStream output;
        try {
            if (outputFilePath.isEmpty() || "stdout".equals(outputFilePath)) {
                output = System.out;
            } else {
                File outputFile = new File(outputFilePath);
                outputFile.createNewFile();
                output = new PrintStream(new FileOutputStream(outputFile));
            }
        } catch (IOException ioe) {
            Output.print("^warn^ Could not access ^b^%s^r^ [ %s ], output is being directed to ^b^stdout^r^.", outputFilePath, ioe.getMessage());
            output = System.out;
        }
        try {
            Output.print("Executing ^b^%s^r^", getExecCommand(javaArgs));
            // the Process thread reaps the child if the parent (this) is terminated
            Process process = processBuilder.start();
            InputStream processStdout = process.getInputStream();
            BufferedReader lineReader = new BufferedReader(new InputStreamReader(processStdout));
            String processStdoutLine;
            while ((processStdoutLine = lineReader.readLine()) != null) {
                output.print(processStdoutLine);
            }
            int result = process.waitFor();
            if (result != 0) {
                System.exit(result);
            }
        } catch (IOException ioe) {
            throw new AssertionError(ioe);
        } catch (InterruptedException ie) {
            throw new AssertionError(ie);
        }
    }

    private static String getExecCommand(String[] args) {
        StringBuilder buffer = new StringBuilder();
        for (String arg : args) {
            if (buffer.length() != 0) {
                buffer.append(' ');
            }
            buffer.append(arg);
        }
        return buffer.toString();
    }

/**
     * @return the contents of ${project.build.dir}/${resolved-deps.properties}
     */
    protected static Properties getResolvedProperties() {
        String buildDir = Props.getValue("project", "build.dir");
        // load the resolved-deps.properties file from the build directory.
        String scope = Props.getValue("ply", "scope");
        String suffix = (scope.isEmpty() ? "" : scope + ".");
        File dependenciesFile = FileUtil.fromParts(buildDir, "resolved-deps." + suffix + "properties");
        if (!dependenciesFile.exists()) {
            return new Properties();
        }
        return PropertiesFileUtil.load(dependenciesFile.getPath());
    }

}