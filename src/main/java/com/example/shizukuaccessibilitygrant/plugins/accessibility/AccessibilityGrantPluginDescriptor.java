package com.example.shizukuaccessibilitygrant.plugins.accessibility;

import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import java.util.Collections;
import java.util.LinkedHashSet;

public final class AccessibilityGrantPluginDescriptor {
    public static final String ID = "accessibility_grant";

    private AccessibilityGrantPluginDescriptor() {
    }

    public static ImportedPluginDescriptor create() {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        dependencies.add("shizuku_auth");
        return new ImportedPluginDescriptor(
                ID,
                "无障碍授权",
                "通过 Shizuku 启用或停用已安装应用的无障碍服务。",
                "1.0",
                "Android Tool Suite",
                "1",
                "com.example.shizukuaccessibilitygrant.plugins.accessibility.AccessibilityGrantPlugin",
                "",
                dependencies,
                Collections.emptyList()
        );
    }
}
