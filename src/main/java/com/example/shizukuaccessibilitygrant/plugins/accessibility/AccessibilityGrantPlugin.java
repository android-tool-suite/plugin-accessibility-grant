package com.example.shizukuaccessibilitygrant.plugins.accessibility;

import com.example.shizukuaccessibilitygrant.plugin.api.HomeWidget;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginHost;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginPermissionCatalog;
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.shizukuaccessibilitygrant.ui.UiKit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AccessibilityGrantPlugin implements ToolPlugin {
    private static final String PREFS_NAME = "accessibility_grant";
    private static final String PREF_FAVORITES = "favorites";
    private static final String PREF_AUTO_GRANT = "auto_grant_favorites";
    private static final String SETTINGS_COMMAND = "/system/bin/settings";
    private static final String ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services";
    private static final String ACCESSIBILITY_ENABLED = "accessibility_enabled";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ImportedPluginDescriptor descriptor;

    private Activity activity;
    private PluginHost host;
    private SharedPreferences preferences;
    private Set<String> favoriteComponents = new LinkedHashSet<>();
    private List<AccessibilityServiceEntry> allServices = new ArrayList<>();
    private LinearLayout serviceList;
    private Button permissionButton;
    private Button refreshButton;
    private EditText searchBox;
    private CheckBox favoritesOnlyCheckBox;
    private CheckBox autoGrantCheckBox;
    private ProgressBar progressBar;
    private boolean autoGrantAttempted;
    private View rootView;

    public AccessibilityGrantPlugin() {
        this(AccessibilityGrantPluginDescriptor.create());
    }

    public AccessibilityGrantPlugin(ImportedPluginDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public String id() {
        return descriptor.id;
    }

    @Override
    public String title() {
        return descriptor.title;
    }

    @Override
    public String description() {
        return descriptor.description;
    }

    @Override
    public boolean removable() {
        return true;
    }

    @Override
    public Set<String> requestedPermissions() {
        return descriptor.requestedPermissions;
    }

    @Override
    public List<HomeWidget> createHomeWidgets(Activity activity, PluginHost host) {
        return Collections.singletonList(new HomeWidget() {
            @Override
            public String id() {
                return "favorite_services";
            }

            @Override
            public String title() {
                return "无障碍收藏";
            }

            @Override
            public String pluginId() {
                return AccessibilityGrantPlugin.this.id();
            }

            @Override
            public View createView(Activity activity, PluginHost host) {
                SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
                Set<String> favorites = prefs.getStringSet(PREF_FAVORITES, Collections.emptySet());
                boolean autoGrant = prefs.getBoolean(PREF_AUTO_GRANT, false);
                LinearLayout card = UiKit.card(activity);

                TextView title = new TextView(activity);
                title.setText("无障碍授权");
                UiKit.styleCaption(title);
                card.addView(title, new LinearLayout.LayoutParams(-1, -2));

                TextView value = new TextView(activity);
                value.setText(favorites.size() + " 个收藏服务");
                UiKit.styleTitle(value, 20);
                LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-1, -2);
                valueParams.topMargin = UiKit.dp(activity, 4);
                card.addView(value, valueParams);

                TextView subtitle = new TextView(activity);
                subtitle.setText(autoGrant ? "启动时会自动启用收藏服务" : "自动启用未开启");
                UiKit.styleBody(subtitle);
                LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
                subtitleParams.topMargin = UiKit.dp(activity, 4);
                card.addView(subtitle, subtitleParams);
                return card;
            }
        });
    }

    @Override
    public View createView(Activity activity, PluginHost host) {
        this.activity = activity;
        this.host = host;
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        this.favoriteComponents = new LinkedHashSet<>(preferences.getStringSet(PREF_FAVORITES, Collections.emptySet()));
        if (rootView == null) {
            rootView = createContentView();
        }
        return rootView;
    }

    @Override
    public void onSelected() {
        refreshState();
    }

    @Override
    public void onHostStateChanged() {
        refreshState();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
    }

    private View createContentView() {
        int gap = dp(12);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("Shizuku 无障碍授权");
        UiKit.styleTitle(title, 22);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView warning = new TextView(activity);
        warning.setText("只给你完全信任的应用开启无障碍权限。该权限可读取屏幕内容并执行点击、滑动等操作。");
        warning.setTextSize(14);
        warning.setTextColor(UiKit.COLOR_WARN);
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        warning.setBackground(UiKit.rounded(0xFFFFF7ED, 8, activity));
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(-1, -2);
        warningParams.topMargin = dp(10);
        root.addView(warning, warningParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = gap;

        permissionButton = new Button(activity);
        permissionButton.setText("授权 Shizuku");
        UiKit.stylePrimaryButton(permissionButton);
        permissionButton.setOnClickListener(v -> host.requestShizukuPermission());
        actions.addView(permissionButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        refreshButton = new Button(activity);
        refreshButton.setText("刷新");
        UiKit.styleSecondaryButton(refreshButton);
        refreshButton.setOnClickListener(v -> refreshState());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        refreshParams.leftMargin = gap;
        actions.addView(refreshButton, refreshParams);
        root.addView(actions, actionsParams);

        searchBox = new EditText(activity);
        searchBox.setSingleLine(true);
        searchBox.setTextSize(15);
        searchBox.setHint("搜索应用、服务或包名");
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackground(UiKit.roundedStroke(UiKit.COLOR_SURFACE, UiKit.COLOR_BORDER, 8, activity));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                showServices(allServices);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.topMargin = gap;
        root.addView(searchBox, searchParams);

        LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(4), 0, 0);

        favoritesOnlyCheckBox = new CheckBox(activity);
        favoritesOnlyCheckBox.setText("仅显示收藏");
        favoritesOnlyCheckBox.setTextSize(14);
        favoritesOnlyCheckBox.setTextColor(UiKit.COLOR_TEXT);
        favoritesOnlyCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> showServices(allServices));
        options.addView(favoritesOnlyCheckBox, new LinearLayout.LayoutParams(-1, -2));

        autoGrantCheckBox = new CheckBox(activity);
        autoGrantCheckBox.setText("启动时自动启用收藏服务");
        autoGrantCheckBox.setTextSize(14);
        autoGrantCheckBox.setTextColor(UiKit.COLOR_TEXT);
        autoGrantCheckBox.setChecked(preferences.getBoolean(PREF_AUTO_GRANT, false));
        autoGrantCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.edit().putBoolean(PREF_AUTO_GRANT, isChecked).apply();
            if (isChecked) {
                autoGrantAttempted = false;
                refreshState();
            }
        });
        options.addView(autoGrantCheckBox, new LinearLayout.LayoutParams(-1, -2));
        root.addView(options, new LinearLayout.LayoutParams(-1, -2));

        progressBar = new ProgressBar(activity);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = gap;
        root.addView(progressBar, progressParams);

        ScrollView scrollView = new ScrollView(activity);
        serviceList = new LinearLayout(activity);
        serviceList.setOrientation(LinearLayout.VERTICAL);
        serviceList.setPadding(0, gap, 0, 0);
        scrollView.addView(serviceList, new ScrollView.LayoutParams(-1, -2));
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(4);
        root.addView(scrollView, scrollParams);

        return root;
    }

    private void refreshState() {
        if (serviceList == null || host == null) {
            return;
        }
        if (!hasRequiredPluginPermissions()) {
            UiKit.setEnabledVisual(permissionButton, false);
            UiKit.setEnabledVisual(refreshButton, false);
            serviceList.removeAllViews();
            addPermissionMessage("插件权限未授予。请到“插件管理”授予 Shizuku、执行 Shell、修改无障碍设置和读取应用列表权限。");
            return;
        }

        boolean binderAlive = host.isShizukuReady();
        boolean granted = binderAlive && host.hasShizukuPermission();

        UiKit.setEnabledVisual(permissionButton, binderAlive && !granted);
        UiKit.setEnabledVisual(refreshButton, granted);

        if (!binderAlive) {
            serviceList.removeAllViews();
            addMessage("Shizuku 未连接。请先安装并启动 Shizuku。");
            return;
        }

        if (!granted) {
            serviceList.removeAllViews();
            addMessage("Shizuku 已连接，尚未授权本工具合集。");
            return;
        }

        if (!host.isShellServiceConnected()) {
            serviceList.removeAllViews();
            addMessage("正在连接 Shizuku UserService...");
            host.ensureShellService();
            return;
        }

        loadAccessibilityServices();
    }

    private boolean hasRequiredPluginPermissions() {
        return host.hasImportedPluginPermission(id(), PluginPermissionCatalog.SHIZUKU)
                && host.hasImportedPluginPermission(id(), PluginPermissionCatalog.SHELL_EXEC)
                && host.hasImportedPluginPermission(id(), PluginPermissionCatalog.ACCESSIBILITY_SETTINGS)
                && host.hasImportedPluginPermission(id(), PluginPermissionCatalog.PACKAGE_QUERY);
    }

    private void addPermissionMessage(String message) {
        TextView textView = new TextView(activity);
        textView.setText(message + "\n\n当前插件作为外部插件运行，宿主负责获取 Shizuku 权限，插件只有获得授权后才能调用宿主的 Shizuku 能力。");
        textView.setTextColor(UiKit.COLOR_WARN);
        textView.setTextSize(14);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(dp(14), dp(14), dp(14), dp(14));
        textView.setBackground(UiKit.rounded(0xFFFFF7ED, 8, activity));
        serviceList.addView(textView, new LinearLayout.LayoutParams(-1, -2));
    }

    private void loadAccessibilityServices() {
        setLoading(true);
        Set<String> favoriteSnapshot = getFavoriteComponentsSnapshot();
        boolean shouldAutoGrant = preferences.getBoolean(PREF_AUTO_GRANT, false) && !autoGrantAttempted;
        if (shouldAutoGrant) {
            autoGrantAttempted = true;
        }
        executor.execute(() -> {
            try {
                Set<String> enabled = readEnabledServices();
                List<AccessibilityServiceEntry> services = queryAccessibilityServices(enabled, favoriteSnapshot);
                int autoGranted = 0;
                if (shouldAutoGrant) {
                    autoGranted = autoGrantFavorites(enabled, services, favoriteSnapshot);
                    if (autoGranted > 0) {
                        enabled = readEnabledServices();
                        services = queryAccessibilityServices(enabled, favoriteSnapshot);
                    }
                }
                int finalAutoGranted = autoGranted;
                List<AccessibilityServiceEntry> finalServices = services;
                activity.runOnUiThread(() -> {
                    allServices = finalServices;
                    showServices(allServices);
                    if (finalAutoGranted > 0) {
                        host.showToast("已自动启用 " + finalAutoGranted + " 个收藏服务");
                    }
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    serviceList.removeAllViews();
                    addMessage("读取无障碍服务失败：" + e.getMessage());
                });
            } finally {
                activity.runOnUiThread(() -> setLoading(false));
            }
        });
    }

    private List<AccessibilityServiceEntry> queryAccessibilityServices(Set<String> enabled, Set<String> favorites) {
        PackageManager pm = activity.getPackageManager();
        Map<String, AccessibilityServiceEntry> entries = new LinkedHashMap<>();

        android.view.accessibility.AccessibilityManager accessibilityManager =
                (android.view.accessibility.AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (accessibilityManager != null) {
            List<AccessibilityServiceInfo> installedServices = accessibilityManager.getInstalledAccessibilityServiceList();
            for (AccessibilityServiceInfo info : installedServices) {
                ResolveInfo resolveInfo = info.getResolveInfo();
                if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                    addServiceEntry(pm, entries, resolveInfo, enabled, favorites);
                }
            }
        }

        Intent intent = new Intent(AccessibilityService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfos;
        int flags = PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveInfos = pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags));
        } else {
            resolveInfos = pm.queryIntentServices(intent, flags);
        }

        for (ResolveInfo resolveInfo : resolveInfos) {
            addServiceEntry(pm, entries, resolveInfo, enabled, favorites);
        }

        List<AccessibilityServiceEntry> sorted = new ArrayList<>(entries.values());
        Collections.sort(sorted, (a, b) -> {
            int app = a.appLabel.compareToIgnoreCase(b.appLabel);
            return app != 0 ? app : a.serviceLabel.compareToIgnoreCase(b.serviceLabel);
        });
        return sorted;
    }

    private void addServiceEntry(
            PackageManager pm,
            Map<String, AccessibilityServiceEntry> entries,
            ResolveInfo resolveInfo,
            Set<String> enabled,
            Set<String> favorites
    ) {
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        if (serviceInfo == null || !Manifest.permission.BIND_ACCESSIBILITY_SERVICE.equals(serviceInfo.permission)) {
            return;
        }

        ComponentName componentName = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        String component = componentName.flattenToString();
        CharSequence serviceLabel = resolveInfo.loadLabel(pm);
        CharSequence appLabel = serviceInfo.applicationInfo.loadLabel(pm);
        entries.put(component, new AccessibilityServiceEntry(
                appLabel == null ? serviceInfo.packageName : appLabel.toString(),
                serviceLabel == null ? serviceInfo.name : serviceLabel.toString(),
                component,
                enabled.contains(component),
                favorites.contains(component)
        ));
    }

    private void showServices(List<AccessibilityServiceEntry> services) {
        serviceList.removeAllViews();
        List<AccessibilityServiceEntry> visibleServices = filterServices(services);
        if (visibleServices.isEmpty()) {
            addMessage(services.isEmpty() ? "没有找到已安装的无障碍服务。" : "没有匹配的服务。");
            return;
        }

        for (AccessibilityServiceEntry entry : visibleServices) {
            serviceList.addView(createServiceRow(entry), new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private List<AccessibilityServiceEntry> filterServices(List<AccessibilityServiceEntry> services) {
        String query = searchBox == null ? "" : searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean favoritesOnly = favoritesOnlyCheckBox != null && favoritesOnlyCheckBox.isChecked();
        List<AccessibilityServiceEntry> filtered = new ArrayList<>();
        for (AccessibilityServiceEntry entry : services) {
            if (favoritesOnly && !entry.favorite) {
                continue;
            }
            if (!query.isEmpty() && !entry.matches(query)) {
                continue;
            }
            filtered.add(entry);
        }
        return filtered;
    }

    private View createServiceRow(AccessibilityServiceEntry entry) {
        LinearLayout row = UiKit.card(activity);

        TextView appName = new TextView(activity);
        appName.setText(entry.appLabel);
        UiKit.styleTitle(appName, 16);
        row.addView(appName, new LinearLayout.LayoutParams(-1, -2));

        TextView serviceName = new TextView(activity);
        serviceName.setText(entry.serviceLabel);
        serviceName.setTextColor(UiKit.COLOR_TEXT);
        serviceName.setTextSize(14);
        row.addView(serviceName, new LinearLayout.LayoutParams(-1, -2));

        TextView component = new TextView(activity);
        component.setText(entry.component);
        component.setTextColor(UiKit.COLOR_MUTED);
        component.setTextSize(12);
        component.setSingleLine(false);
        row.addView(component, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(activity);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(-1, -2);
        bottomParams.topMargin = dp(10);

        TextView state = new TextView(activity);
        state.setText(entry.enabled ? "已启用" : "未启用");
        state.setTextColor(entry.enabled ? UiKit.COLOR_PRIMARY : UiKit.COLOR_WARN);
        state.setTextSize(14);
        bottom.addView(state, new LinearLayout.LayoutParams(0, -2, 1));

        Button favorite = new Button(activity);
        favorite.setText(entry.favorite ? "取消收藏" : "收藏");
        UiKit.styleSecondaryButton(favorite);
        favorite.setOnClickListener(v -> toggleFavorite(entry.component));
        bottom.addView(favorite, new LinearLayout.LayoutParams(dp(120), dp(44)));

        Button action = new Button(activity);
        action.setText(entry.enabled ? "停用" : "启用");
        if (entry.enabled) {
            UiKit.styleDangerButton(action);
        } else {
            UiKit.stylePrimaryButton(action);
        }
        action.setOnClickListener(v -> setServiceEnabled(entry.component, !entry.enabled));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(96), dp(44));
        actionParams.leftMargin = dp(8);
        bottom.addView(action, actionParams);
        row.addView(bottom, bottomParams);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setPadding(0, 0, 0, dp(10));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private void toggleFavorite(String component) {
        if (favoriteComponents.contains(component)) {
            favoriteComponents.remove(component);
            host.showToast("已取消收藏");
        } else {
            favoriteComponents.add(component);
            host.showToast("已收藏");
        }
        preferences.edit().putStringSet(PREF_FAVORITES, new LinkedHashSet<>(favoriteComponents)).apply();
        refreshFavoriteState();
        showServices(allServices);
    }

    private void refreshFavoriteState() {
        List<AccessibilityServiceEntry> updated = new ArrayList<>();
        for (AccessibilityServiceEntry entry : allServices) {
            updated.add(entry.withFavorite(favoriteComponents.contains(entry.component)));
        }
        allServices = updated;
    }

    private Set<String> getFavoriteComponentsSnapshot() {
        return new LinkedHashSet<>(favoriteComponents);
    }

    private void setServiceEnabled(String component, boolean enabled) {
        setLoading(true);
        executor.execute(() -> {
            try {
                Set<String> services = readEnabledServices();
                if (enabled) {
                    services.add(component);
                } else {
                    services.remove(component);
                }
                writeEnabledServices(services);
                activity.runOnUiThread(() -> {
                    host.showToast(enabled ? "已启用" : "已停用");
                    loadAccessibilityServices();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> host.showToast("操作失败：" + e.getMessage()));
            } finally {
                activity.runOnUiThread(() -> setLoading(false));
            }
        });
    }

    private int autoGrantFavorites(
            Set<String> enabled,
            List<AccessibilityServiceEntry> services,
            Set<String> favorites
    ) throws IOException {
        if (favorites.isEmpty()) {
            return 0;
        }

        Set<String> availableFavorites = new LinkedHashSet<>();
        for (AccessibilityServiceEntry entry : services) {
            if (favorites.contains(entry.component)) {
                availableFavorites.add(entry.component);
            }
        }

        int changed = 0;
        for (String component : availableFavorites) {
            if (enabled.add(component)) {
                changed++;
            }
        }
        if (changed > 0) {
            writeEnabledServices(enabled);
        }
        return changed;
    }

    private Set<String> readEnabledServices() throws IOException {
        String output = host.runShellCommand(SETTINGS_COMMAND, "get", "secure", ENABLED_ACCESSIBILITY_SERVICES).trim();
        Set<String> result = new LinkedHashSet<>();
        if (output.isEmpty() || "null".equalsIgnoreCase(output)) {
            return result;
        }
        for (String item : output.split(":")) {
            if (!TextUtils.isEmpty(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private void writeEnabledServices(Set<String> services) throws IOException {
        String joined = TextUtils.join(":", services);
        host.runShellCommand(SETTINGS_COMMAND, "put", "secure", ENABLED_ACCESSIBILITY_SERVICES, joined);
        host.runShellCommand(SETTINGS_COMMAND, "put", "secure", ACCESSIBILITY_ENABLED, services.isEmpty() ? "0" : "1");
    }

    private void addMessage(String message) {
        TextView textView = new TextView(activity);
        textView.setText(message);
        textView.setTextColor(UiKit.COLOR_MUTED);
        textView.setTextSize(14);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(0, dp(32), 0, 0);
        serviceList.addView(textView, new LinearLayout.LayoutParams(-1, -2));
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        UiKit.setEnabledVisual(refreshButton, !loading && host.hasShizukuPermission());
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class AccessibilityServiceEntry {
        final String appLabel;
        final String serviceLabel;
        final String component;
        final boolean enabled;
        final boolean favorite;

        AccessibilityServiceEntry(String appLabel, String serviceLabel, String component, boolean enabled, boolean favorite) {
            this.appLabel = appLabel;
            this.serviceLabel = serviceLabel;
            this.component = component;
            this.enabled = enabled;
            this.favorite = favorite;
        }

        AccessibilityServiceEntry withFavorite(boolean favorite) {
            return new AccessibilityServiceEntry(appLabel, serviceLabel, component, enabled, favorite);
        }

        boolean matches(String query) {
            return appLabel.toLowerCase(Locale.ROOT).contains(query)
                    || serviceLabel.toLowerCase(Locale.ROOT).contains(query)
                    || component.toLowerCase(Locale.ROOT).contains(query);
        }
    }
}
