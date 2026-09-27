package top.niunaijun.blackbox.fake.service;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import black.android.app.job.BRIJobSchedulerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class IJobServiceProxy extends BinderInvocationStub {
    public static final String TAG = "JobServiceStub";

    public IJobServiceProxy() {
        super(BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder jobScheduler = BRServiceManager.get().getService("jobscheduler");
        return BRIJobSchedulerStub.get().asInterface(jobScheduler);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    @ProxyMethod("schedule")
    public static class Schedule extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return scheduleAsHost(who, method, args);
        }
    }

    @ProxyMethod("enqueue")
    public static class Enqueue extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return scheduleAsHost(who, method, args);
        }
    }

    // The system only accepts a job whose service belongs to the calling uid, so the
    // guest's JobInfo has to be pointed at one of the host's proxy job services first.
    // Handing over the guest's own component fails with "uid N cannot schedule job in
    // <guest package>". Android 14 (API 34) put a namespace ahead of the JobInfo -
    // schedule(String, JobInfo), enqueue(String, JobInfo, JobWorkItem) - so the job
    // is found by type rather than position.
    private static Object scheduleAsHost(Object who, Method method, Object[] args) throws Throwable {
        int index = indexOfJobInfo(args);
        if (index < 0) {
            Slog.w(TAG, method.getName() + ": no JobInfo in arguments");
            return JobScheduler.RESULT_FAILURE;
        }
        JobInfo job = (JobInfo) args[index];
        JobInfo proxyJob;
        try {
            proxyJob = BlackBoxCore.getBJobManager().schedule(job);
        } catch (RuntimeException e) {
            Slog.w(TAG, method.getName() + ": job manager failed for " + job.getService(), e);
            proxyJob = null;
        }
        if (proxyJob == null) {
            Slog.w(TAG, method.getName() + ": no proxy service for " + job.getService() + ", job dropped");
            return JobScheduler.RESULT_FAILURE;
        }
        args[index] = proxyJob;
        try {
            return method.invoke(who, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static int indexOfJobInfo(Object[] args) {
        if (args == null) {
            return -1;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof JobInfo) {
                return i;
            }
        }
        return -1;
    }

    @ProxyMethod("cancel")
    public static class Cancel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // The job id is the last argument: cancel(int), or cancel(String, int) from API 34.
            int index = args.length - 1;
            try {
                Integer jobId = (Integer) args[index];
                if (jobId == null) {
                    Slog.w(TAG, "Cancel: JobId is null");
                    return method.invoke(who, args);
                }

                args[index] = BlackBoxCore.getBJobManager()
                        .cancel(BActivityThread.getAppConfig().processName, jobId);
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.e(TAG, "Cancel: Error canceling job", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("cancelAll")
    public static class CancelAll extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                BlackBoxCore.getBJobManager().cancelAll(BActivityThread.getAppConfig().processName);
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.e(TAG, "CancelAll: Error canceling all jobs", e);
                return method.invoke(who, args);
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
