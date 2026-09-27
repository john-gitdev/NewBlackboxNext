package dev.codex.envprobe;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.os.Parcel;
import android.os.UserManager;
import android.system.Os;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class ProbeActivity extends Activity {
    private static final String TAG = "ENVPROBE";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray lifecycle = new JSONArray();
    private final JSONObject binding = new JSONObject();
    private final JSONObject peerBinding = new JSONObject();
    private TextView text;
    private boolean bound;
    private boolean peerBound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            put(binding, "connectedAtElapsedMs", android.os.SystemClock.elapsedRealtime());
            put(binding, "component", name.flattenToString());
            put(binding, "binderClass", binder.getClass().getName());
            field(binding, "descriptor", () -> binder.getInterfaceDescriptor());
            field(binding, "callerIdentity", () -> binderIdentity(binder));
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            put(binding, "disconnectedAtElapsedMs", android.os.SystemClock.elapsedRealtime());
            put(binding, "disconnectedComponent", name.flattenToString());
        }
    };
    private final ServiceConnection peerConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            put(peerBinding, "component", name.flattenToString());
            field(peerBinding, "descriptor", binder::getInterfaceDescriptor);
            field(peerBinding, "callerIdentity", () -> binderIdentity(binder));
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            put(peerBinding, "disconnectedComponent", name.flattenToString());
        }
    };

    private interface Read { Object get() throws Exception; }
    private static void put(JSONObject j, String key, Object value) {
        try { j.put(key, value == null ? JSONObject.NULL : value); } catch (Exception ignored) { }
    }
    private static void field(JSONObject j, String key, Read r) {
        try { put(j, key, r.get()); }
        catch (Throwable e) { put(j, key, "ERROR:" + e.getClass().getSimpleName() + ":" + e.getMessage()); }
    }
    private static String path(File f) { return f == null ? null : f.getAbsolutePath(); }
    private static JSONArray strings(String[] a) {
        JSONArray out = new JSONArray();
        if (a != null) for (String s : a) out.put(s);
        return out;
    }
    private static String component(ComponentName c) { return c == null ? null : c.flattenToString(); }
    private void event(String name) { lifecycle.put(name + "@" + android.os.SystemClock.elapsedRealtime()); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        event("onCreate");
        text = new TextView(this);
        text.setTextIsSelectable(true);
        text.setPadding(18, 18, 18, 18);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
        text.setText("Collecting Android environment…\nResults also appear in logcat under ENVPROBE.");
        Intent service = new Intent("dev.codex.envprobe.PING").setPackage(getPackageName());
        field(binding, "resolveService", () -> serviceInfo(getPackageManager().resolveService(service, 0)));
        field(binding, "queryIntentServices", () -> services(getPackageManager().queryIntentServices(service, 0)));
        field(binding, "bindResult", () -> { bound = bindService(service, connection, Context.BIND_AUTO_CREATE); return bound; });
        Intent peer = new Intent("dev.codex.envpeer.PING").setPackage("dev.codex.envpeer");
        field(peerBinding, "resolveService", () -> serviceInfo(getPackageManager().resolveService(peer, 0)));
        field(peerBinding, "bindResult", () -> {
            peerBound = bindService(peer, peerConnection, Context.BIND_AUTO_CREATE);
            return peerBound;
        });
        handler.postDelayed(this::snapshot, 1200);
    }
    @Override protected void onStart() { super.onStart(); event("onStart"); }
    @Override protected void onResume() { super.onResume(); event("onResume"); }
    @Override protected void onPause() { event("onPause"); super.onPause(); }
    @Override protected void onDestroy() {
        if (bound) try { unbindService(connection); } catch (Exception ignored) { }
        if (peerBound) try { unbindService(peerConnection); } catch (Exception ignored) { }
        event("onDestroy");
        super.onDestroy();
    }

    private void snapshot() {
        JSONObject root = new JSONObject();
        put(root, "schema", 9);
        put(root, "timestampUtcMs", System.currentTimeMillis());
        put(root, "lifecycle", lifecycle);
        put(root, "identity", identity());
        put(root, "applicationInfo", applicationInfo());
        put(root, "directories", directories());
        put(root, "peerProvider", peerProvider());
        put(root, "nativeFilesystem", nativeFilesystem());
        put(root, "packageManager", packageManager());
        put(root, "permissionsAndAppOps", permissionsAndAppOps());
        put(root, "accounts", accounts());
        put(root, "binding", binding);
        put(root, "peerBinding", peerBinding);
        put(root, "provider", provider());
        put(root, "runtime", runtime());
        String id = Long.toHexString(System.currentTimeMillis()) + "-" + Process.myPid();
        String json = root.toString();
        int size = 1800;
        int count = (json.length() + size - 1) / size;
        Log.i(TAG, "BEGIN id=" + id + " chunks=" + count);
        for (int i = 0; i < count; i++) {
            Log.i(TAG, "CHUNK id=" + id + " index=" + i + " data=" + json.substring(i * size, Math.min(json.length(), (i + 1) * size)));
        }
        Log.i(TAG, "END id=" + id);
        text.setText(json.replace(",\"", ",\n\""));
    }

    private JSONObject identity() {
        JSONObject j = new JSONObject();
        put(j, "packageName", getPackageName());
        field(j, "opPackageName", this::getOpPackageName);
        put(j, "pid", Process.myPid());
        put(j, "androidUid", Process.myUid());
        field(j, "osGetuid", Os::getuid);
        field(j, "osGetgid", Os::getgid);
        field(j, "appProcessName", Application::getProcessName);
        field(j, "activityManagerProcess", () -> {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
                if (p.pid == Process.myPid()) return p.processName + " uid=" + p.uid + " packages=" + Arrays.toString(p.pkgList);
            return null;
        });
        field(j, "pmPackageUid", () -> getPackageManager().getPackageUid(getPackageName(), 0));
        field(j, "systemPackageUid", () -> getPackageManager().getPackageUid("android", 0));
        field(j, "missingPackageUid", () -> getPackageManager().getPackageUid(
                "dev.codex.envprobe.definitely.missing", 0));
        return j;
    }

    private JSONObject binderIdentity(IBinder binder) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                throw new IllegalStateException("transact returned false");
            reply.readException();
            JSONObject identity = new JSONObject();
            put(identity, "serviceCallingUid", reply.readInt());
            put(identity, "serviceCallingPid", reply.readInt());
            put(identity, "serviceProcessUid", reply.readInt());
            put(identity, "serviceProcessPid", reply.readInt());
            return identity;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private JSONObject applicationInfo() {
        JSONObject j = new JSONObject();
        field(j, "contextApplicationInfo", () -> appInfo(getApplicationInfo()));
        field(j, "pmApplicationInfo", () -> appInfo(getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA)));
        return j;
    }
    private static JSONObject appInfo(ApplicationInfo a) {
        JSONObject j = new JSONObject();
        if (a == null) return j;
        put(j, "packageName", a.packageName);
        put(j, "processName", a.processName);
        put(j, "uid", a.uid);
        put(j, "dataDir", a.dataDir);
        field(j, "credentialProtectedDataDir", () ->
                (String) ApplicationInfo.class.getField("credentialProtectedDataDir").get(a));
        put(j, "deviceProtectedDataDir", a.deviceProtectedDataDir);
        put(j, "sourceDir", a.sourceDir);
        put(j, "publicSourceDir", a.publicSourceDir);
        put(j, "nativeLibraryDir", a.nativeLibraryDir);
        put(j, "splitSourceDirs", strings(a.splitSourceDirs));
        put(j, "splitPublicSourceDirs", strings(a.splitPublicSourceDirs));
        put(j, "splitNames", strings(a.splitNames));
        put(j, "enabled", a.enabled);
        put(j, "flags", a.flags);
        put(j, "targetSdkVersion", a.targetSdkVersion);
        put(j, "minSdkVersion", a.minSdkVersion);
        put(j, "className", a.className);
        put(j, "taskAffinity", a.taskAffinity);
        if (a.metaData != null) {
            JSONArray keys = new JSONArray();
            for (String key : a.metaData.keySet()) keys.put(key);
            put(j, "metaDataKeys", keys);
        }
        return j;
    }

    private JSONObject directories() {
        JSONObject j = new JSONObject();
        field(j, "dataDir", () -> path(getDataDir()));
        field(j, "filesDir", () -> path(getFilesDir()));
        field(j, "cacheDir", () -> path(getCacheDir()));
        field(j, "codeCacheDir", () -> path(getCodeCacheDir()));
        field(j, "noBackupFilesDir", () -> path(getNoBackupFilesDir()));
        field(j, "externalFilesDir", () -> path(getExternalFilesDir(null)));
        field(j, "deviceProtectedContextDataDir", () -> path(createDeviceProtectedStorageContext().getDataDir()));
        field(j, "contextIsDeviceProtectedStorage", this::isDeviceProtectedStorage);
        field(j, "filesDirCanonical", () -> getFilesDir().getCanonicalPath());
        field(j, "dataDirExists", () -> new File(getApplicationInfo().dataDir).isDirectory());
        field(j, "dataDirCanRead", () -> new File(getApplicationInfo().dataDir).canRead());
        field(j, "dataDirCanWrite", () -> new File(getApplicationInfo().dataDir).canWrite());
        field(j, "filesDirStat", () -> {
            android.system.StructStat s = Os.stat(getFilesDir().getAbsolutePath());
            JSONObject o = new JSONObject();
            put(o, "device", s.st_dev);
            put(o, "inode", s.st_ino);
            put(o, "uid", s.st_uid);
            put(o, "gid", s.st_gid);
            return o;
        });
        field(j, "isolationMarker", () -> {
            File marker = new File(getFilesDir(), "envprobe-isolation-marker.txt");
            String prior = marker.exists() ? readSmall(marker.getAbsolutePath(), 300) : null;
            String written = "pid=" + Process.myPid() + ";uid=" + Process.myUid() + ";time=" + System.currentTimeMillis();
            try (FileOutputStream out = new FileOutputStream(marker)) {
                out.write(written.getBytes(StandardCharsets.UTF_8));
            }
            JSONObject o = new JSONObject();
            put(o, "path", marker.getAbsolutePath());
            put(o, "previous", prior);
            put(o, "written", written);
            put(o, "readBack", readSmall(marker.getAbsolutePath(), 300));
            File logicalUser = new File("/data/user/0/" + getPackageName() + "/files/envprobe-isolation-marker.txt");
            File logicalData = new File("/data/data/" + getPackageName() + "/files/envprobe-isolation-marker.txt");
            field(o, "logicalUserRead", () -> readSmall(logicalUser.getAbsolutePath(), 300));
            field(o, "logicalDataRead", () -> readSmall(logicalData.getAbsolutePath(), 300));
            field(o, "logicalUserCanonical", logicalUser::getCanonicalPath);
            field(o, "logicalDataCanonical", logicalData::getCanonicalPath);
            field(o, "kernelFdPath", () -> {
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(marker, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    return Os.readlink("/proc/self/fd/" + fd.getFd());
                }
            });
            return o;
        });
        field(j, "userUnlocked", () -> ((UserManager) getSystemService(USER_SERVICE)).isUserUnlocked());
        field(j, "deviceProtectedMarker", () -> {
            Context device = createDeviceProtectedStorageContext();
            File marker = new File(device.getFilesDir(), "envprobe-device-marker.txt");
            String prior = marker.exists() ? readSmall(marker.getAbsolutePath(), 300) : null;
            String written = "pid=" + Process.myPid() + ";time=" + System.currentTimeMillis();
            try (FileOutputStream out = new FileOutputStream(marker)) {
                out.write(written.getBytes(StandardCharsets.UTF_8));
            }
            JSONObject o = new JSONObject();
            put(o, "path", marker.getAbsolutePath());
            put(o, "prior", prior);
            put(o, "readBack", readSmall(marker.getAbsolutePath(), 300));
            put(o, "canonical", marker.getCanonicalPath());
            return o;
        });
        field(j, "directBootEvents", () -> {
            File events = new File(createDeviceProtectedStorageContext().getFilesDir(),
                    "envprobe-boot-events.txt");
            return events.exists() ? readSmall(events.getAbsolutePath(), 2000) : null;
        });
        return j;
    }

    private JSONObject nativeFilesystem() {
        JSONObject j = new JSONObject();
        field(j, "libraryName", () -> System.mapLibraryName("envprobe"));
        File marker = new File(getFilesDir(), "envprobe-isolation-marker.txt");
        String logical = "/data/user/0/" + getPackageName() + "/files/" + marker.getName();
        String logicalDe = "/data/user_de/0/" + getPackageName() +
                "/files/envprobe-device-marker.txt";
        field(j, "backingPath", () -> nativePathFacts(marker.getAbsolutePath()));
        field(j, "logicalPath", () -> nativePathFacts(logical));
        field(j, "logicalDataAlias", () -> nativePathFacts(
                "/data/data/" + getPackageName() + "/files/" + marker.getName()));
        field(j, "logicalDeviceProtectedPath", () -> nativePathFacts(logicalDe));
        field(j, "deviceProtectedPath", () -> nativePathFacts(new File(
                createDeviceProtectedStorageContext().getFilesDir(),
                "envprobe-device-marker.txt").getAbsolutePath()));
        field(j, "nativeWriteCe", () -> {
            String name = "envprobe-native-marker.txt";
            String path = "/data/user/0/" + getPackageName() + "/files/" + name;
            String value = "native;pid=" + Process.myPid() + ";time=" + System.currentTimeMillis();
            JSONObject o = new JSONObject();
            put(o, "status", NativePathProbe.writeLogical(path, value));
            put(o, "written", value);
            field(o, "javaRead", () -> readSmall(new File(getFilesDir(), name).getAbsolutePath(), 300));
            field(o, "nativeRead", () -> NativePathProbe.inspect(path)[0]);
            return o;
        });
        field(j, "nativeWriteDe", () -> {
            String name = "envprobe-native-device-marker.txt";
            String path = "/data/user_de/0/" + getPackageName() + "/files/" + name;
            String value = "native-de;pid=" + Process.myPid() + ";time=" + System.currentTimeMillis();
            JSONObject o = new JSONObject();
            put(o, "status", NativePathProbe.writeLogical(path, value));
            put(o, "written", value);
            field(o, "javaRead", () -> readSmall(new File(
                    createDeviceProtectedStorageContext().getFilesDir(), name).getAbsolutePath(), 300));
            field(o, "nativeRead", () -> NativePathProbe.inspect(path)[0]);
            return o;
        });
        field(j, "peerLogicalPath", () -> nativePathFacts(
                "/data/user/0/dev.codex.envpeer/files/envpeer-isolation-marker.txt"));
        field(j, "prefixNeighborPath", () -> nativePathFacts(
                "/data/user/0/" + getPackageName() + ".suffix/files/" + marker.getName()));
        field(j, "parentTraversalPath", () -> nativePathFacts(
                "/data/user/0/" + getPackageName() +
                "/../dev.codex.envpeer/files/envpeer-isolation-marker.txt"));
        field(j, "mutations", () -> {
            String[] values = NativePathProbe.exerciseMutations(
                    "/data/user/0/" + getPackageName() + "/files", logical);
            JSONObject o = new JSONObject();
            put(o, "status", values[0]);
            put(o, "symlinkRead", values[1]);
            put(o, "symlinkTarget", values[2]);
            return o;
        });
        return j;
    }

    private JSONObject nativePathFacts(String path) {
        String[] names = {"openRead", "openatRead", "stat", "lstat", "readlink",
                "realpath", "procSelfFdReadlink", "access", "faccessat", "fstatat",
                "procSelfFdReadlinkat", "open64Read", "openat64Read"};
        String[] values = NativePathProbe.inspect(path);
        JSONObject j = new JSONObject();
        put(j, "path", path);
        for (int i = 0; i < names.length; i++) put(j, names[i], values[i]);
        return j;
    }

    private JSONObject permissionsAndAppOps() {
        JSONObject j = new JSONObject();
        field(j, "cameraPermission", () -> checkSelfPermission(Manifest.permission.CAMERA));
        field(j, "readContactsPermission", () -> checkSelfPermission(Manifest.permission.READ_CONTACTS));
        field(j, "getAccountsPermission", () -> checkSelfPermission(Manifest.permission.GET_ACCOUNTS));
        if (Build.VERSION.SDK_INT >= 33)
            field(j, "notificationsPermission", () -> checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        field(j, "readContactsAppOp", () -> {
            AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_READ_CONTACTS, Process.myUid(), getPackageName());
        });
        return j;
    }

    private JSONObject packageManager() {
        JSONObject j = new JSONObject();
        PackageManager pm = getPackageManager();
        field(j, "self", () -> packageInfo(pm.getPackageInfo(getPackageName(), PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA)));
        field(j, "vending", () -> packageInfo(pm.getPackageInfo("com.android.vending", PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS)));
        field(j, "gms", () -> packageInfo(pm.getPackageInfo("com.google.android.gms", 0)));
        field(j, "peer", () -> packageInfo(pm.getPackageInfo("dev.codex.envpeer", PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS)));
        field(j, "peerUid", () -> pm.getPackageUid("dev.codex.envpeer", 0));
        field(j, "installedPackagesCount", () -> pm.getInstalledPackages(0).size());
        field(j, "installerPackageName", () -> pm.getInstallerPackageName(getPackageName()));
        if (Build.VERSION.SDK_INT >= 30)
            field(j, "installSourceInfo", () -> {
                android.content.pm.InstallSourceInfo s = pm.getInstallSourceInfo(getPackageName());
                JSONObject o = new JSONObject();
                put(o, "initiating", s.getInitiatingPackageName());
                put(o, "installing", s.getInstallingPackageName());
                put(o, "originating", s.getOriginatingPackageName());
                return o;
            });
        Intent pad = new Intent("com.google.android.play.core.assetmoduleservice.BIND_ASSET_MODULE_SERVICE").setPackage("com.android.vending");
        field(j, "padResolve", () -> serviceInfo(pm.resolveService(pad, 0)));
        field(j, "padQuery", () -> services(pm.queryIntentServices(pad, 0)));
        field(j, "ownProviderInfo", () -> providerInfo(pm.resolveContentProvider("dev.codex.envprobe.provider", 0)));
        return j;
    }
    private static JSONObject packageInfo(PackageInfo p) {
        JSONObject j = new JSONObject();
        put(j, "packageName", p.packageName);
        put(j, "versionName", p.versionName);
        put(j, "versionCode", p.getLongVersionCode());
        put(j, "applicationInfo", p.applicationInfo == null ? JSONObject.NULL : appInfo(p.applicationInfo));
        JSONArray ss = new JSONArray();
        if (p.services != null) for (ServiceInfo s : p.services) ss.put(serviceInfo(s));
        put(j, "services", ss);
        JSONArray ps = new JSONArray();
        if (p.providers != null) for (ProviderInfo x : p.providers) ps.put(providerInfo(x));
        put(j, "providers", ps);
        return j;
    }
    private static JSONObject serviceInfo(ResolveInfo r) { return r == null ? null : serviceInfo(r.serviceInfo); }
    private static JSONObject serviceInfo(ServiceInfo s) {
        if (s == null) return null;
        JSONObject j = new JSONObject();
        put(j, "packageName", s.packageName);
        put(j, "name", s.name);
        put(j, "processName", s.processName);
        put(j, "exported", s.exported);
        put(j, "enabled", s.enabled);
        put(j, "permission", s.permission);
        return j;
    }
    private static JSONArray services(List<ResolveInfo> rs) {
        JSONArray a = new JSONArray();
        if (rs != null) for (ResolveInfo r : rs) a.put(serviceInfo(r));
        return a;
    }
    private static JSONObject providerInfo(ProviderInfo p) {
        if (p == null) return null;
        JSONObject j = new JSONObject();
        put(j, "packageName", p.packageName);
        put(j, "name", p.name);
        put(j, "authority", p.authority);
        put(j, "exported", p.exported);
        put(j, "enabled", p.enabled);
        put(j, "processName", p.processName);
        return j;
    }

    private JSONObject provider() {
        JSONObject j = new JSONObject();
        field(j, "query", () -> {
            try (Cursor c = getContentResolver().query(Uri.parse("content://dev.codex.envprobe.provider/ping"), null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    JSONObject o = new JSONObject();
                    put(o, "value", c.getString(0));
                    put(o, "callingUid", c.getInt(1));
                    put(o, "callingPid", c.getInt(2));
                    put(o, "processUid", c.getInt(3));
                    put(o, "processPid", c.getInt(4));
                    return o;
                }
                return null;
            }
        });
        return j;
    }

    private JSONObject peerProvider() {
        JSONObject j = new JSONObject();
        field(j, "query", () -> {
            try (Cursor c = getContentResolver().query(
                    Uri.parse("content://dev.codex.envpeer.provider/ping"), null, null, null, null)) {
                if (c == null || !c.moveToFirst()) return null;
                JSONObject o = new JSONObject();
                put(o, "value", c.getString(0));
                put(o, "callingUid", c.getInt(1));
                put(o, "callingPid", c.getInt(2));
                put(o, "processUid", c.getInt(3));
                put(o, "processPid", c.getInt(4));
                put(o, "markerPath", c.getString(5));
                put(o, "markerValue", c.getString(6));
                int devicePathColumn = c.getColumnIndex("deviceMarkerPath");
                int deviceValueColumn = c.getColumnIndex("deviceMarkerValue");
                if (devicePathColumn >= 0 && deviceValueColumn >= 0) {
                    put(o, "deviceMarkerPath", c.getString(devicePathColumn));
                    put(o, "deviceMarkerValue", c.getString(deviceValueColumn));
                }
                return o;
            }
        });
        return j;
    }

    private JSONObject accounts() {
        JSONObject j = new JSONObject();
        field(j, "getAccountsPermission", () -> checkSelfPermission(Manifest.permission.GET_ACCOUNTS));
        field(j, "authenticatorTypes", () -> {
            JSONArray a = new JSONArray();
            for (AuthenticatorDescription d : AccountManager.get(this).getAuthenticatorTypes()) a.put(d.type + "@" + d.packageName);
            return a;
        });
        field(j, "googleAccountCount", () -> AccountManager.get(this).getAccountsByType("com.google").length);
        return j;
    }

    private JSONObject runtime() {
        JSONObject j = new JSONObject();
        put(j, "sdk", Build.VERSION.SDK_INT);
        put(j, "supportedAbis", strings(Build.SUPPORTED_ABIS));
        put(j, "manufacturer", Build.MANUFACTURER);
        put(j, "model", Build.MODEL);
        put(j, "fingerprint", Build.FINGERPRINT);
        field(j, "classLoader", () -> String.valueOf(getClassLoader()));
        field(j, "contextClassLoader", () -> String.valueOf(Thread.currentThread().getContextClassLoader()));
        field(j, "javaLibraryPath", () -> System.getProperty("java.library.path"));
        field(j, "javaHome", () -> System.getProperty("java.home"));
        field(j, "procCmdline", () -> readSmall("/proc/self/cmdline", 512).replace('\0', ' '));
        field(j, "mountNamespace", () -> Os.readlink("/proc/self/ns/mnt"));
        field(j, "procStatus", () -> {
            String s = readSmall("/proc/self/status", 10000);
            JSONArray out = new JSONArray();
            for (String line : s.split("\n"))
                if (line.startsWith("Name:") || line.startsWith("Uid:") || line.startsWith("Gid:") || line.startsWith("NSpid:") || line.startsWith("TracerPid:")) out.put(line);
            return out;
        });
        field(j, "procMapsSelected", () -> {
            JSONArray out = new JSONArray();
            String maps = readSmall("/proc/self/maps", 250000);
            for (String line : maps.split("\n")) {
                if (line.contains("/data/") || line.contains("libart.so") || line.contains("linker64")) {
                    if (out.length() >= 80) break;
                    out.put(line);
                }
            }
            return out;
        });
        field(j, "procMountinfoSelected", () -> {
            JSONArray out = new JSONArray();
            String mounts = readSmall("/proc/self/mountinfo", 250000);
            for (String line : mounts.split("\n")) {
                if (line.contains("dev.codex.envprobe") || line.contains("com.multipleapp.clonespace") || line.contains("top.niunaijun.blackbox")) {
                    if (out.length() >= 40) break;
                    out.put(line);
                }
            }
            return out;
        });
        field(j, "systemProperties", () -> {
            JSONObject p = new JSONObject();
            String[] keys = {"ro.build.version.sdk", "ro.product.cpu.abi", "ro.product.model", "ro.build.fingerprint", "ro.debuggable", "ro.secure", "dalvik.vm.isa.arm64.features"};
            for (String key : keys) field(p, key, () -> getProp(key));
            return p;
        });
        return j;
    }
    private static String readSmall(String path, int limit) throws Exception {
        byte[] b = new byte[limit];
        try (FileInputStream in = new FileInputStream(path)) {
            int n = 0;
            while (n < limit) {
                int got = in.read(b, n, limit - n);
                if (got <= 0) break;
                n += got;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        }
    }
    private static String getProp(String key) throws Exception {
        java.lang.Process p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line = r.readLine();
            p.waitFor();
            return line;
        }
    }
}
