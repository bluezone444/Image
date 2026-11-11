package dev.chargelog.core

/** Light/dark appearance chosen in the Data tab. */
enum class ThemeMode(val label: String) {
    SYSTEM("시스템 설정 따름"), LIGHT("라이트"), DARK("다크");

    /** Whether the app draws dark, given whether the system is currently in dark mode. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun of(name: String?): ThemeMode = values().firstOrNull { it.name == name } ?: SYSTEM
    }
}
