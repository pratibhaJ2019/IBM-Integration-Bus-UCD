/**
 * (c) Copyright IBM Corporation 2013, 2017.
 * (c) Copyright HCL Technologies Ltd. 2018. All Rights Reserved.
 * This is licensed under the following license.
 * The Eclipse Public 1.0 License (http://www.eclipse.org/legal/epl-v10.html)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

import org.apache.commons.lang.StringUtils

import com.urbancode.air.AirPluginTool
import com.urbancode.air.CommandHelper

import java.nio.charset.Charset
import java.util.regex.Pattern

final def apTool = new AirPluginTool(this.args[1], this.args[2])
final def PLUGIN_HOME = System.getenv("PLUGIN_HOME")
final def PLUGIN_LIB = PLUGIN_HOME + File.separator + "lib"
final def SYSTEM_CLASSPATH = System.getenv("CLASSPATH")
final def isWindows = apTool.isWindows
final def props = apTool.getStepProperties()

CommandHelper helper = new CommandHelper(new File('.').canonicalFile)
def argScript = PLUGIN_HOME + File.separator + this.args[0]
def jarPath = props['jarPath'].trim()
def classpath = new StringBuilder(PLUGIN_HOME + File.separator + "classes")
classpath.append(File.pathSeparator + PLUGIN_LIB + File.separator + "jettison-1.1.jar")
classpath.append(File.pathSeparator + PLUGIN_LIB + File.separator + "CommonsUtil.jar")
classpath.append(File.pathSeparator + PLUGIN_LIB + File.separator + "securedata.jar")
classpath.append(File.pathSeparator + PLUGIN_LIB + File.separator + "commons-codec.jar")
def mqsiprofile = props['mqsiprofile'] ? props['mqsiprofile'].trim() : ""
def env = props['env']
def groovyHome = System.getProperty("groovy.home")
def groovyExe = groovyHome + File.separator + "bin" + File.separator + (isWindows ? "groovy.bat" : "groovy")
def version = props['version'] ? props['version'].trim() : ""
def cmdArgs

/* Append CLASSPATH to specified Jar Path if found */
if (SYSTEM_CLASSPATH) {
    println("[Ok] Found CLASSPATH from system environment: ${SYSTEM_CLASSPATH}.")
    jarPath += jarPath ? File.pathSeparator + SYSTEM_CLASSPATH : SYSTEM_CLASSPATH
}

if (env) {
    File envFile = new File(env)

    if (envFile.isFile()) {
        Charset defaultCharset = Charset.defaultCharset() // default charset of the JVM
        env = envFile.getText(defaultCharset.toString())
        println("[Ok] File specified to configure environment properties.")
    }

    env = env.split("\n")

    for (def envArg : env) {
        if(envArg.trim() && envArg.contains('=')) {
            def (key, val) = envArg.trim().split('=', 2)  // split by first occurrence
            println("[Action] Setting environment variable ${key}=${val}")
            helper.addEnvironmentVariable(key, val)
        }
        else {
            println("[Error] Missing a delimiter '=' for environment variable definition : ${envArg}")
        }
    }
}

// append required jar files to classpath
def requiredJars = []

version = Integer.parseInt(version.split("\\.")[0])

if (version < 10) {
    requiredJars << "ConfigManagerProxy"

    if (!argScript.contains("set_bar_props.groovy")) {
        requiredJars.addAll([
            "connector",
            "ibmjsseprovider2",
            "com.ibm.mq",
            "com.ibm.mq.commonservices",
            "com.ibm.mq.headers",
            "com.ibm.mq.jmqi",
            "com.ibm.mq.pcf"
        ])
    }
}
else {
    requiredJars << "IntegrationAPI"

    // Additional required JAR files for a remote broker connection
    if (props['brokerHost']?.trim() && props['port']?.trim()) {
        requiredJars.addAll([
            "jetty-io",
            "jetty-util",
            "websocket-api",
            "websocket-client",
            "websocket-common"])
    }
}

/* Parse Jar Path to create groovy classpath */
for (def jarEntry : jarPath.split(File.pathSeparator)) {
    def jarFile = new File(jarEntry.trim())

    /* Add absolute paths to JARs if specified, recurse into directories if missing any required JARs */
    if (jarFile.isDirectory() && requiredJars) {
        def regexPattern = ""
        def regexArr = []
        for (def jar : requiredJars) {
            regexArr << ".*${jar}[^\\" + File.separator + "]*.jar\$"
        }
        regexPattern = StringUtils.join(regexArr, '|')
        def filePattern = Pattern.compile(regexPattern)

        def buildClassPath = {
            if (filePattern.matcher(it.name).find()) {
                def jarName = it.name
                requiredJars.remove(jarName.substring(0, jarName.lastIndexOf('.')))
                classpath.append(File.pathSeparator + it.absolutePath)
            }
        }

        jarFile.eachFileRecurse(buildClassPath)
    }
    else if (jarFile.isFile()) {
        def jarName = jarFile.name
        requiredJars.remove(jarName.substring(0, jarName.lastIndexOf('.')))
        classpath.append(File.pathSeparator + jarFile.absolutePath)
    }
    else {
        println("[Warning] ${jarFile} is not a file or directory on the file system, and it will be ignored.")
    }
}

if (requiredJars) {
    println("[Warning] the following jar files were not found on the Jar Path and are required with this"
            + " version of IIB: '${requiredJars}' If these JAR files are not part of your system's CLASSPATH"
            + " environment variable some steps may fail.")
}


if (mqsiprofile) {
    mqsiprofile = new File(mqsiprofile)

    if (!mqsiprofile.isFile()) {
        throw new FileNotFoundException("${mqsiprofile.absolutePath} is not a file on the file system.")
    }

    if (isWindows) {
        // set command environment
        cmdArgs = [mqsiprofile.absolutePath]
        String commandScript = "call " + buildCommandLine(cmdArgs)

        // run script regardless of whether environment was set
        cmdArgs = [
            groovyExe,
            "-cp",
            "${classpath}",
            argScript,
            args[1],
            args[2]
        ]

        // Convert the call into a batch script
        commandScript = commandScript + "\n" + buildCommandLine(cmdArgs)
        String cmdFile = 'call_cmd.bat'
        (new File(cmdFile)).text = commandScript
        cmdArgs = [cmdFile]
    }
    else {
        def defaultShell = props['shell']

        // source mqsiprofile within the same process as the groovy script to maintain command environment
        cmdArgs = [
            defaultShell,
            "-c",
            ". ${mqsiprofile.absolutePath} && ${groovyExe} -cp ${classpath.toString()} " +
            "${argScript} ${this.args[1]} ${this.args[2]}"
        ]
    }
}
else {
    cmdArgs = [
        groovyExe,
        "-cp",
        "${classpath}",
        argScript,
        this.args[1],
        this.args[2]
    ]
}

if (apTool.getEncKey() != null) {
    helper.addEnvironmentVariable("UCD_SECRET_VAR", apTool.getEncKey())
}

helper.runCommand(cmdArgs.join(' '), cmdArgs)

/* Convert a list of command line arguments into a string that is
 * suitable to use in a batch file.
 */
public String buildCommandLine(def cmdArgs) {
    String retval = ''
    String delim = ''
    cmdArgs.each {String cmdArg ->
        if (cmdArg.contains(' ')) {
            cmdArg = '"' + cmdArg + '"'
        }
        retval = retval + delim + cmdArg
        delim = ' '
    }
    return retval
}
