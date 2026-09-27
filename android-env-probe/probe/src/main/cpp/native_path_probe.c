#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void set(JNIEnv *env, jobjectArray out, int index, const char *value) {
    jstring text = (*env)->NewStringUTF(env, value);
    (*env)->SetObjectArrayElement(env, out, index, text);
    (*env)->DeleteLocalRef(env, text);
}

static void errtext(char *out, size_t len, int err) {
    snprintf(out, len, "ERR:%d", err);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_codex_envprobe_NativePathProbe_inspect(JNIEnv *env, jclass cls, jstring input) {
    (void)cls;
    const char *path = (*env)->GetStringUTFChars(env, input, NULL);
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, 7, string_class, NULL);
    char value[PATH_MAX + 80];
    char resolved[PATH_MAX];
    struct stat st;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        errtext(value, sizeof(value), errno);
        set(env, result, 0, value);
        set(env, result, 6, value);
    } else {
        char sample[81];
        ssize_t n = read(fd, sample, 80);
        if (n < 0) errtext(value, sizeof(value), errno);
        else {
            sample[n] = '\0';
            snprintf(value, sizeof(value), "%s", sample);
        }
        set(env, result, 0, value);
        char fdpath[64];
        snprintf(fdpath, sizeof(fdpath), "/proc/self/fd/%d", fd);
        n = readlink(fdpath, resolved, sizeof(resolved) - 1);
        if (n < 0) errtext(value, sizeof(value), errno);
        else { resolved[n] = '\0'; snprintf(value, sizeof(value), "%s", resolved); }
        set(env, result, 6, value);
        close(fd);
    }

    char parent[PATH_MAX];
    snprintf(parent, sizeof(parent), "%s", path);
    char *slash = strrchr(parent, '/');
    if (slash == NULL) {
        set(env, result, 1, "ERR:no-parent");
    } else {
        const char *base = slash + 1;
        if (slash == parent) slash[1] = '\0'; else *slash = '\0';
        int dirfd = open(parent, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (dirfd < 0) errtext(value, sizeof(value), errno);
        else {
            int child = openat(dirfd, base, O_RDONLY | O_CLOEXEC);
            if (child < 0) errtext(value, sizeof(value), errno);
            else {
                ssize_t n = read(child, value, 80);
                if (n < 0) errtext(value, sizeof(value), errno);
                else value[n] = '\0';
                close(child);
            }
            close(dirfd);
        }
        set(env, result, 1, value);
    }

    if (stat(path, &st) < 0) errtext(value, sizeof(value), errno);
    else snprintf(value, sizeof(value), "dev=%llu ino=%llu uid=%u mode=%o",
            (unsigned long long)st.st_dev, (unsigned long long)st.st_ino,
            st.st_uid, st.st_mode);
    set(env, result, 2, value);

    if (lstat(path, &st) < 0) errtext(value, sizeof(value), errno);
    else snprintf(value, sizeof(value), "dev=%llu ino=%llu uid=%u mode=%o",
            (unsigned long long)st.st_dev, (unsigned long long)st.st_ino,
            st.st_uid, st.st_mode);
    set(env, result, 3, value);

    ssize_t n = readlink(path, resolved, sizeof(resolved) - 1);
    if (n < 0) errtext(value, sizeof(value), errno);
    else { resolved[n] = '\0'; snprintf(value, sizeof(value), "%s", resolved); }
    set(env, result, 4, value);

    if (realpath(path, resolved) == NULL) errtext(value, sizeof(value), errno);
    else snprintf(value, sizeof(value), "%s", resolved);
    set(env, result, 5, value);

    (*env)->ReleaseStringUTFChars(env, input, path);
    return result;
}
