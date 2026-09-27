package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class IRoleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "RoleManagerStub";
    private static final String ROLE_SERVICE = "role";

    public IRoleManagerProxy() {
        super(BRServiceManager.get().getService(ROLE_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(ROLE_SERVICE);
        try {
            return Class.forName("android.app.role.IRoleManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
        } catch (ReflectiveOperationException e) {
            Slog.e(TAG, "Unable to get IRoleManager", e);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(ROLE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // The service checks the package it's asked about belongs to the caller, and a
    // guest's never belongs to the host - Firefox died with "Package org.mozilla.firefox
    // does not belong to 10355" asking whether it was the default browser. A guest
    // can't hold a device role, so the answer is no.
    @ProxyMethod("isRoleHeldAsUser")
    public static class IsRoleHeldAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }
}
