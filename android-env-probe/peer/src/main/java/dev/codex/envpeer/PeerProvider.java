package dev.codex.envpeer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class PeerProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File marker = new File(getContext().getFilesDir(), "envpeer-isolation-marker.txt");
        String markerValue = "peer;pid=" + Process.myPid() + ";time=" + System.currentTimeMillis();
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(markerValue.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            markerValue = "ERR:" + e.getClass().getSimpleName();
        }
        File deviceMarker = new File(getContext().createDeviceProtectedStorageContext().getFilesDir(),
                "envpeer-device-marker.txt");
        String deviceMarkerValue = "peer-de;pid=" + Process.myPid() + ";time="
                + System.currentTimeMillis();
        try (FileOutputStream out = new FileOutputStream(deviceMarker)) {
            out.write(deviceMarkerValue.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            deviceMarkerValue = "ERR:" + e.getClass().getSimpleName();
        }
        MatrixCursor c = new MatrixCursor(new String[]{"value", "callingUid", "callingPid",
                "processUid", "processPid", "markerPath", "markerValue",
                "deviceMarkerPath", "deviceMarkerValue"});
        c.addRow(new Object[]{"peer-ok", Binder.getCallingUid(), Binder.getCallingPid(),
                Process.myUid(), Process.myPid(), marker.getAbsolutePath(), markerValue,
                deviceMarker.getAbsolutePath(), deviceMarkerValue});
        return c;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.dev.codex.envpeer"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
