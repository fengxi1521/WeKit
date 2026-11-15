package com.xiao.feng.features.items.system.keepalive

import com.xiao.feng.constants.PackageNames
import java.util.concurrent.TimeUnit

/** What the device reports after the root whitelists run. */
data class RootStatus(
    val rootAvailable: Boolean,
    val dozeWhitelisted: Boolean,
    val backgroundAllowed: Boolean,
)

/**
 * Root commands shared by the host process and the module app process.
 *
 * They are plain `su` invocations and therefore only work in a UID that the user actually granted
 * root. WeChat rarely has it — and should not — so [KeepAliveRootClient] forwards the work to
 * [KeepAliveRootService] in the module app, which the user does grant root.
 */
object KeepAliveRootCommands {

    /** su prompts on the first run; do not block forever if the prompt stays unanswered. */
    private const val TIMEOUT_SECONDS = 10L

    private val backgroundAppOps = listOf(
        "RUN_ANY_IN_BACKGROUND",
        "RUN_IN_BACKGROUND",
        "WAKE_LOCK",
        "START_FOREGROUND",
    )

    fun isRootAvailable(): Boolean = run("id")

    fun applyDozeWhitelist(): Boolean =
        run("cmd deviceidle whitelist +${PackageNames.WECHAT}")

    fun applyBackgroundAppOps(): Boolean =
        backgroundAppOps.map { run("cmd appops set ${PackageNames.WECHAT} $it allow") }.all { it }

    fun applyAndProbe(dozeWhitelist: Boolean, appOps: Boolean): RootStatus {
        if (!isRootAvailable()) return RootStatus(false, false, false)
        if (dozeWhitelist) applyDozeWhitelist()
        if (appOps) applyBackgroundAppOps()
        val doze = output("dumpsys deviceidle whitelist").contains(PackageNames.WECHAT)
        val background =
            output("cmd appops get ${PackageNames.WECHAT} RUN_ANY_IN_BACKGROUND")
                .contains("allow", ignoreCase = true)
        return RootStatus(true, doze, background)
    }

    fun run(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)

    private fun output(command: String): String = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching ""
        }
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrDefault("")
}
