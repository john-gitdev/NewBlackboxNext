package dev.codex.envpeer;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

public final class PeerService extends Service {
    private final IBinder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
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

    public PeerService() {
        ((Binder) binder).attachInterface(null, "dev.codex.envpeer.PeerService");
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
}
