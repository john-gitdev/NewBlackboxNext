package top.niunaijun.blackbox.proxy;

import android.app.Service;
import android.app.job.JobParameters;
import android.app.job.JobServiceEngine;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import top.niunaijun.blackbox.app.dispatcher.AppJobServiceDispatcher;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class ProxyJobService extends Service {
    public static final String TAG = "StubJobService";
    private static final String DESCRIPTOR = "android.app.job.IJobService";

    // Takes jobs no guest service can be found for and finishes them straight away, so
    // Android isn't left waiting on an answer. JobServiceEngine is API 26+; earlier,
    // such a job simply times out.
    private IBinder mNoJobBinder;

    // Android drives a job service through the binder its onBind returns. This one reads
    // the job id off each call and passes the call on untouched to the binder the job's
    // guest service returned, so the guest's own job engine deals with Android directly.
    // That covers JobService and anything else built on JobServiceEngine - androidx's
    // JobIntentService is a plain Service - along with every call newer Android adds.
    // Calling the guest from inside a JobService of our own instead would have both
    // engines acknowledge the same start, and Android takes the second as the job ending.
    private final Binder mRouter = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            // Every IJobService call starts with the JobParameters.
            int start = data.dataPosition();
            data.enforceInterface(DESCRIPTOR);
            JobParameters params = data.readInt() != 0 ? JobParameters.CREATOR.createFromParcel(data) : null;
            data.setDataPosition(start);

            IBinder target = null;
            if (params != null) {
                try {
                    target = AppJobServiceDispatcher.get().getJobBinder(params.getJobId());
                } catch (Throwable t) {
                    Slog.e(TAG, "Unable to reach the service for job " + params.getJobId(), t);
                }
            }
            if (target == null) {
                target = mNoJobBinder;
            }
            return target == null || target.transact(code, data, reply, flags);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildCompat.isOreo()) {
            mNoJobBinder = new JobServiceEngine(this) {
                @Override
                public boolean onStartJob(JobParameters params) {
                    return false;
                }

                @Override
                public boolean onStopJob(JobParameters params) {
                    return false;
                }
            }.getBinder();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mRouter;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppJobServiceDispatcher.get().onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AppJobServiceDispatcher.get().onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        AppJobServiceDispatcher.get().onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        AppJobServiceDispatcher.get().onTrimMemory(level);
    }

    public static class P0 extends ProxyJobService {

    }

    public static class P1 extends ProxyJobService {

    }

    public static class P2 extends ProxyJobService {

    }

    public static class P3 extends ProxyJobService {

    }

    public static class P4 extends ProxyJobService {

    }

    public static class P5 extends ProxyJobService {

    }

    public static class P6 extends ProxyJobService {

    }

    public static class P7 extends ProxyJobService {

    }

    public static class P8 extends ProxyJobService {

    }

    public static class P9 extends ProxyJobService {

    }

    public static class P10 extends ProxyJobService {

    }

    public static class P11 extends ProxyJobService {

    }

    public static class P12 extends ProxyJobService {

    }

    public static class P13 extends ProxyJobService {

    }

    public static class P14 extends ProxyJobService {

    }

    public static class P15 extends ProxyJobService {

    }

    public static class P16 extends ProxyJobService {

    }

    public static class P17 extends ProxyJobService {

    }

    public static class P18 extends ProxyJobService {

    }

    public static class P19 extends ProxyJobService {

    }

    public static class P20 extends ProxyJobService {

    }

    public static class P21 extends ProxyJobService {

    }

    public static class P22 extends ProxyJobService {

    }

    public static class P23 extends ProxyJobService {

    }

    public static class P24 extends ProxyJobService {

    }

    public static class P25 extends ProxyJobService {

    }

    public static class P26 extends ProxyJobService {

    }

    public static class P27 extends ProxyJobService {

    }

    public static class P28 extends ProxyJobService {

    }

    public static class P29 extends ProxyJobService {

    }

    public static class P30 extends ProxyJobService {

    }

    public static class P31 extends ProxyJobService {

    }

    public static class P32 extends ProxyJobService {

    }

    public static class P33 extends ProxyJobService {

    }

    public static class P34 extends ProxyJobService {

    }

    public static class P35 extends ProxyJobService {

    }

    public static class P36 extends ProxyJobService {

    }

    public static class P37 extends ProxyJobService {

    }

    public static class P38 extends ProxyJobService {

    }

    public static class P39 extends ProxyJobService {

    }

    public static class P40 extends ProxyJobService {

    }

    public static class P41 extends ProxyJobService {

    }

    public static class P42 extends ProxyJobService {

    }

    public static class P43 extends ProxyJobService {

    }

    public static class P44 extends ProxyJobService {

    }

    public static class P45 extends ProxyJobService {

    }

    public static class P46 extends ProxyJobService {

    }

    public static class P47 extends ProxyJobService {

    }

    public static class P48 extends ProxyJobService {

    }

    public static class P49 extends ProxyJobService {

    }
}
