package top.niunaijun.blackbox.app.dispatcher;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.JobRecord;
import top.niunaijun.blackbox.utils.Slog;


public class AppJobServiceDispatcher {
    public static final String TAG = "AppJobServiceDispatcher";
    private static final AppJobServiceDispatcher sServiceDispatcher = new AppJobServiceDispatcher();

    // Keyed by service class. Like Android, one instance of a job service takes every
    // job aimed at it. Touched on the main thread only.
    private final Map<String, Service> mServices = new HashMap<>();
    private final Map<String, IBinder> mBinders = new HashMap<>();

    public static AppJobServiceDispatcher get() {
        return sServiceDispatcher;
    }

    // The binder the job's guest service returned from onBind - what Android itself
    // would be talking to outside BlackBox - or null if the job can't be placed.
    public IBinder getJobBinder(int jobId) {
        JobRecord record = BlackBoxCore.getBJobManager().queryJobRecord(BlackBoxCore.getAppProcessName(), jobId);
        if (record == null || record.mServiceInfo == null) {
            Slog.w(TAG, "No record of job " + jobId);
            return null;
        }
        return bindOnMainThread(record.mServiceInfo);
    }

    private IBinder bindOnMainThread(ServiceInfo serviceInfo) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return bind(serviceInfo);
        }
        IBinder[] binder = new IBinder[1];
        ConditionVariable done = new ConditionVariable();
        BlackBoxCore.get().getHandler().post(() -> {
            try {
                binder[0] = bind(serviceInfo);
            } catch (Throwable t) {
                Slog.e(TAG, "Unable to bind job service " + serviceInfo.name, t);
            } finally {
                done.open();
            }
        });
        done.block();
        return binder[0];
    }

    private IBinder bind(ServiceInfo serviceInfo) {
        IBinder binder = mBinders.get(serviceInfo.name);
        if (binder != null) {
            return binder;
        }
        Service service = BlackBoxCore.currentActivityThread().createJobService(serviceInfo);
        if (service == null) {
            return null;
        }
        binder = service.onBind(new Intent().setComponent(
                new ComponentName(serviceInfo.packageName, serviceInfo.name)));
        if (binder == null) {
            Slog.w(TAG, serviceInfo.name + " returned no binder from onBind");
            service.onDestroy();
            return null;
        }
        mServices.put(serviceInfo.name, service);
        mBinders.put(serviceInfo.name, binder);
        return binder;
    }

    public void onDestroy() {
        for (Service service : mServices.values()) {
            try {
                service.onDestroy();
            } catch (Throwable t) {
                Slog.w(TAG, "onDestroy failed for " + service.getClass().getName(), t);
            }
        }
        mServices.clear();
        mBinders.clear();
    }

    public void onConfigurationChanged(Configuration newConfig) {
        for (Service service : mServices.values()) {
            service.onConfigurationChanged(newConfig);
        }
    }

    public void onLowMemory() {
        for (Service service : mServices.values()) {
            service.onLowMemory();
        }
    }

    public void onTrimMemory(int level) {
        for (Service service : mServices.values()) {
            service.onTrimMemory(level);
        }
    }
}
