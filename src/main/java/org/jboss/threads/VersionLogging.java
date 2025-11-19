package org.jboss.threads;

import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Not part of {@link Version} in order to not force class initialization of the latter
 */
class VersionLogging {

    private static final boolean LOG_VERSION = AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
        public Boolean run() {
            return Boolean.getBoolean("jboss.log-version");
        }
    });

    static boolean shouldLogVersion() {
        return LOG_VERSION;
    }
}
