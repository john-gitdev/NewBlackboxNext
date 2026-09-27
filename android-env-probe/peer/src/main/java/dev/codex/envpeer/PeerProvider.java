package dev.codex.envpeer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

public final class PeerProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"value", "callingUid", "callingPid", "processUid", "processPid"});
        c.addRow(new Object[]{"peer-ok", Binder.getCallingUid(), Binder.getCallingPid(),
                Process.myUid(), Process.myPid()});
        return c;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.dev.codex.envpeer"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
