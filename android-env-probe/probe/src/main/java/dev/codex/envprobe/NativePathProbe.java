package dev.codex.envprobe;

final class NativePathProbe {
    static {
        System.loadLibrary("envprobe");
    }

    private NativePathProbe() { }

    static native String[] inspect(String path);
    static native String writeLogical(String path, String content);
    static native String[] exerciseMutations(String directory, String marker);
}
