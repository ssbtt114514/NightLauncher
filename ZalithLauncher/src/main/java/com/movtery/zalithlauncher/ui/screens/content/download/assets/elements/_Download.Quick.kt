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
import com.movtery.zalithlauncher.game.download.assets.platform.Platform
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformClasses
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformDependencyType
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformProject
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformVersion
import com.movtery.zalithlauncher.game.download.assets.platform.getProjectByVersion
import com.movtery.zalithlauncher.game.download.assets.platform.getVersions
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
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
    val classes: PlatformClasses
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
        val dependencies: List<Pair<PlatformProject, PlatformVersion>>
    ) : QuickDownloadState
    data class Error(val message: String) : QuickDownloadState
}

private class QuickDownloadViewModel(
    private val platform: Platform,
    private val projectId: String,
    private val classes: PlatformClasses
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

            val gameVersion = VersionsManager.currentVersion.value ?: run {
                state = QuickDownloadState.NoGameVersion
                return@launch
            }

            val mcVersion = gameVersion.getVersionInfo()?.minecraftVersion ?: run {
                state = QuickDownloadState.Error("Failed to get Minecraft version")
                return@launch
            }

            val modLoader = gameVersion.getVersionInfo()?.loaderInfo?.loader?.displayName ?: ""

            try {
                val result = resolveDependencies(platform, projectId, mcVersion, modLoader)
                val targetProject = result.first
                val targetVersion = result.second
                val deps = result.third

                state = QuickDownloadState.Ready(targetProject, targetVersion, deps)
            } catch (e: Exception) {
                state = QuickDownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    private suspend fun resolveDependencies(
        platform: Platform,
        projectId: String,
        mcVersion: String,
        modLoader: String
    ): Triple<PlatformProject, PlatformVersion, List<Pair<PlatformProject, PlatformVersion>>> {
        val deps = mutableListOf<Pair<PlatformProject, PlatformVersion>>()
        val semaphore = Semaphore(8)
        val processedLock = Any()

        fun isVersionCompatible(version: PlatformVersion): Boolean {
            val gameVersions = version.platformGameVersion()
            val loaders = version.platformLoaders().map { it.getDisplayName() }

            val gameVersionMatch = gameVersions.any { mcVersion.startsWith(it) || it.startsWith(mcVersion) }
            val loaderMatch = loaders.isEmpty() || modLoader.isEmpty() || loaders.any { modLoader.contains(it) }

            return gameVersionMatch && loaderMatch
        }

        suspend fun processProject(currentPlatform: Platform, currentProjectId: String): Pair<PlatformProject, PlatformVersion> {
            val project = getProjectByVersion(currentProjectId, currentPlatform)

            val versions = getVersions(currentProjectId, currentPlatform)
                .filter { isVersionCompatible(it) }
                .initAllGeneric(currentProjectId = currentProjectId)

            if (versions.isEmpty()) {
                error("No compatible version for project ${project.platformTitle()}")
            }

            //initAllGeneric 已按发布日期降序排序，取第一个即为最新版本
            val latestVersion = versions.first()

            val requiredDeps = latestVersion.platformDependencies()
                .filter { it.type == PlatformDependencyType.REQUIRED && it.projectId.isNotEmpty() }

            if (requiredDeps.isNotEmpty()) {
                val depResults = coroutineScope {
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
                                    processProject(dep.platform, dep.projectId)
                                }.getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                synchronized(deps) {
                    deps.addAll(depResults)
                }
            }

            return project to latestVersion
        }

        val result = processProject(platform, projectId)
        return Triple(result.first, result.second, deps)
    }

    override fun onCleared() {
        resolveJob?.cancel()
    }
}

@Composable
fun QuickDownloadDialog(
    platform: Platform,
    projectId: String,
    classes: PlatformClasses,
    onDismiss: () -> Unit,
    onDownload: (QuickDownloadInfo) -> Unit
) {
    val viewModel = viewModel(
        key = "$platform|$projectId|$classes"
    ) {
        QuickDownloadViewModel(platform, projectId, classes)
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
                            val allItems = mutableListOf<Pair<PlatformProject, PlatformVersion>>().apply {
                                add(state.targetProject to state.targetVersion)
                                addAll(state.dependencies)
                            }

                            Text(
                                text = stringResource(R.string.download_quick_download_list),
                                style = MaterialTheme.typography.titleMedium
                            )

                            val listState = rememberLazyListState()
                            LazyColumn(
                                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 300.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                state = listState
                            ) {
                                items(allItems) { (project, version) ->
                                    QuickDownloadItem(
                                        project = project,
                                        version = version,
                                        isMain = project.platformId() == projectId
                                    )
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
                                        val gameVersion = VersionsManager.currentVersion.value ?: return@Button
                                        val gameVersions = listOf(gameVersion)

                                        onDownload(
                                            QuickDownloadInfo(
                                                targetVersion = state.targetVersion,
                                                dependencyVersions = state.dependencies.map { it.second },
                                                targetProject = state.targetProject,
                                                dependencyProjects = state.dependencies.map { it.first },
                                                gameVersions = gameVersions,
                                                classes = classes
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
private fun QuickDownloadItem(
    project: PlatformProject,
    version: PlatformVersion,
    isMain: Boolean
) {
    val context = LocalContext.current
    val title = remember { project.platformTitle() }
    val summary = remember { project.platformSummary() }
    val iconUrl = remember { project.platformIconUrl() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isMain) cardColor(false) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = onCardColor()
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
                    if (isMain) {
                        Text(
                            text = stringResource(R.string.download_quick_download_main),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                summary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = version.platformVersion(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
