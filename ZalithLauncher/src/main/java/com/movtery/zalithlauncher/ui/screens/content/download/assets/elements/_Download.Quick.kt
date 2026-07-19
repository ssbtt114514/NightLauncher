/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content.download.assets.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.download.assets.platform.Platform
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformClasses
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformDependencyType
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformProject
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformVersion
import com.movtery.zalithlauncher.game.download.assets.platform.getProjectByVersion
import com.movtery.zalithlauncher.game.download.assets.platform.getVersions
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.game.version.mod.AllModReader
import com.movtery.zalithlauncher.ui.components.MarqueeText
import com.movtery.zalithlauncher.ui.components.ScalingLabel
import com.movtery.zalithlauncher.ui.theme.cardColor
import com.movtery.zalithlauncher.ui.theme.onCardColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 一键下载信息：包含主资源和所有前置依赖的版本信息
 */
data class QuickDownloadInfo(
    val targetVersion: PlatformVersion,
    val dependencyVersions: List<PlatformVersion>,
    val targetProject: PlatformProject,
    val dependencyProjects: List<PlatformProject>,
    val gameVersions: List<Version>,
    val classes: PlatformClasses,
    /** 解析后的完整依赖关系图，用于确认界面展示 */
    val resolvedNodes: List<ResolvedDependencyNode> = emptyList()
)

/**
 * 模组关系类型
 */
enum class DependencyRelationship {
    MAIN,       // 主资源
    REQUIRED,   // 必需前置
    OPTIONAL,   // 可选前置
    INCOMPATIBLE// 不兼容模组
}

/**
 * 解析后的依赖节点
 * @param project 项目信息（可选/不兼容项可能解析失败而为 null）
 * @param version 选中的版本（可选/不兼容项为 null）
 * @param platform 所属平台
 * @param projectId 项目ID
 * @param relationship 与父资源的关系
 * @param depth 在依赖树中的深度，主资源为 0
 * @param parentProjectId 父项目ID
 */
data class ResolvedDependencyNode(
    val project: PlatformProject?,
    val version: PlatformVersion?,
    val platform: Platform,
    val projectId: String,
    val relationship: DependencyRelationship,
    val depth: Int,
    val parentProjectId: String?
)

/**
 * 一键下载对话框状态
 */
sealed interface QuickDownloadState {
    data object Loading : QuickDownloadState
    data object NoGameVersion : QuickDownloadState
    data class Ready(
        val targetProject: PlatformProject,
        val targetVersion: PlatformVersion,
        /** 要下载的节点：主资源 + 必需前置 */
        val downloadableNodes: List<ResolvedDependencyNode>,
        /** 可选前置 */
        val optionalNodes: List<ResolvedDependencyNode>,
        /** 不兼容模组 */
        val incompatibleNodes: List<ResolvedDependencyNode>,
        /** 光影包着色器模组警告（仅 SHADERS 类型），非空时需要提醒玩家 */
        val shaderWarning: String? = null
    ) : QuickDownloadState
    /**
     * 解析失败：主资源或某个必需前置缺少对应的加载器/MC 版本。
     * [failures] 中每一项描述一个项目不符合兼容性的原因。
     */
    data class Failed(val failures: List<CompatibilityFailure>) : QuickDownloadState
    data class Error(val message: String) : QuickDownloadState
}

/**
 * 某个项目与当前实例的兼容性失败原因
 * @param projectTitle 项目标题
 * @param platform 所属平台
 * @param projectId 项目ID
 * @param reason 失败原因（已本地化的字符串）
 */
data class CompatibilityFailure(
    val projectTitle: String,
    val platform: Platform,
    val projectId: String,
    val reason: String
)

/** 着色器模组检测结果缓存，key = 实例游戏目录路径，防止重复检测 */
private val shaderCheckCache = mutableMapOf<String, Boolean>()

/**
 * 清除指定实例的光影加载器检测缓存。
 * 在每次打开光影下载界面时调用，确保该实例首次下载光影时重新扫描。
 */
