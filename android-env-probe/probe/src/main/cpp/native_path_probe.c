#include <jni.h>
#include <errno.h>
#include <dirent.h>
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
    jobjectArray result = (*env)->NewObjectArray(env, 13, string_class, NULL);
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
        n = readlinkat(AT_FDCWD, fdpath, resolved, sizeof(resolved) - 1);
        if (n < 0) errtext(value, sizeof(value), errno);
        else { resolved[n] = '\0'; snprintf(value, sizeof(value), "%s", resolved); }
        set(env, result, 10, value);
        close(fd);
    }
    if (fd < 0) set(env, result, 10, value);

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
        dirfd = open(parent, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (dirfd < 0) errtext(value, sizeof(value), errno);
        else {
            int child = openat64(dirfd, base, O_RDONLY | O_CLOEXEC);
            if (child < 0) errtext(value, sizeof(value), errno);
            else {
                ssize_t n = read(child, value, 80);
                if (n < 0) errtext(value, sizeof(value), errno);
                else value[n] = '\0';
                close(child);
            }
            close(dirfd);
        }
        set(env, result, 12, value);
    }
    if (slash == NULL) set(env, result, 12, "ERR:no-parent");

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

    if (access(path, R_OK) < 0) errtext(value, sizeof(value), errno);
    else snprintf(value, sizeof(value), "OK");
    set(env, result, 7, value);

    if (faccessat(AT_FDCWD, path, R_OK, 0) < 0) errtext(value, sizeof(value), errno);
    else snprintf(value, sizeof(value), "OK");
    set(env, result, 8, value);

    if (fstatat(AT_FDCWD, path, &st, 0) < 0) errtext(value, sizeof(value), errno);
    else snprintf(value, sizeof(value), "dev=%llu ino=%llu uid=%u mode=%o",
            (unsigned long long)st.st_dev, (unsigned long long)st.st_ino,
            st.st_uid, st.st_mode);
    set(env, result, 9, value);

    fd = open64(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) errtext(value, sizeof(value), errno);
    else {
        n = read(fd, value, 80);
        if (n < 0) errtext(value, sizeof(value), errno);
        else value[n] = '\0';
        close(fd);
    }
    set(env, result, 11, value);

    (*env)->ReleaseStringUTFChars(env, input, path);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_codex_envprobe_NativePathProbe_writeLogical(JNIEnv *env, jclass cls,
        jstring input, jstring content) {
    (void)cls;
    const char *path = (*env)->GetStringUTFChars(env, input, NULL);
    const char *data = (*env)->GetStringUTFChars(env, content, NULL);
    char status[64];
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) errtext(status, sizeof(status), errno);
    else {
        size_t length = strlen(data);
        ssize_t written = write(fd, data, length);
        if (written < 0) errtext(status, sizeof(status), errno);
        else if ((size_t)written != length) snprintf(status, sizeof(status), "ERR:short-write");
        else snprintf(status, sizeof(status), "OK");
        close(fd);
    }
    (*env)->ReleaseStringUTFChars(env, input, path);
    (*env)->ReleaseStringUTFChars(env, content, data);
    return (*env)->NewStringUTF(env, status);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_codex_envprobe_NativePathProbe_exerciseMutations(JNIEnv *env, jclass cls,
        jstring directoryInput, jstring markerInput) {
    (void)cls;
    const char *directory = (*env)->GetStringUTFChars(env, directoryInput, NULL);
    const char *marker = (*env)->GetStringUTFChars(env, markerInput, NULL);
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, 3, stringClass, NULL);
    char path[PATH_MAX], other[PATH_MAX], status[96], value[PATH_MAX];
    snprintf(path, sizeof(path), "%s/envprobe-native-dir-%d", directory, getpid());
    snprintf(status, sizeof(status), "OK");
    if (mkdir(path, 0700) != 0 && errno != EEXIST) {
        snprintf(status, sizeof(status), "ERR:mkdir:%d", errno);
    } else {
        DIR *dir = opendir(path);
        if (dir == NULL) snprintf(status, sizeof(status), "ERR:opendir:%d", errno);
        else {
            int dfd = dirfd(dir);
            int fd = openat(dfd, "source", O_WRONLY | O_CREAT | O_TRUNC, 0600);
            if (fd < 0) snprintf(status, sizeof(status), "ERR:openat-create:%d", errno);
            else { write(fd, "x", 1); close(fd); }
            if (status[0] == 'O' && renameat(dfd, "source", dfd, "renamed") != 0)
                snprintf(status, sizeof(status), "ERR:renameat:%d", errno);
            if (status[0] == 'O' && unlinkat(dfd, "renamed", 0) != 0)
                snprintf(status, sizeof(status), "ERR:unlinkat:%d", errno);
            if (status[0] == 'O' && mkdirat(dfd, "child", 0700) != 0)
                snprintf(status, sizeof(status), "ERR:mkdirat:%d", errno);
            if (status[0] == 'O' && unlinkat(dfd, "child", AT_REMOVEDIR) != 0)
                snprintf(status, sizeof(status), "ERR:unlinkat-dir:%d", errno);
            closedir(dir);
        }
        if (status[0] == 'O' && rmdir(path) != 0)
            snprintf(status, sizeof(status), "ERR:rmdir:%d", errno);
    }
    if (status[0] == 'O') {
        snprintf(path, sizeof(path), "%s/envprobe-native-source-%d", directory, getpid());
        snprintf(other, sizeof(other), "%s/envprobe-native-renamed-%d", directory, getpid());
        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd < 0) snprintf(status, sizeof(status), "ERR:open-create:%d", errno);
        else { write(fd, "y", 1); close(fd); }
        if (status[0] == 'O' && rename(path, other) != 0)
            snprintf(status, sizeof(status), "ERR:rename:%d", errno);
        if (status[0] == 'O' && unlink(other) != 0)
            snprintf(status, sizeof(status), "ERR:unlink:%d", errno);
    }
    set(env, result, 0, status);

    snprintf(path, sizeof(path), "%s/envprobe-native-link-%d", directory, getpid());
    unlink(path);
    if (symlink(marker, path) != 0) {
        errtext(value, sizeof(value), errno);
        set(env, result, 1, value);
        set(env, result, 2, value);
    } else {
        int fd = open(path, O_RDONLY);
        if (fd < 0) errtext(value, sizeof(value), errno);
        else {
            ssize_t n = read(fd, value, 80);
            if (n < 0) errtext(value, sizeof(value), errno);
            else value[n] = '\0';
            close(fd);
        }
        set(env, result, 1, value);
        ssize_t n = readlink(path, value, sizeof(value) - 1);
        if (n < 0) errtext(value, sizeof(value), errno);
        else value[n] = '\0';
        set(env, result, 2, value);
        unlink(path);
    }
    (*env)->ReleaseStringUTFChars(env, directoryInput, directory);
    (*env)->ReleaseStringUTFChars(env, markerInput, marker);
    return result;
}
