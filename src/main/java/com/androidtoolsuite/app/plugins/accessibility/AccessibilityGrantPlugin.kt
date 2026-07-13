package com.androidtoolsuite.app.plugins.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.androidtoolsuite.app.plugin.api.HomeWidget
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize
import com.androidtoolsuite.app.plugin.api.PluginHost
import com.androidtoolsuite.app.plugin.api.ToolPlugin
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor
import com.androidtoolsuite.app.ui.composePluginView
import java.io.IOException
import java.util.concurrent.Executors

class AccessibilityGrantPlugin(
    private val descriptor: ImportedPluginDescriptor = AccessibilityGrantPluginDescriptor.create(),
) : ToolPlugin {
    internal val state: MutableState<AccessibilityGrantUiState> = mutableStateOf(AccessibilityGrantUiState())

    private val executor = Executors.newSingleThreadExecutor()
    private var activity: Activity? = null
    private var host: PluginHost? = null
    private var preferences: SharedPreferences? = null
    private var rootView: View? = null
    private var favoriteComponents = linkedSetOf<String>()
    private var autoGrantAttempted = false

    override fun id(): String = descriptor.id
    override fun title(): String = descriptor.title
    override fun description(): String = descriptor.description
    override fun version(): String = descriptor.version
    override fun removable(): Boolean = true
    override fun dependencies(): Set<String> = descriptor.dependencies

    override fun createHomeWidgets(activity: Activity, host: PluginHost): List<HomeWidget> = listOf(
        object : HomeWidget {
            override fun id(): String = "favorite_services"
            override fun title(): String = "无障碍收藏"
            override fun pluginId(): String = this@AccessibilityGrantPlugin.id()
            override fun supportedSizes(): List<HomeWidgetSize> = listOf(HomeWidgetSize(2, 2), HomeWidgetSize(4, 2))
            override fun createView(activity: Activity, host: PluginHost): View {
                val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
                val favorites = prefs.getStringSet(PREF_FAVORITES, emptySet()).orEmpty().size
                val autoGrant = prefs.getBoolean(PREF_AUTO_GRANT, false)
                return composePluginView(activity) { AccessibilityGrantHomeWidget(favorites, autoGrant) }
            }
        },
    )

    override fun createView(activity: Activity, host: PluginHost): View {
        if (this.activity !== activity || rootView == null) {
            this.activity = activity
            this.host = host
            preferences = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
            favoriteComponents = LinkedHashSet(preferences?.getStringSet(PREF_FAVORITES, emptySet()).orEmpty())
            state.value = state.value.copy(autoGrant = preferences?.getBoolean(PREF_AUTO_GRANT, false) == true)
            rootView = composePluginView(activity) { AccessibilityGrantScreen(this) }
        }
        return rootView!!
    }

    override fun onSelected() = refreshState()
    override fun onHostStateChanged() = refreshState()

    internal fun refreshState() {
        val currentHost = host ?: return
        val connection = when {
            !currentHost.isShizukuReady -> AccessibilityConnection.DISCONNECTED
            !currentHost.hasShizukuPermission() -> AccessibilityConnection.UNAUTHORIZED
            !currentHost.isShellServiceConnected -> AccessibilityConnection.CONNECTING
            else -> AccessibilityConnection.READY
        }
        updateState { copy(connection = connection) }
        when (connection) {
            AccessibilityConnection.DISCONNECTED -> showMessage("请先启动 Shizuku，再返回此页刷新连接状态。")
            AccessibilityConnection.UNAUTHORIZED -> showMessage("请先在“Shizuku 授权”工具中批准宿主权限。")
            AccessibilityConnection.CONNECTING -> {
                showMessage("正在连接 Shizuku UserService…")
                currentHost.ensureShellService()
            }
            AccessibilityConnection.READY -> loadAccessibilityServices()
        }
    }

    internal fun setQuery(query: String) = updateState { copy(query = query) }

    internal fun setFavoritesOnly(enabled: Boolean) = updateState { copy(favoritesOnly = enabled) }

    internal fun setAutoGrant(enabled: Boolean) {
        preferences?.edit()?.putBoolean(PREF_AUTO_GRANT, enabled)?.apply()
        updateState { copy(autoGrant = enabled) }
        if (enabled) {
            autoGrantAttempted = false
            refreshState()
        }
    }

    internal fun toggleFavorite(component: String) {
        val added = if (favoriteComponents.remove(component)) false else favoriteComponents.add(component)
        preferences?.edit()?.putStringSet(PREF_FAVORITES, LinkedHashSet(favoriteComponents))?.apply()
        updateState {
            copy(services = services.map { if (it.component == component) it.copy(favorite = added) else it })
        }
        host?.showToast(if (added) "已收藏" else "已取消收藏")
    }

    internal fun setServiceEnabled(component: String, enabled: Boolean) {
        updateState { copy(loading = true) }
        executor.execute {
            runCatching {
                val services = readEnabledServices()
                if (enabled) services.add(component) else services.remove(component)
                writeEnabledServices(services)
            }.onSuccess {
                postState { copy(loading = false) }
                host?.showToast(if (enabled) "已启用" else "已停用")
                loadAccessibilityServices()
            }.onFailure { error ->
                postState { copy(loading = false, message = "操作失败：${safeMessage(error)}") }
            }
        }
    }

    private fun showMessage(message: String) = updateState {
        copy(loading = false, services = emptyList(), message = message)
    }

    private fun loadAccessibilityServices() {
        val prefs = preferences ?: return
        val favorites = LinkedHashSet(favoriteComponents)
        val shouldAutoGrant = prefs.getBoolean(PREF_AUTO_GRANT, false) && !autoGrantAttempted
        if (shouldAutoGrant) autoGrantAttempted = true
        updateState { copy(loading = true, message = null) }
        executor.execute {
            runCatching {
                var enabled = readEnabledServices()
                var services = queryAccessibilityServices(enabled, favorites)
                val autoGranted = if (shouldAutoGrant) autoGrantFavorites(enabled, services, favorites) else 0
                if (autoGranted > 0) {
                    enabled = readEnabledServices()
                    services = queryAccessibilityServices(enabled, favorites)
                }
                services to autoGranted
            }.onSuccess { (services, autoGranted) ->
                postState { copy(loading = false, services = services, message = null) }
                if (autoGranted > 0) host?.showToast("已自动启用 $autoGranted 个收藏服务")
            }.onFailure { error ->
                postState { copy(loading = false, services = emptyList(), message = "读取无障碍服务失败：${safeMessage(error)}") }
            }
        }
    }

    private fun queryAccessibilityServices(enabled: Set<String>, favorites: Set<String>): List<AccessibilityServiceItem> {
        val currentActivity = activity ?: return emptyList()
        val packageManager = currentActivity.packageManager
        val entries = linkedMapOf<String, AccessibilityServiceItem>()
        val accessibilityManager = currentActivity.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        accessibilityManager?.installedAccessibilityServiceList.orEmpty().forEach { info ->
            info.resolveInfo?.let { addServiceEntry(packageManager, entries, it, enabled, favorites) }
        }
        val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        val intent = Intent(AccessibilityService.SERVICE_INTERFACE)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") packageManager.queryIntentServices(intent, flags)
        }
        resolveInfos.forEach { addServiceEntry(packageManager, entries, it, enabled, favorites) }
        return entries.values.sortedWith { first, second ->
            val appResult = first.appLabel.compareTo(second.appLabel, ignoreCase = true)
            if (appResult != 0) appResult else first.serviceLabel.compareTo(second.serviceLabel, ignoreCase = true)
        }
    }

    private fun addServiceEntry(
        packageManager: PackageManager,
        entries: MutableMap<String, AccessibilityServiceItem>,
        resolveInfo: ResolveInfo,
        enabled: Set<String>,
        favorites: Set<String>,
    ) {
        val serviceInfo = resolveInfo.serviceInfo ?: return
        if (serviceInfo.permission != Manifest.permission.BIND_ACCESSIBILITY_SERVICE) return
        val component = ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToString()
        entries[component] = AccessibilityServiceItem(
            appLabel = serviceInfo.applicationInfo.loadLabel(packageManager)?.toString() ?: serviceInfo.packageName,
            serviceLabel = resolveInfo.loadLabel(packageManager)?.toString() ?: serviceInfo.name,
            component = component,
            enabled = component in enabled,
            favorite = component in favorites,
        )
    }

    private fun autoGrantFavorites(
        enabled: MutableSet<String>,
        services: List<AccessibilityServiceItem>,
        favorites: Set<String>,
    ): Int {
        var changed = 0
        services.filter { it.component in favorites }.forEach { if (enabled.add(it.component)) changed++ }
        if (changed > 0) writeEnabledServices(enabled)
        return changed
    }

    private fun readEnabledServices(): MutableSet<String> {
        val output = requireHost().runShellCommand(SETTINGS_COMMAND, "get", "secure", ENABLED_ACCESSIBILITY_SERVICES).trim()
        if (output.isBlank() || output.equals("null", true)) return linkedSetOf()
        return output.split(':').filter { it.isNotBlank() }.toCollection(linkedSetOf())
    }

    private fun writeEnabledServices(services: Set<String>) {
        requireHost().runShellCommand(SETTINGS_COMMAND, "put", "secure", ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", services))
        requireHost().runShellCommand(SETTINGS_COMMAND, "put", "secure", ACCESSIBILITY_ENABLED, if (services.isEmpty()) "0" else "1")
    }

    private fun requireHost(): PluginHost = host ?: throw IOException("插件宿主尚未初始化")
    private fun safeMessage(error: Throwable): String = error.message?.take(240) ?: error.javaClass.simpleName
    private fun postState(transform: AccessibilityGrantUiState.() -> AccessibilityGrantUiState) {
        activity?.runOnUiThread { updateState(transform) }
    }
    private fun updateState(transform: AccessibilityGrantUiState.() -> AccessibilityGrantUiState) {
        state.value = state.value.transform()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        rootView = null
        activity = null
        host = null
    }

    companion object {
        private const val PREFS_NAME = "accessibility_grant"
        private const val PREF_FAVORITES = "favorites"
        private const val PREF_AUTO_GRANT = "auto_grant_favorites"
        private const val SETTINGS_COMMAND = "/system/bin/settings"
        private const val ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"
        private const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
    }
}

internal enum class AccessibilityConnection { DISCONNECTED, UNAUTHORIZED, CONNECTING, READY }

internal data class AccessibilityServiceItem(
    val appLabel: String,
    val serviceLabel: String,
    val component: String,
    val enabled: Boolean,
    val favorite: Boolean,
) {
    fun matches(query: String): Boolean = appLabel.contains(query, true) || serviceLabel.contains(query, true) || component.contains(query, true)
}

internal data class AccessibilityGrantUiState(
    val loading: Boolean = false,
    val connection: AccessibilityConnection = AccessibilityConnection.DISCONNECTED,
    val services: List<AccessibilityServiceItem> = emptyList(),
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val autoGrant: Boolean = false,
    val message: String? = null,
)