internal fun clearShaderCheckCacheFor(version: Version?) {
    version?.getGameDir()?.absolutePath?.let { shaderCheckCache.remove(it) }
}

private class QuickDownloadViewModel(
    private val platform: Platform,
    private val projectId: String,
    private val classes: PlatformClasses,
    private val reasonProvider: FailureReasonProvider,
    /** 指定下载目标实例；优先于 [VersionsManager.currentVersion] */
    private val gameVersion: Version? = null
) : ViewModel() {
    var state by mutableStateOf<QuickDownloadState>(QuickDownloadState.Loading)
        private set

    private val processedProjects = mutableSetOf<String>()
    private var resolveJob: Job? = null

    fun start() {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            state = QuickDownloadState.Loading
            processedProjects.clear()

            val targetGameVersion = gameVersion ?: VersionsManager.currentVersion.value ?: run {
                state = QuickDownloadState.NoGameVersion
                return@launch
            }

            val mcVersion = targetGameVersion.getVersionInfo()?.minecraftVersion ?: run {
                state = QuickDownloadState.Error("Failed to get Minecraft version")
                return@launch
            }

            val modLoader = targetGameVersion.getVersionInfo()?.loaderInfo?.loader?.displayName ?: ""
            val isModType = classes == PlatformClasses.MOD

            try {
                val failures = mutableListOf<CompatibilityFailure>()
                val nodes = mutableListOf<ResolvedDependencyNode>()
                val semaphore = Semaphore(8)
                val processedLock = Any()

                /**
                 * 解析单个项目，返回选中的版本；不兼容时返回 null 并向 [failuresOut] 追加原因。
                 *
                 * 流程：
                 * 1. 项目级加载器检查（MOD 类型）
                 * 2. 获取所有版本
                 * 3. 过滤 MC 版本
                 * 4. MOD 类型再过滤版本级加载器
                 * 5. 优先选择星标/featured 版本中的最新版，无星标则取最新版
                 * 6. 递归处理必需依赖；可选/不兼容仅解析项目信息并记录
                 */
                suspend fun processProject(
                    currentPlatform: Platform,
                    currentProjectId: String,
                    relationship: DependencyRelationship,
                    depth: Int,
                    parentProjectId: String?,
                    failuresOut: MutableList<CompatibilityFailure>
                ): Pair<PlatformProject, PlatformVersion>? {
                    val project = getProjectByVersion(currentProjectId, currentPlatform)
                    val projectTitle = project.platformTitle()

                    // 第一步：项目级加载器检查（仅 MOD 类型）
                    if (isModType) {
                        val projectLoaders = project.platformModLoaders()?.map { it.getDisplayName() } ?: emptyList()
                        val loaderSupported = projectLoaders.isEmpty() || modLoader.isEmpty() ||
                            projectLoaders.any { it == modLoader }

                        if (!loaderSupported) {
                            failuresOut.add(
                                CompatibilityFailure(
                                    projectTitle = projectTitle,
                                    platform = currentPlatform,
                                    projectId = currentProjectId,
                                    reason = reasonProvider.noMatchingLoader(modLoader, projectLoaders.toSet())
                                )
                            )
                            return null
                        }
                    }

                    // 第二步：获取并初始化所有版本
                    val allVersions = getVersions(currentProjectId, currentPlatform)
                        .initAllGeneric(currentProjectId = currentProjectId)

                    if (allVersions.isEmpty()) {
                        failuresOut.add(
                            CompatibilityFailure(
                                projectTitle = projectTitle,
                                platform = currentPlatform,
                                projectId = currentProjectId,
                                reason = reasonProvider.noVersions()
                            )
                        )
                        return null
                    }

                    // 第三步：过滤 MC 版本
                    val mcMatched = allVersions.filter { version ->
                        version.platformGameVersion().any { mcVersion == it || mcVersion.startsWith(it) || it.startsWith(mcVersion) }
                    }

                    if (mcMatched.isEmpty()) {
                        val supportedVersions = allVersions
                            .flatMap { it.platformGameVersion().asIterable() }
                            .toSet()
                        failuresOut.add(
                            CompatibilityFailure(
                                projectTitle = projectTitle,
                                platform = currentPlatform,
                                projectId = currentProjectId,
                                reason = reasonProvider.noMatchingMcVersion(mcVersion, supportedVersions)
                            )
                        )
                        return null
                    }

                    // 第四步：MOD 类型再过滤版本级加载器
                    val candidates = if (isModType) {
                        mcMatched.filter { version ->
                            val versionLoaders = version.platformLoaders().map { it.getDisplayName() }
                            versionLoaders.isEmpty() || modLoader.isEmpty() || versionLoaders.any { it == modLoader }
                        }
                    } else mcMatched

                    if (isModType && candidates.isEmpty()) {
                        val supportedLoaders = mcMatched
                            .flatMap { it.platformLoaders().map { loader -> loader.getDisplayName() } }
                            .toSet()
                        failuresOut.add(
                            CompatibilityFailure(
                                projectTitle = projectTitle,
                                platform = currentPlatform,
                                projectId = currentProjectId,
                                reason = reasonProvider.noMatchingLoader(modLoader, supportedLoaders)
                            )
                        )
                        return null
                    }

                    // 第五步：优先选择星标版本中的最新版，没有则使用最新版
                    val featuredVersions = candidates.filter { it.isFeatured() }
                    val selectedVersion = featuredVersions.firstOrNull() ?: candidates.first()

                    // 记录节点
                    nodes.add(
                        ResolvedDependencyNode(
                            project = project,
                            version = selectedVersion,
                            platform = currentPlatform,
                            projectId = currentProjectId,
                            relationship = relationship,
                            depth = depth,
                            parentProjectId = parentProjectId
                        )
                    )

                    // 第六步：处理依赖
                    val allDeps = selectedVersion.platformDependencies()
                        .filter { it.projectId.isNotEmpty() }

                    val requiredDeps = allDeps.filter {
                        it.type == PlatformDependencyType.REQUIRED ||
                        it.type == PlatformDependencyType.EMBEDDED ||
                        it.type == PlatformDependencyType.INCLUDE
                    }
                    val optionalDeps = allDeps.filter {
                        it.type == PlatformDependencyType.OPTIONAL ||
                        it.type == PlatformDependencyType.TOOL
                    }
                    val incompatibleDeps = allDeps.filter {
                        it.type == PlatformDependencyType.INCOMPATIBLE
                    }

                    // 递归处理必需依赖
                    if (requiredDeps.isNotEmpty()) {
                        coroutineScope {
                            requiredDeps.map { dep ->
                                async {
                                    semaphore.withPermit {
                                        val alreadyProcessed = synchronized(processedLock) {
                                            dep.projectId in processedProjects
                                        }
                                        if (alreadyProcessed) return@async null
                                        synchronized(processedLock) {
                                            processedProjects.add(dep.projectId)
                                        }
                                        runCatching {
                                            processProject(
                                                dep.platform,
                                                dep.projectId,
                                                DependencyRelationship.REQUIRED,
                                                depth + 1,
                                                currentProjectId,
                                                failuresOut
                                            )
                                        }.getOrNull()
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }
                    }

                    // 可选/不兼容依赖：仅解析项目信息，不递归
                    val infoDeps = optionalDeps.map { it to DependencyRelationship.OPTIONAL } +
                        incompatibleDeps.map { it to DependencyRelationship.INCOMPATIBLE }

                    if (infoDeps.isNotEmpty()) {
                        coroutineScope {
                            infoDeps.map { (dep, rel) ->
                                async {
                                    semaphore.withPermit {
                                        val depProject = runCatching {
                                            getProjectByVersion(dep.projectId, dep.platform, printLog = false)
                                        }.getOrNull()
                                        ResolvedDependencyNode(
                                            project = depProject,
                                            version = null,
                                            platform = dep.platform,
                                            projectId = dep.projectId,
                                            relationship = rel,
                                            depth = depth + 1,
                                            parentProjectId = currentProjectId
                                        )
                                    }
                                }
                            }.awaitAll().forEach { node ->
                                nodes.add(node)
                            }
                        }
                    }

                    return project to selectedVersion
                }

                processedProjects.add(projectId)
                val targetResult = processProject(
                    platform,
                    projectId,
                    DependencyRelationship.MAIN,
                    0,
                    null,
                    failures
                )

                if (targetResult == null || failures.isNotEmpty()) {
                    state = QuickDownloadState.Failed(failures)
                } else {
                    val downloadableNodes = nodes.filter {
                        it.relationship == DependencyRelationship.MAIN ||
                        it.relationship == DependencyRelationship.REQUIRED
                    }
                    val optionalNodes = nodes.filter { it.relationship == DependencyRelationship.OPTIONAL }
                    val incompatibleNodes = nodes.filter { it.relationship == DependencyRelationship.INCOMPATIBLE }

                    // 光影包：检查 Iris/Oculus/OptiFine 是否安装
                    var shaderWarning: String? = null
                    if (classes == PlatformClasses.SHADERS) {
                        shaderWarning = checkShaderDependency(
                            gameVersion = targetGameVersion,
                            mcVersion = mcVersion,
                            modLoader = modLoader,
                            context = reasonProvider.context
                        )
                    }

                    state = QuickDownloadState.Ready(
                        targetProject = targetResult.first,
                        targetVersion = targetResult.second,
                        downloadableNodes = downloadableNodes,
                        optionalNodes = optionalNodes,
                        incompatibleNodes = incompatibleNodes,
                        shaderWarning = shaderWarning
                    )
                }
            } catch (e: Exception) {
                state = QuickDownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    override fun onCleared() {
        resolveJob?.cancel()
    }
}

/**
 * 提供失败原因的本地化文本
 */
class FailureReasonProvider(val context: android.content.Context) {
    fun noVersions(): String = context.getString(R.string.download_quick_download_no_versions)

    fun noMatchingMcVersion(currentMc: String, supportedMc: Set<String>): String {
        val supportedText = supportedMc.joinToString(", ")
        return context.getString(R.string.download_quick_download_no_mc_version, currentMc, supportedText)
    }

    fun noMatchingLoader(currentLoader: String, supportedLoaders: Set<String>): String {
        val supportedText = supportedLoaders.joinToString(", ")
        return context.getString(R.string.download_quick_download_no_loader, currentLoader, supportedText)
    }
}

// ─── 着色器模组检测 ────────────────────────────────────────────────

/**
 * 检测当前实例是否安装了 Iris、Oculus 或 OptiFine。
 * 每个实例（按游戏目录）只检测一次，结果缓存复用。
 *
 * @return 如果未安装任何着色器模组，返回提醒文本；否则返回 null
 */
private suspend fun checkShaderDependency(
    gameVersion: Version,
    mcVersion: String,
    modLoader: String,
    context: android.content.Context
): String? {
    val gameDir = gameVersion.getGameDir().absolutePath
    val cached = shaderCheckCache[gameDir]
    if (cached != null) {
        return if (cached) null else getShaderSuggestion(mcVersion, modLoader, context)
    }

    val modsDir = VersionFolders.MOD.getDir(gameVersion.getGameDir())
    val hasShaderMod = try {
        val mods = AllModReader(modsDir).readAllLocals()
        mods.any { mod ->
            mod.id.equals("iris", ignoreCase = true) ||
            mod.id.equals("oculus", ignoreCase = true) ||
            mod.loader == ModLoader.OPTIFINE
        }
    } catch (_: Exception) {
        false
    }

    shaderCheckCache[gameDir] = hasShaderMod
    return if (hasShaderMod) null else getShaderSuggestion(mcVersion, modLoader, context)
}

/**
 * 根据 MC 版本和加载器类型，返回着色器模组建议文本。
 */
private fun getShaderSuggestion(
    mcVersion: String,
    modLoader: String,
    context: android.content.Context
): String {
    val mcVer = parseMcVersion(mcVersion)

    return when {
        modLoader == ModLoader.FORGE.displayName &&
            mcVer != null && mcVer >= intArrayOf(1, 16, 5) && mcVer <= intArrayOf(1, 20, 1) ->
            context.getString(R.string.download_quick_download_shader_suggest_oculus)

        (modLoader == ModLoader.FABRIC.displayName || modLoader == ModLoader.QUILT.displayName) &&
            mcVer != null && mcVer >= intArrayOf(1, 16, 5) ->
            context.getString(R.string.download_quick_download_shader_suggest_iris)

        modLoader == ModLoader.NEOFORGE.displayName &&
            mcVer != null && mcVer >= intArrayOf(1, 21) ->
            context.getString(R.string.download_quick_download_shader_suggest_iris)

        else -> context.getString(R.string.download_quick_download_shader_suggest_optifine)
    }
}

private fun parseMcVersion(version: String): IntArray? {
    return try {
        val parts = version.split(".").map { it.toInt() }
        if (parts.size < 2) null else parts.toIntArray()
    } catch (_: NumberFormatException) {
        null
    }
}

private operator fun IntArray.compareTo(other: IntArray): Int {
    for (i in 0 until minOf(size, other.size)) {
        val cmp = this[i] - other[i]
        if (cmp != 0) return cmp
    }
    return size - other.size
}

// ─── UI ────────────────────────────────────────────────────────────

@Composable
fun QuickDownloadDialog(
    platform: Platform,
    projectId: String,
    classes: PlatformClasses,
    /** 指定下载目标实例；优先于 [VersionsManager.currentVersion] */
    gameVersion: Version? = null,
    onDismiss: () -> Unit,
    onDownload: (QuickDownloadInfo) -> Unit
) {
    val context = LocalContext.current
    val reasonProvider = remember { FailureReasonProvider(context) }

    val viewModel = viewModel(
        key = "$platform|$projectId|$classes|$gameVersion"
    ) {
        QuickDownloadViewModel(platform, projectId, classes, reasonProvider, gameVersion)
    }

    LaunchedEffect(Unit) {
        viewModel.start()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.padding(all = 6.dp).heightIn(max = maxHeight - 12.dp).wrapContentHeight(),
                shape = MaterialTheme.shapes.extraLarge,
                color = cardColor(false),
                contentColor = onCardColor(),
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.download_quick_download),
                        style = MaterialTheme.typography.titleLarge
                    )

                    when (val state = viewModel.state) {
                        QuickDownloadState.Loading -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f), wavelength = 32.dp)
                                Text(
                                    text = stringResource(R.string.download_quick_download_resolving),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        QuickDownloadState.NoGameVersion -> {
                            ScalingLabel(
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                text = {
                                    Text(stringResource(R.string.download_assets_no_installed_versions))
                                },
                                onClick = onDismiss
                            )
                        }

                        is QuickDownloadState.Failed -> {
                            Text(
                                text = stringResource(R.string.download_quick_download_incompatible),
                                style = MaterialTheme.typography.titleMedium
                            )

                            val listState = rememberLazyListState()
                            LazyColumn(
                                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 300.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                state = listState
                            ) {
                                items(state.failures) { failure ->
                                    CompatibilityFailureItem(failure = failure)
                                }
                            }

                            FilledTonalButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onDismiss
                            ) {
                                MarqueeText(text = stringResource(R.string.generic_confirm))
                            }
                        }

                        is QuickDownloadState.Error -> {
                            ScalingLabel(
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                text = {
                                    Text(state.message)
                                },
                                onClick = onDismiss
                            )
                        }

                        is QuickDownloadState.Ready -> {
                            state.shaderWarning?.let { warning ->
                                ShaderWarningCard(warning = warning)
                            }

                            val listState = rememberLazyListState()
                            LazyColumn(
                                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 300.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                state = listState
                            ) {
                                // 下载列表
                                item {
                                    Text(
                                        text = stringResource(R.string.download_quick_download_list),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                items(state.downloadableNodes) { node ->
                                    QuickDownloadItem(node = node)
                                }

                                // 可选前置
                                if (state.optionalNodes.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = stringResource(R.string.download_quick_download_optional_list),
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                    items(state.optionalNodes) { node ->
                                        QuickDownloadItem(node = node)
                                    }
                                }

                                // 不兼容模组
                                if (state.incompatibleNodes.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = stringResource(R.string.download_quick_download_incompatible_list),
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                    items(state.incompatibleNodes) { node ->
                                        QuickDownloadItem(node = node)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                FilledTonalButton(
                                    modifier = Modifier.weight(0.5f),
                                    onClick = onDismiss
                                ) {
                                    MarqueeText(text = stringResource(R.string.generic_cancel))
                                }
                                Button(
                                    modifier = Modifier.weight(0.5f),
                                    onClick = {
                                        val targetGameVersion = gameVersion ?: VersionsManager.currentVersion.value ?: return@Button
                                        val gameVersions = listOf(targetGameVersion)

                                        onDownload(
                                            QuickDownloadInfo(
                                                targetVersion = state.targetVersion,
                                                dependencyVersions = state.downloadableNodes
                                                    .filter { it.relationship == DependencyRelationship.REQUIRED }
                                                    .mapNotNull { it.version },
                                                targetProject = state.targetProject,
                                                dependencyProjects = state.downloadableNodes
                                                    .filter { it.relationship == DependencyRelationship.REQUIRED }
                                                    .mapNotNull { it.project },
                                                gameVersions = gameVersions,
                                                classes = classes,
                                                resolvedNodes = state.downloadableNodes + state.optionalNodes + state.incompatibleNodes
                                            )
                                        )
                                    }
                                ) {
                                    MarqueeText(text = stringResource(R.string.download_install))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShaderWarningCard(warning: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(
            text = warning,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun QuickDownloadItem(node: ResolvedDependencyNode) {
    val project = node.project
    val title = remember(project) { project?.platformTitle() ?: node.projectId }
    val author = remember(project) { project?.platformAuthor() }
    val iconUrl = remember(project) { project?.platformIconUrl() }
    val platform = remember(node) { project?.platform() ?: node.platform }

    val relationshipLabel = when (node.relationship) {
        DependencyRelationship.MAIN -> R.string.download_quick_download_main
        DependencyRelationship.REQUIRED -> R.string.download_quick_download_required
        DependencyRelationship.OPTIONAL -> R.string.download_quick_download_optional
        DependencyRelationship.INCOMPATIBLE -> R.string.download_quick_download_incompatible_project
    }

    val relationshipColor = when (node.relationship) {
        DependencyRelationship.MAIN -> MaterialTheme.colorScheme.primary
        DependencyRelationship.REQUIRED -> MaterialTheme.colorScheme.secondary
        DependencyRelationship.OPTIONAL -> MaterialTheme.colorScheme.tertiary
        DependencyRelationship.INCOMPATIBLE -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = (node.depth * 16).dp),
        shape = MaterialTheme.shapes.large,
        color = when (node.relationship) {
            DependencyRelationship.MAIN -> cardColor(false)
            DependencyRelationship.INCOMPATIBLE -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (node.relationship == DependencyRelationship.INCOMPATIBLE) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            onCardColor()
        }
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssetsIcon(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                size = 40.dp,
                iconUrl = iconUrl
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(relationshipLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = relationshipColor,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    ProjectTitleHead(
                        platform = platform,
                        title = title,
                        author = author
                    )
                }
                node.version?.let { version ->
                    Text(
                        text = version.platformVersion(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompatibilityFailureItem(failure: CompatibilityFailure) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = failure.projectTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = failure.reason,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}