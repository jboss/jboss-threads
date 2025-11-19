package org.jboss.threads;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 *
 */
public final class Version {
    private Version() {
    }

    private static final String JAR_NAME;
    private static final String VERSION_STRING;

    static {
        Properties versionProps = new Properties();
        String jarName = "(unknown)";
        String versionString = "(unknown)";
        try (InputStream stream = Version.class.getResourceAsStream("Version.properties")) {
            if (stream != null) try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                versionProps.load(reader);
                jarName = versionProps.getProperty("jarName", jarName);
                versionString = versionProps.getProperty("version", versionString);
            }
        } catch (IOException ignored) {
        }
        JAR_NAME = jarName;
        VERSION_STRING = versionString;
        try {
            Messages.msg.version(versionString);
        } catch (Throwable ignored) {}
    }

    /**
     * Get the name of the JBoss Modules JAR.
     *
     * @return the name
     */
    public static String getJarName() {
        return JAR_NAME;
    }

    /**
     * Get the version string of JBoss Modules.
     *
     * @return the version string
     */
    public static String getVersionString() {
        return VERSION_STRING;
    }

    /**
     * Print out the current version on {@code System.out}.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        System.out.printf("JBoss Threads version %s\n", VERSION_STRING);
    }
}
