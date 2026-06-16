package dev.kuml.ai.vault

/**
 * OS detection for backend selection.
 *
 * Override-able via `-Dkuml.ai.os` system property for tests.
 * Valid override values: "mac", "linux", "windows", "other".
 */
public object OsDetection {
    public enum class Os {
        MAC,
        LINUX,
        WINDOWS,
        OTHER,
    }

    /** Returns the current OS. Checks `-Dkuml.ai.os` first for test injection. */
    public fun current(): Os {
        System.getProperty("kuml.ai.os")?.lowercase()?.let { override ->
            return when {
                override.contains("mac") -> Os.MAC
                override.contains("linux") -> Os.LINUX
                override.contains("windows") || override.contains("win") -> Os.WINDOWS
                else -> Os.OTHER
            }
        }
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("mac") -> Os.MAC
            osName.contains("linux") -> Os.LINUX
            osName.contains("windows") || osName.contains("win") -> Os.WINDOWS
            else -> Os.OTHER
        }
    }
}
