package com.xiao.feng.features.items.bot

import android.content.res.AssetManager
import bsh.Interpreter
import com.xiao.feng.BuildConfig
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.items.scripting_java.JavaEngine
import com.xiao.feng.features.items.scripting_java.JavaPlugin
import com.xiao.feng.features.items.scripting_java.JavaScriptingHook
import com.xiao.feng.loader.startup.StartupInfo
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.fs.KnownPaths
import com.xiao.feng.utils.fs.createDirsSafe
import com.xiao.feng.utils.hookAfterDirectly
import com.xiao.feng.wauxv.data.bean.MsgInfoBean
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 内置机器人脚本运行时。
 *
 * 脚本随 APK 打包在 `assets/bot_scripts/`，首次启用时展开到 `bot_scripts/`，由 [JavaEngine]
 * 里的 BeanShell 解释器执行。它不依赖「脚本引擎 (Java)」功能：只要打开机器人分组里的开关，
 * 对应脚本就会在该进程中直接加载并开始响应群消息。
 */
object BundledBotRuntime {

    private const val TAG = "BundledBotRuntime"
    private const val ASSET_DIR = "bot_scripts"
    private const val MARKER = ".bundled_version"
    private const val DISABLED_FLAG = "disabled.flag"

    private val scriptsDir by lazy { (KnownPaths.moduleRoot / ASSET_DIR).createDirsSafe() }

