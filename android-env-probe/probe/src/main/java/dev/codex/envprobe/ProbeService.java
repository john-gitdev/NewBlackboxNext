package dev.codex.envprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

public final class ProbeService extends Service {
    private final IBinder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                reply.writeNoException();
                reply.writeInt(Binder.getCallingUid());
                reply.writeInt(Binder.getCallingPid());
                reply.writeInt(Process.myUid());
                reply.writeInt(Process.myPid());
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    public ProbeService() {
        ((Binder) binder).attachInterface(null, "dev.codex.envprobe.ProbeService");
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
}
