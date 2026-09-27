package dev.codex.envprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/** Records actual unlock state and Java/native CE/DE availability at boot delivery. */
public final class BootProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "ENVPROBE_BOOT";

    @Override public void onReceive(Context context, Intent intent) {
        JSONObject event = new JSONObject();
        try {
            Context device = context.createDeviceProtectedStorageContext();
            File deMarker = new File(device.getFilesDir(), "envprobe-directboot-de-marker.txt");
            File deLogical = new File("/data/user_de/0/" + context.getPackageName()
                    + "/files/envprobe-directboot-de-marker.txt");
            event.put("action", intent.getAction());
            event.put("time", System.currentTimeMillis());
            event.put("uid", Process.myUid());
            event.put("userUnlocked", ((UserManager) context.getSystemService(Context.USER_SERVICE))
                    .isUserUnlocked());
            event.put("receiverDeviceProtected", context.isDeviceProtectedStorage());
            event.put("dePath", deMarker.getAbsolutePath());
            event.put("deLogicalPath", deLogical.getAbsolutePath());
            String token = "boot;pid=" + Process.myPid() + ";time=" + System.currentTimeMillis();
            try (FileOutputStream out = new FileOutputStream(deMarker, false)) {
                out.write(token.getBytes(StandardCharsets.UTF_8));
                event.put("deWrite", "OK");
            } catch (Throwable t) {
                event.put("deWrite", failure(t));
            }
            event.put("deJavaRead", read(deMarker));
            event.put("deNativeRead", nativeRead(deMarker));
            event.put("deLogicalJavaRead", read(deLogical));
            event.put("deLogicalNativeRead", nativeRead(deLogical));
            try {
                File ceMarker = new File(context.getApplicationInfo().dataDir,
                        "files/envprobe-isolation-marker.txt");
                event.put("cePath", ceMarker.getAbsolutePath());
                event.put("ceJavaRead", read(ceMarker));
                event.put("ceNativeRead", nativeRead(ceMarker));
            } catch (Throwable t) {
                event.put("ceAccess", failure(t));
            }
            Log.i(TAG, event.toString());
            File events = new File(device.getFilesDir(), "envprobe-boot-events.txt");
            try (FileOutputStream out = new FileOutputStream(events, true)) {
                out.write((event.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                Log.w(TAG, "Could not persist boot event in DE storage", t);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Boot probe failed before storage checks: " + event, t);
        }
    }

    private static String read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[80];
            int n = in.read(buffer);
            return n < 0 ? "" : new String(buffer, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return failure(t);
        }
    }

    private static String nativeRead(File file) {
        try {
            return NativePathProbe.inspect(file.getAbsolutePath())[0];
        } catch (Throwable t) {
            return failure(t);
        }
    }

    private static String failure(Throwable t) {
        return "ERR:" + t.getClass().getSimpleName() + ":" + t.getMessage();
    }
}
