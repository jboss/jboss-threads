package org.jboss.threads;

/**
 * Not part of {@link Version} in order to not force class initialization of the latter
 */
class VersionLogging {

    static Boolean shouldLogVersion() {
        try {
            return Boolean.valueOf(System.getProperty("jboss.log-version", "false"));
        } catch (Throwable ignored) {
            return Boolean.FALSE;
        }
    }
}
