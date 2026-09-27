package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class ISafetyCenterManagerProxy extends BinderInvocationStub {
    public static final String TAG = "SafetyCenterStub";
    private static final String SAFETY_CENTER_SERVICE = "safety_center";

    public ISafetyCenterManagerProxy() {
        super(BRServiceManager.get().getService(SAFETY_CENTER_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SAFETY_CENTER_SERVICE);
        try {
            return Class.forName("android.safetycenter.ISafetyCenterManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
        } catch (ReflectiveOperationException e) {
            Slog.e(TAG, "Unable to get ISafetyCenterManager", e);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SAFETY_CENTER_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // Needs READ_SAFETY_CENTER_STATUS or SEND_SAFETY_CENTER_UPDATE, which only system apps
    // get. Play services asks on boot and its process died of the SecurityException; a
    // guest can't feed Safety Center anyway, so to it Safety Center is off.
    @ProxyMethod("isSafetyCenterEnabled")
    public static class IsSafetyCenterEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }
}
