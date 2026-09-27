package dev.codex.envprobe;

final class NativePathProbe {
    static {
        System.loadLibrary("envprobe");
    }

    private NativePathProbe() { }

    static native String[] inspect(String path);
}
