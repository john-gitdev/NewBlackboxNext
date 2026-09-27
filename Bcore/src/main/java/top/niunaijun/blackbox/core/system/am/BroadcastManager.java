package top.niunaijun.blackbox.core.system.am;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackage;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.core.system.pm.PackageMonitor;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.core.system.user.BUserManagerService;
import top.niunaijun.blackbox.entity.am.PendingResultData;
import top.niunaijun.blackbox.entity.am.ReceiverData;
import top.niunaijun.blackbox.proxy.ProxyBroadcastReceiver;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;


public class BroadcastManager implements PackageMonitor {
    public static final String TAG = "BroadcastManager";

    public static final int TIMEOUT = 9000;

    public static final int MSG_TIME_OUT = 1;

    private static BroadcastManager sBroadcastManager;

    private final BActivityManagerService mAms;
    private final BPackageManagerService mPms;
    private final Map<String, List<BroadcastReceiver>> mReceivers = new HashMap<>();
    private final Map<String, PendingResultData> mReceiversData = new HashMap<>();

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case MSG_TIME_OUT:
                    try {
                        PendingResultData data = (PendingResultData) msg.obj;
                        data.build().finish();
                        Slog.d(TAG, "Timeout Receiver: " + data);
                    } catch (Throwable ignore) {
                    }
                    break;
            }
        }
    };

    public static BroadcastManager startSystem(BActivityManagerService ams, BPackageManagerService pms) {
        if (sBroadcastManager == null) {
            synchronized (BroadcastManager.class) {
                if (sBroadcastManager == null) {
                    sBroadcastManager = new BroadcastManager(ams, pms);
                }
            }
        }
        return sBroadcastManager;
    }

    public BroadcastManager(BActivityManagerService ams, BPackageManagerService pms) {
        mAms = ams;
        mPms = pms;
    }

    public void startup() {
        mPms.addPackageMonitor(this);
        List<BPackageSettings> bPackageSettings = mPms.getBPackageSettings();
        for (BPackageSettings bPackageSetting : bPackageSettings) {
            BPackage bPackage = bPackageSetting.pkg;
            registerPackage(bPackage);
        }
    }

    private void registerPackage(BPackage bPackage) {
        synchronized (mReceivers) {
            Slog.d(TAG, "register: " + bPackage.packageName + ", size: " + bPackage.receivers.size());
            for (BPackage.Activity receiver : bPackage.receivers) {
                List<BPackage.ActivityIntentInfo> intents = receiver.intents;
                for (BPackage.ActivityIntentInfo intent : intents) {
                    ProxyBroadcastReceiver proxyBroadcastReceiver = new ProxyBroadcastReceiver();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        BlackBoxCore.getContext().registerReceiver(proxyBroadcastReceiver, intent.intentFilter, Context.RECEIVER_EXPORTED);
                    }else{
                        BlackBoxCore.getContext().registerReceiver(proxyBroadcastReceiver, intent.intentFilter);
                    }
                    addReceiver(bPackage.packageName, proxyBroadcastReceiver);
                }
            }
        }
    }

    private void addReceiver(String packageName, BroadcastReceiver receiver) {
        List<BroadcastReceiver> broadcastReceivers = mReceivers.get(packageName);
        if (broadcastReceivers == null) {
            broadcastReceivers = new ArrayList<>();
            mReceivers.put(packageName, broadcastReceivers);
        }
        broadcastReceivers.add(receiver);
    }

    public void sendBroadcast(PendingResultData pendingResultData) {
        synchronized (mReceiversData) {
            
            mReceiversData.put(pendingResultData.mBToken, pendingResultData);
            Message obtain = Message.obtain(mHandler, MSG_TIME_OUT, pendingResultData);
            mHandler.sendMessageDelayed(obtain, TIMEOUT);
        }
    }

    public void finishBroadcast(PendingResultData data) {
        synchronized (mReceiversData) {
            
            mHandler.removeMessages(MSG_TIME_OUT, mReceiversData.get(data.mBToken));
        }
    }

    @Override
    public void onPackageUninstalled(String packageName, boolean removeApp, int userId) {
        if (removeApp) {
            synchronized (mReceivers) {
                List<BroadcastReceiver> broadcastReceivers = mReceivers.get(packageName);
                if (broadcastReceivers != null) {
                    Slog.d(TAG, "unregisterReceiver Package: " + packageName + ", size: " + broadcastReceivers.size());
                    for (BroadcastReceiver broadcastReceiver : broadcastReceivers) {
                        try {
                            BlackBoxCore.getContext().unregisterReceiver(broadcastReceiver);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                mReceivers.remove(packageName);
            }
        }
    }

    @Override
    public void onPackageInstalled(String packageName, int userId) {
        synchronized (mReceivers) {
            mReceivers.remove(packageName);
            BPackageSettings bPackageSetting = mPms.getBPackageSetting(packageName);
            if (bPackageSetting != null) {
                registerPackage(bPackageSetting.pkg);
            }
        }
        if (mSystemStarted) {
            bootHandler().post(() -> sendBootCompleted(packageName, userId));
        }
    }

    // Android sends the boot broadcasts when the phone starts, and never to guests, so an
    // app that sets itself up at boot never did - Play services never checked in with
    // Google, and push registration failed with AUTHENTICATION_FAILED. The container
    // sends them itself: once per phone boot to every app, and to an app installed after
    // that, since in a container that is how apps usually arrive.
    private static final String[] BOOT_ACTIONS = {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED};

    private volatile boolean mSystemStarted;
    private Handler mBootHandler;

    // Called once the container's services are all up.
    public void onSystemStarted() {
        if (isNewPhoneBoot()) {
            bootHandler().post(() -> sendBootCompleted(null, BUserHandle.USER_ALL));
        }
        mSystemStarted = true;
    }

    private synchronized Handler bootHandler() {
        if (mBootHandler == null) {
            HandlerThread thread = new HandlerThread("BootBroadcast");
            thread.start();
            mBootHandler = new Handler(thread.getLooper());
        }
        return mBootHandler;
    }

    // Starts each receiving app's process, as Android would for a manifest receiver.
    // A null packageName means every app.
    private void sendBootCompleted(String packageName, int userId) {
        if (userId == BUserHandle.USER_ALL) {
            for (BUserInfo user : BUserManagerService.get().getUsers()) {
                sendBootCompleted(packageName, user.id);
            }
            return;
        }
        for (String action : BOOT_ACTIONS) {
            Intent intent = new Intent(action);
            if (packageName != null) {
                intent.setPackage(packageName);
            }
            List<ResolveInfo> resolves;
            try {
                resolves = mPms.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA, null, userId);
            } catch (Throwable t) {
                Slog.w(TAG, "Unable to query " + action, t);
                continue;
            }
            int missingActivityInfo = 0;
            for (ResolveInfo resolve : resolves) {
                ActivityInfo info = resolve == null ? null : resolve.activityInfo;
                if (info == null) {
                    missingActivityInfo++;
                    continue;
                }
                try {
                    ProcessRecord app = BProcessManagerService.get()
                            .startProcessLocked(info.packageName, info.processName, userId, -1, -1);
                    if (app == null || app.bActivityThread == null) {
                        Slog.w(TAG, "No process for " + info.name);
                        continue;
                    }
                    ReceiverData data = new ReceiverData();
                    data.intent = new Intent(intent).setComponent(new ComponentName(info.packageName, info.name));
                    data.activityInfo = info;
                    data.data = new PendingResultData(userId);
                    sendBroadcast(data.data);
                    app.bActivityThread.scheduleReceiver(data);
                    Slog.d(TAG, action + " -> " + info.packageName + "/" + info.name + " (user " + userId + ")");
                } catch (Throwable t) {
                    Slog.w(TAG, "Unable to deliver " + action + " to " + info.name, t);
                }
            }
            if (missingActivityInfo != 0) {
                Slog.w(TAG, "Skipped " + missingActivityInfo
                        + " boot receiver results without ActivityInfo for " + action);
            }
        }
    }

    // True the first time it's asked after the phone starts, so a restart of the
    // container's service process doesn't replay the broadcasts.
    private static boolean isNewPhoneBoot() {
        String boot = currentPhoneBoot();
        File marker = new File(BEnvironment.getSystemDir(), "boot_marker");
        try {
            if (marker.exists() && boot.equals(FileUtils.readToString(marker.getAbsolutePath()).trim())) {
                return false;
            }
            FileUtils.writeToFile(boot.getBytes(StandardCharsets.UTF_8), marker);
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to track phone boots", t);
        }
        return true;
    }

    private static String currentPhoneBoot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return "count:" + Settings.Global.getInt(
                        BlackBoxCore.getContext().getContentResolver(), Settings.Global.BOOT_COUNT);
            } catch (Throwable ignored) {
            }
        }
        // Boot time to the minute, which rides out small clock adjustments.
        return "time:" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000;
    }
}
