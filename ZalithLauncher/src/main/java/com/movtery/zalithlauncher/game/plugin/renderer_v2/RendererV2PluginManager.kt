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

package com.movtery.zalithlauncher.game.plugin.renderer_v2

import android.content.Context
import android.content.pm.PackageManager
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.plugin.ApkPlugin
import com.movtery.zalithlauncher.game.plugin.cacheAppIcon
import com.movtery.zalithlauncher.game.plugin.renderer_v2.data.RendererConfigList
import com.movtery.zalithlauncher.path.GLOBAL_JSON
import com.movtery.zalithlauncher.utils.logging.Logger

object RendererV2PluginManager {
    private const val TAG = "RendererV2Plugin"
    private val rendererPluginList: MutableList<RendererV2Data> = mutableListOf()
    private val packageNameList: MutableList<String> = mutableListOf()

    /**
     * 获取所有已加载的插件提供的渲染器数据
     */
    fun getRendererList(): List<RendererV2Data> = rendererPluginList

    /**
     * 获取所有已加载的插件的包名
     */
    fun getPackageNameList(): List<String> = packageNameList

    fun clearPlugin() {
        rendererPluginList.clear()
        packageNameList.clear()
    }

    /**
     * 从 MMKV 加载已保存的插件配置
     * 同时清理已卸载插件对应的残留配置
     */
    fun initialize(
        context: Context,
        loaded: (ApkPlugin) -> Unit
    ) {
        val mmkv = rendererPluginMMKV()
        val keys = mmkv.allKeys() ?: emptyArray()
        var removedCount = 0

        val pm = context.packageManager

        keys.forEach { packageName ->
            // 检查该插件是否存在
            val isInstalled = runCatching {
                pm.getPackageInfo(packageName, 0)
            }.isSuccess
            // 不存在则清除配置
            if (!isInstalled) {
                mmkv.remove(packageName)
                removedCount++
                Logger.info(TAG, "Removed stale config for uninstalled package: $packageName")
                return@forEach
            }

            val json = mmkv.getString(packageName, null) ?: return@forEach
            runCatching {
                val configList = GLOBAL_JSON.decodeFromString<RendererConfigList>(json)
                // 获取插件应用名称
                val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val appLabel = pm.getApplicationLabel(appInfo).toString()

                // 已成功加载目标插件
                runCatching {
                    cacheAppIcon(context, appInfo)
                    ApkPlugin(
                        packageName = packageName,
                        appName = appLabel,
                        appVersion = pm.getPackageInfo(packageName, 0).versionName ?: ""
                    )
                }.getOrNull()?.let { loaded(it) }

                packageNameList.add(packageName)

                configList.data.forEach { data ->
                    val renderer = RendererV2Data(
                        packageName = packageName,
                        summary = context.getString(R.string.settings_renderer_from_plugins, appLabel),
                        renderer = data
                    )
                    rendererPluginList.add(renderer)
                }
            }.onFailure {
                Logger.error(TAG, "Failed to parse config for $packageName, removing.", it)
                mmkv.remove(packageName)
                removedCount++
            }
        }

        Logger.debug(TAG, "Loaded ${keys.size - removedCount} plugin(s), removed $removedCount stale config(s).")
    }

    /**
     * 反序列化插件配置 JSON 并导入
     */
    fun deserialize(context: Context, senderPackageName: String, configJson: String) {
        val pm = context.packageManager

        // 包名是否对应已安装的应用
        val packageInfo = runCatching {
            pm.getPackageInfo(senderPackageName, 0)
        }.getOrNull()
        if (packageInfo == null) {
            Logger.warning(TAG, "Verification failed: Package name $senderPackageName is not installed.")
            return
        }

        // 验证是否声明了 fclPlugin_V2
        val appInfo = runCatching {
            pm.getApplicationInfo(senderPackageName, PackageManager.GET_META_DATA)
        }.getOrNull()
        val metaData = appInfo?.metaData
        if (metaData == null || !metaData.getBoolean("fclPlugin_V2", false)) {
            Logger.warning(TAG, "Verification failed: $senderPackageName does not declare fclPlugin_V2")
            return
        }

        // 解析渲染器配置
        runCatching {
            GLOBAL_JSON.decodeFromString<RendererConfigList>(configJson)
        }.onFailure { e ->
            Logger.error(TAG, "JSON parsing failed.", e)
        }.getOrNull()?.let { config ->
            val plugin = RendererV2Plugin(
                packageName = senderPackageName,
                config = config
            )
            save(plugin)
        }
    }

    /**
     * 移除渲染器插件，并保存本地配置列表
     */
    fun removeRenderer(failedToLoadList: List<RendererV2Data>) {
        TODO("Not yet implemented")
    }

    private fun save(plugin: RendererV2Plugin) {
        runCatching {
            val json = GLOBAL_JSON.encodeToString(plugin.config)
            rendererPluginMMKV().encode(plugin.packageName, json)
            Logger.info(TAG, "Plugin configuration saved: ${plugin.packageName}")
        }.onFailure {
            Logger.error(TAG, "Failed to save plugin configuration: ${plugin.packageName}", it)
        }
    }
}
