#!/usr/bin/env bash
# Builds the Gradle project structure around the flat .kt files (runs on GitHub Actions).
set -euo pipefail

P=app/src/main/java/app/iosvolume/module
S=xposedapi/src/main/java/de/robv/android/xposed
mkdir -p "$P" app/src/main/assets app/src/main/res/values "$S/callbacks"
cp MainHook.kt PillView.kt VolumeInjector.kt SettingsActivity.kt "$P/"

cat > settings.gradle.kts <<'X'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "iOSVolume"
include(":app", ":xposedapi")
X

cat > build.gradle.kts <<'X'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
X

printf 'org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\n' > gradle.properties

cat > app/build.gradle.kts <<'X'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.iosvolume.module"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.iosvolume.module"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { compileOnly(project(":xposedapi")) }
X

cat > app/src/main/AndroidManifest.xml <<'X'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="iOS Volume" android:allowBackup="false"
        android:theme="@android:style/Theme.DeviceDefault.DayNight">
        <activity android:name=".SettingsActivity" android:exported="true" android:label="iOS Volume">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <meta-data android:name="xposedmodule" android:value="true" />
        <meta-data android:name="xposeddescription" android:value="iOS-style volume HUD" />
        <meta-data android:name="xposedminversion" android:value="93" />
        <meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />
    </application>
</manifest>
X

cat > app/src/main/res/values/arrays.xml <<'X'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="xposed_scope">
        <item>com.android.systemui</item>
    </string-array>
</resources>
X

echo "app.iosvolume.module.MainHook" > app/src/main/assets/xposed_init

# ---- compile-only stubs of the Xposed API (real classes are provided by LSPosed at runtime) ----
cat > xposedapi/build.gradle.kts <<'X'
plugins { id("java-library") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
X

cat > "$S/IXposedHookLoadPackage.java" <<'X'
package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
X

cat > "$S/callbacks/XC_LoadPackage.java" <<'X'
package de.robv.android.xposed.callbacks;
public abstract class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}
X

cat > "$S/XC_MethodHook.java" <<'X'
package de.robv.android.xposed;
public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public class Unhook {
        public void unhook() {}
    }
    public static class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable getThrowable() { return null; }
        public void setThrowable(Throwable t) {}
    }
}
X

cat > "$S/XposedBridge.java" <<'X'
package de.robv.android.xposed;
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
}
X

cat > "$S/XposedHelpers.java" <<'X'
package de.robv.android.xposed;
public final class XposedHelpers {
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { return null; }
}
X

echo "project generated"
