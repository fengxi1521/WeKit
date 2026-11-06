package com.xiao.feng.features.items.scripting_java

import android.content.ContentValues
import android.content.res.AssetManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import bsh.Interpreter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.BuildConfig
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.extensions.ExtensionPackDialogs
import com.xiao.feng.extensions.ScriptDepsPack
import com.xiao.feng.loader.startup.StartupInfo
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.content.m3.lazySegmentedItems
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.getTopMostActivity
import com.xiao.feng.utils.fs.KnownPaths
import com.xiao.feng.utils.fs.createDirsSafe
import com.xiao.feng.utils.serialization.XmlUtils.extractXmlAttr
import com.xiao.feng.utils.serialization.XmlUtils.extractXmlTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.xiao.feng.wauxv.data.bean.MsgInfoBean
import com.xiao.feng.wauxv.data.bean.PayMsgBean
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

object JavaScriptingHook : ClickableFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "脚本引擎 (Java)"
    override val nameRes = R.string.feature_java_scripting_hook_name
    override val categoryIds = listOf(FeatureCategoryIds.SCRIPTING_JAVA)
    override val descriptionRes = R.string.feature_java_scripting_hook_description

    private const val TAG = "JavaScriptingHook"
    private const val DISABLED_FLAG = "disabled.flag"

    private val SCRIPTS_DIR by lazy { (KnownPaths.moduleRoot / "scripts_java").createDirsSafe() }

    val scripts = ConcurrentHashMap<String, JavaPlugin>()
    private val lifecycleLock = Any()
    private var loadJob: Job? = null
    private var lifecycleGeneration = 0L

    private data class ScriptEntry(
        val dir: Path,
        val info: JavaPluginInfo,
        val enabled: Boolean,
    )

    private val methodPayMsg by dexMethod()

    private const val BUNDLED_SCRIPTS_ASSET_DIR = "scripts_java"
    private const val BUNDLED_SCRIPT_MARKER = ".bundled_version"

    @Volatile
    private var bundledScriptsChecked = false

    /** 展开 APK 内置脚本到 [SCRIPTS_DIR]，只在首次访问时执行一次。 */
    private fun ensureBundledScriptsInstalled() {
        if (bundledScriptsChecked) return
        synchronized(lifecycleLock) {
            if (bundledScriptsChecked) return
            runCatching { installBundledScripts() }
                .onSuccess { bundledScriptsChecked = true }
                .onFailure { WeLogger.e(TAG, "failed to install bundled scripts", it) }
        }
    }

    private fun installBundledScripts() {
        val root = SCRIPTS_DIR.toFile()
        if (installBundledScriptsFromModuleApk(root)) return
        installBundledScriptsFromAssets(root)
    }

    private fun installBundledScriptsFromModuleApk(root: File): Boolean = runCatching {
        val apk = File(StartupInfo.modulePath)
        if (!apk.isFile) return@runCatching false
        ZipFile(apk).use { zip ->
            val prefix = "$BUNDLED_SCRIPTS_ASSET_DIR/"
            val ids = zip.entries().asSequence()
                .filter { it.name.startsWith(prefix) && !it.isDirectory }
                .map { it.name.removePrefix(prefix).substringBefore('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            if (ids.isEmpty()) return@runCatching false
            ids.forEach { id -> installScriptFromZip(zip, root, id) }
            true
        }
    }.getOrElse {
        WeLogger.e(TAG, "failed to read bundled scripts from module apk", it)
        false
    }

    private fun installScriptFromZip(zip: ZipFile, root: File, id: String) {
        val base = "$BUNDLED_SCRIPTS_ASSET_DIR/$id/"
        val scriptEntries = zip.entries().asSequence()
            .filter { it.name.startsWith(base) && !it.isDirectory }
            .sortedBy { it.name }
            .toList()
        if (scriptEntries.isEmpty()) return

        // 用资源条目的 CRC 等元信息作为版本标记，脚本内容一有变化就会重新展开
        val version = scriptEntries.joinToString("|") { "${it.name}:${it.size}:${it.crc}" }
        val dir = File(root, id)
        val marker = File(dir, BUNDLED_SCRIPT_MARKER)
        val installedVersion = marker.takeIf { it.isFile }?.readText()
        if (installedVersion == version) return

        val freshInstall = !dir.isDirectory || installedVersion == null
        scriptEntries.forEach { entry ->
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

    private fun installBundledScriptsFromAssets(root: File) {
        val assets = HostInfo.application.assets
        val ids = runCatching { assets.list(BUNDLED_SCRIPTS_ASSET_DIR) }.getOrNull() ?: return
        ids.forEach { id ->
            val dir = File(root, id)
            val marker = File(dir, BUNDLED_SCRIPT_MARKER)
            val installedVersion = marker.takeIf { it.isFile }?.readText()
            if (installedVersion == BuildConfig.VERSION_NAME) return@forEach

            val freshInstall = !dir.isDirectory || installedVersion == null
            copyAssetTree(assets, "$BUNDLED_SCRIPTS_ASSET_DIR/$id", dir)
            marker.writeText(BuildConfig.VERSION_NAME)
            if (freshInstall) File(dir, DISABLED_FLAG).writeText("")
            WeLogger.i(TAG, "installed bundled script '$id' from assets")
        }
    }

    private fun copyAssetTree(assets: AssetManager, assetPath: String, dest: File) {
        val children = runCatching { assets.list(assetPath) }.getOrNull()
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

    /**
     * 为内置脚本设置启用状态。脚本引擎已运行时即时加载/卸载该脚本；未运行时按需开启引擎，
     * 由引擎启动流程统一加载。
     */
    fun setBundledScriptEnabled(scriptId: String, enabled: Boolean) {
        ensureBundledScriptsInstalled()
        val scriptDir = SCRIPTS_DIR / scriptId
        if (!scriptDir.isDirectory()) {
            WeLogger.e(TAG, "bundled script '$scriptId' is missing")
            return
        }
        if (!setScriptEnabled(scriptDir, enabled)) return

        if (!isActive) {
            if (enabled) applyToggle(true)
            return
        }
        applyScriptStateWhenIdle(scriptDir, enabled)
    }

    private fun applyScriptStateWhenIdle(scriptDir: Path, enabled: Boolean) {
        val apply = {
            CoroutineScope(Dispatchers.IO).launch {
                if (!isActive) return@launch
                synchronized(lifecycleLock) {
                    if (isScriptEnabled(scriptDir) == enabled) applyScriptState(scriptDir, enabled)
                }
            }
        }
        val job = loadJob
        if (job != null && job.isActive) job.invokeOnCompletion { apply() } else apply()
    }

    private fun applyScriptState(scriptDir: Path, enabled: Boolean) {
        if (enabled) loadScript(scriptDir) else unloadScript(scriptDir.name)
    }

    private fun loadScript(scriptDir: Path) {
        val dirName = scriptDir.name
        if (scripts.containsKey(dirName)) return

        val mainFile = scriptDir / "main.java"
        val infoFile = scriptDir / "info.prop"
        if (!mainFile.exists() || !infoFile.exists()) {
            WeLogger.w(TAG, "skipping '$dirName': missing main.java or info.prop")
            return
        }

        val plugin = JavaPlugin(
            name = dirName,
            dir = scriptDir,
            info = JavaPlugin.parseInfoProp(infoFile.readText()),
            content = mainFile.readText(),
            interpreter = Interpreter(null, ""),
        )
        scripts[dirName] = plugin
        WeLogger.i(TAG, "loading bundled script '$dirName'")
        JavaEngine.executeAllOnLoad(mapOf(dirName to plugin))
    }

    private fun unloadScript(dirName: String) {
        val plugin = scripts.remove(dirName) ?: return
        WeLogger.i(TAG, "unloading bundled script '$dirName'")
        JavaEngine.executeAllOnUnload(mapOf(dirName to plugin))
    }

    override fun onEnable() {
        ensureBundledScriptsInstalled()
        val generation = synchronized(lifecycleLock) {
            lifecycleGeneration += 1
            lifecycleGeneration
        }
        WeDatabaseListenerApi.addListener(this)

        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookAfter {
            val msgObj = args[0] ?: return@hookAfter
            val msgBean = MsgInfoBean(msgObj)
            JavaEngine.executeAllOnHandleMsg(scripts, msgBean)
        }

        WeChatInputBarMenuApi.methodSendMessage.hookBefore {
            val chatFooter = thisObject!!.reflekt().firstField {
                type = ChatFooter::class
            }.get()!! as ChatFooter
            val text = chatFooter.lastText
            JavaEngine.executeAllOnClickSendBtn(scripts, this, text)
        }

        methodPayMsg.hookBefore {
            val g2Var = args[0] ?: return@hookBefore
            val payMsgBean = PayMsgBean(g2Var)
            JavaEngine.executeAllOnRecvPayMsg(scripts, payMsgBean)
        }

        loadJob = CoroutineScope(Dispatchers.IO).launch {
            WeLogger.d(TAG, "loading java scripts...")
            val loadedScripts = ConcurrentHashMap<String, JavaPlugin>()
            for (scriptDir in SCRIPTS_DIR.listDirectoryEntries().filter { it.isDirectory() }) {
                currentCoroutineContext().ensureActive()
                val dirName = scriptDir.name
                if (!isScriptEnabled(scriptDir)) {
                    WeLogger.d(TAG, "skipping '$dirName': disabled")
                    continue
                }

                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"
                if (!mainFile.exists() || !infoFile.exists()) {
                    WeLogger.w(TAG, "skipping '$dirName': missing main.java or info.prop")
                    continue
                }

                val content = runCatching { mainFile.readText() }.getOrElse { continue }
                val infoPropContent = runCatching { infoFile.readText() }.getOrElse { continue }
                val info = JavaPlugin.parseInfoProp(infoPropContent)
                WeLogger.d(TAG, "loaded script, name='${info.name}', length=${content.length}")

                val plugin = JavaPlugin(
                    name = dirName,
                    dir = scriptDir,
                    info = info,
                    content = content,
                    interpreter = Interpreter(null, "")
                )
                loadedScripts[dirName] = plugin
            }

            currentCoroutineContext().ensureActive()
            synchronized(lifecycleLock) {
                check(lifecycleGeneration == generation)
                scripts.clear()
                scripts.putAll(loadedScripts)
                JavaEngine.executeAllOnLoad(scripts)
            }
        }

        if (!ScriptDepsPack.isInstalled()) {
            getTopMostActivity(allowPaused = true)?.let { activity ->
                ExtensionPackDialogs.suggestInstall(activity, ScriptDepsPack)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        val entries = listScriptEntries()
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.java_scripts_dialog_title)) },
                text = {
                    if (entries.isEmpty()) {
                        Text(stringResource(R.string.java_scripts_empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                        ) {
                            lazySegmentedItems(entries, key = { it.dir.name }) { entry ->
                                var enabled by remember(entry.dir) { mutableStateOf(entry.enabled) }
                                val statusText = stringResource(
                                    if (enabled) R.string.java_script_status_enabled
                                    else R.string.java_script_status_disabled
                                )
                                val versionText =
                                    entry.info.version?.let { stringResource(R.string.java_script_version, it) }
                                val authorText =
                                    entry.info.author?.let { stringResource(R.string.java_script_author, it) }

                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = if (entry.info.name == "unnamed") {
                                        stringResource(R.string.java_script_unnamed)
                                    } else {
                                        entry.info.name
                                    },
                                    description = buildList {
                                        add(entry.dir.name)
                                        add(statusText)
                                        versionText?.let { add(it) }
                                        authorText?.let { add(it) }
                                    }.joinToString(" · "),
                                    checked = enabled,
                                    onCheckedChange = { newState ->
                                        if (setScriptEnabled(entry.dir, newState)) {
                                            enabled = newState
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                },
            )
        }
    }

    private fun listScriptEntries(): List<ScriptEntry> {
        ensureBundledScriptsInstalled()
        return SCRIPTS_DIR.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .mapNotNull { scriptDir ->
                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"
                if (!mainFile.exists() || !infoFile.exists()) return@mapNotNull null

                val info = runCatching {
                    JavaPlugin.parseInfoProp(infoFile.readText())
                }.getOrNull() ?: return@mapNotNull null
                ScriptEntry(
                    dir = scriptDir,
                    info = info,
                    enabled = isScriptEnabled(scriptDir),
                )
            }
    }

    private fun isScriptEnabled(scriptDir: Path): Boolean =
        !(scriptDir / DISABLED_FLAG).exists()

    private fun setScriptEnabled(scriptDir: Path, enabled: Boolean): Boolean = runCatching {
        val disabledFlag = scriptDir / DISABLED_FLAG
        if (enabled) {
            disabledFlag.deleteIfExists()
        } else {
            disabledFlag.writeText("")
        }
        true
    }.onFailure {
        WeLogger.w(TAG, "failed to ${if (enabled) "enable" else "disable"} script '${scriptDir.name}'", it)
    }.getOrDefault(false)

    override fun onDisable() {
        synchronized(lifecycleLock) {
            lifecycleGeneration += 1
        }
        loadJob?.cancel()
        loadJob = null
        WeDatabaseListenerApi.removeListener(this)
        JavaHookApi.unhookEverything()
        synchronized(lifecycleLock) {
            JavaEngine.executeAllOnUnload(scripts)
            scripts.clear()
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table == "fmessage_msginfo") {
            val isSend = values.getAsInteger("isSend") ?: 0
            if (isSend == 0) {
                val msgContent = values.getAsString("msgContent") ?: ""
                val fromusername = extractXmlAttr(msgContent, "encryptusername").takeIf { it.isNotEmpty() }
                    ?: extractXmlAttr(msgContent, "fromusername").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "fromusername")
                val ticket = extractXmlAttr(msgContent, "ticket").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "ticket")
                val sceneStr = extractXmlAttr(msgContent, "scene").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "scene")
                val scene = sceneStr.toIntOrNull() ?: 0

                JavaEngine.executeAllOnNewFriend(scripts, fromusername, ticket, scene)
            }
        }
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "chatroom") return
        val chatroomName = values.getAsString("chatroomname") ?: return
        val memberCount = values.getAsInteger("memberCount") ?: return
        val memberlist = values.getAsString("memberlist") ?: return
        if (memberlist.isBlank()) return

        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist, memberCount FROM chatroom WHERE chatroomname = ?",
            arrayOf(chatroomName)
        )
        if (cursor.moveToFirst()) {
            val oldMemberCount = cursor.getInt(cursor.getColumnIndexOrThrow("memberCount"))
            val oldMemberListStr = cursor.getString(cursor.getColumnIndexOrThrow("memberlist"))
            cursor.close()

            if (oldMemberCount == 0 || oldMemberListStr.isNullOrBlank()) return

            val oldMembers = oldMemberListStr.split(";").filter { it.isNotBlank() }.toSet()
            val newMembers = memberlist.split(";").filter { it.isNotBlank() }.toSet()

            if (memberCount > oldMemberCount) {
                val joined = newMembers - oldMembers
                joined.forEach { userWxid ->
                    val nickname = WeDatabaseApi.getDisplayName(userWxid)
                    JavaEngine.executeAllOnMemberChange(scripts, "join", chatroomName, userWxid, nickname)
                }
            } else if (memberCount < oldMemberCount) {
                val left = oldMembers - newMembers
                left.forEach { userWxid ->
                    val nickname = WeDatabaseApi.getDisplayName(userWxid)
                    JavaEngine.executeAllOnMemberChange(scripts, "left", chatroomName, userWxid, nickname)
                }
            }
        }
    }
}