    private val scripts = ConcurrentHashMap<String, JavaPlugin>()
    private val lock = Any()
    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BundledBotRuntime")
    }

    private var installed = false
    private var messageHookInstalled = false

    /**
     * 设置内置脚本的启用状态。
     *
     * 开关只控制该脚本自己的 `disabled.flag`；展开、加载、卸载都在后台串行执行，
     * 避免阻塞设置界面，也保证同一脚本的启停顺序不会错乱。
     */
    fun setScriptEnabled(scriptId: String, enabled: Boolean) {
        lifecycle.execute {
            ensureInstalled()
            val scriptDir = scriptsDir / scriptId
            if (!scriptDir.isDirectory()) {
                WeLogger.e(TAG, "bundled script '$scriptId' is missing")
                return@execute
            }
            writeDisabledFlag(scriptDir, disabled = !enabled)
            if (enabled) {
                ensureMessageHook()
                loadScript(scriptDir)
            } else {
                unloadScript(scriptId)
            }
        }
    }

    /** 内置脚本目录；首次调用时先展开 APK 内的脚本资源。 */
    fun scriptDir(scriptId: String): Path {
        ensureInstalled()
        return scriptsDir / scriptId
    }

    private fun ensureMessageHook() {
        synchronized(lock) {
            if (messageHookInstalled) return
            WeMessageApi.methodMsgInfoHandleApiInsertMessage.method.hookAfterDirectly {
                val msgBean = MsgInfoBean(args[0]!!)
                JavaEngine.executeAllOnHandleMsg(scripts, msgBean)
            }
            messageHookInstalled = true
        }
    }

    private fun loadScript(scriptDir: Path) {
        val id = scriptDir.name
        if (scripts.containsKey(id)) return

        val mainFile = scriptDir / "main.java"
        val infoFile = scriptDir / "info.prop"
        if (!mainFile.exists() || !infoFile.exists()) {
            WeLogger.w(TAG, "skipping '$id': missing main.java or info.prop")
            return
        }

        val plugin = JavaPlugin(
            name = id,
            dir = scriptDir,
            info = JavaPlugin.parseInfoProp(infoFile.readText()),
            content = mainFile.readText(),
            interpreter = Interpreter(null, ""),
        )
        scripts[id] = plugin
        WeLogger.i(TAG, "loading bundled script '$id'")
        JavaEngine.executeAllOnLoad(mapOf(id to plugin))
    }

    private fun unloadScript(id: String) {
        val plugin = scripts.remove(id) ?: return
        WeLogger.i(TAG, "unloading bundled script '$id'")
        JavaEngine.executeAllOnUnload(mapOf(id to plugin))
    }

    private fun writeDisabledFlag(scriptDir: Path, disabled: Boolean) {
        val flag = scriptDir / DISABLED_FLAG
        if (disabled) flag.writeText("") else flag.deleteIfExists()
    }

    private fun ensureInstalled() {
        synchronized(lock) {
            if (installed) return
            runCatching {
                val ids = installFromModuleApk() ?: installFromAssets()
                cleanupLegacyCopies(ids)
            }
                .onSuccess { installed = true }
                .onFailure { WeLogger.e(TAG, "failed to install bundled bot scripts", it) }
        }
    }

    /**
     * 早期版本把内置脚本当作 Java 脚本引擎的资源展开到 `scripts_java/`。这里清掉那些旧副本，
     * 避免同一个脚本被引擎和机器人运行时重复加载。
     */
    private fun cleanupLegacyCopies(ids: List<String>) {
        ids.forEach { id ->
            if (JavaScriptingHook.isActive) {
                JavaScriptingHook.scripts.remove(id)?.let { plugin ->
                    JavaEngine.executeAllOnUnload(mapOf(id to plugin))
                }
            }
            val legacyDir = KnownPaths.moduleRoot / "scripts_java" / id
            if (legacyDir.isDirectory()) {
                runCatching { legacyDir.toFile().deleteRecursively() }
                    .onFailure { WeLogger.w(TAG, "failed to remove legacy script '$id'", it) }
            }
        }
    }

    /**
     * 从模块 APK 的 assets 展开脚本。资源条目的 size + crc 组成版本标记，脚本内容一有变化
     * 就会重新展开，避免沿用旧文件。
     */
    private fun installFromModuleApk(): List<String>? = runCatching {
        val apk = File(StartupInfo.modulePath)
        if (!apk.isFile) return@runCatching null
        ZipFile(apk).use { zip ->
            val prefix = "$ASSET_DIR/"
            val ids = zip.entries().asSequence()
                .filter { it.name.startsWith(prefix) && !it.isDirectory }
                .map { it.name.removePrefix(prefix).substringBefore('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            if (ids.isEmpty()) return@runCatching null
            ids.forEach { id -> installScriptFromZip(zip, id) }
            ids
        }
    }.getOrElse {
        WeLogger.e(TAG, "failed to read bundled bot scripts from module apk", it)
        null
    }

    private fun installScriptFromZip(zip: ZipFile, id: String) {
        val base = "$ASSET_DIR/$id/"
        val entries = zip.entries().asSequence()
            .filter { it.name.startsWith(base) && !it.isDirectory }
            .sortedBy { it.name }
            .toList()
        if (entries.isEmpty()) return

        val version = entries.joinToString("|") { "${it.name}:${it.size}:${it.crc}" }
        val dir = File(scriptsDir.toFile(), id)
        val marker = File(dir, MARKER)
        if (marker.isFile && marker.readText() == version) return

        val freshInstall = !dir.isDirectory || !marker.isFile
        entries.forEach { entry ->
            val dest = File(dir, entry.name.removePrefix(base))
            dest.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        marker.writeText(version)
        if (freshInstall) File(dir, DISABLED_FLAG).writeText("")
        WeLogger.i(TAG, "installed bundled script '$id'")
    }

    private fun installFromAssets(): List<String> {
        val assets = HostInfo.application.assets
        val ids = assets.list(ASSET_DIR)?.toList().orEmpty()
        ids.forEach { id ->
            val dir = File(scriptsDir.toFile(), id)
            val marker = File(dir, MARKER)
            if (marker.isFile && marker.readText() == BuildConfig.VERSION_NAME) return@forEach

            val freshInstall = !dir.isDirectory || !marker.isFile
            copyAssetTree(assets, "$ASSET_DIR/$id", dir)
            marker.writeText(BuildConfig.VERSION_NAME)
            if (freshInstall) File(dir, DISABLED_FLAG).writeText("")
            WeLogger.i(TAG, "installed bundled script '$id' from assets")
        }
        return ids
    }

    private fun copyAssetTree(assets: AssetManager, assetPath: String, dest: File) {
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        children.forEach { copyAssetTree(assets, "$assetPath/$it", File(dest, it)) }
    }
}
