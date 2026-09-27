package dev.codex.envprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Device-protected breadcrumb for a later controlled direct-boot test. */
public final class BootProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            Context device = context.createDeviceProtectedStorageContext();
            File file = new File(device.getFilesDir(), "envprobe-boot-events.txt");
            String line = intent.getAction() + ";time=" + System.currentTimeMillis()
                    + ";uid=" + Process.myUid() + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }
}
