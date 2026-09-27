package android.app;

import android.os.IInterface;

/**
 * Compile-time stand-in for the hidden interface Android 17 (API 37) added to
 * {@code IServiceConnection.connected}. The framework's own class wins at runtime,
 * so this only has to carry the name.
 */
public interface IBinderSession extends IInterface {
}
