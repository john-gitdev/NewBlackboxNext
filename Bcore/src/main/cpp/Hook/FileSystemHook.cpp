#include "FileSystemHook.h"

#include "Dobby/dobby.h"
#include "IO.h"
#include "Log.h"
#include "xdl.h"

#include <cerrno>
#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {
thread_local unsigned hookDepth = 0;
std::mutex installMutex;
bool installed = false;

struct HookScope {
    HookScope() { ++hookDepth; }
    ~HookScope() { --hookDepth; }
};

const char *translated(const char *path, std::string &storage) {
    return hookDepth == 1 && IO::translatePath(path, storage) ? storage.c_str() : path;
}

std::vector<std::pair<void *, void *>> installedAddresses;

template <typename Function>
void hookSymbol(void *handle, const char *name, void *replacement, Function &original) {
    void *address = xdl_sym(handle, name, nullptr);
    if (address == nullptr) {
        ALOGD("FileSystemHook: libc symbol %s unavailable", name);
        return;
    }
    for (const auto &entry : installedAddresses) {
        if (entry.first == address) {
            original = reinterpret_cast<Function>(entry.second);
            ALOGD("FileSystemHook: %s aliases an installed symbol", name);
            return;
        }
    }
    void *trampoline = nullptr;
    if (DobbyHook(address, replacement, &trampoline) != 0 || trampoline == nullptr) {
        ALOGE("FileSystemHook: failed to hook %s", name);
        return;
    }
    original = reinterpret_cast<Function>(trampoline);
    installedAddresses.emplace_back(address, trampoline);
    ALOGD("FileSystemHook: hooked %s", name);
}

bool hasMode(int flags) {
    if ((flags & O_CREAT) != 0) return true;
#ifdef O_TMPFILE
    if ((flags & O_TMPFILE) == O_TMPFILE) return true;
#endif
    return false;
}

int (*origOpen)(const char *, int, ...) = nullptr;
int (*origOpen64)(const char *, int, ...) = nullptr;
int (*origOpen2)(const char *, int) = nullptr;
int (*origOpenat)(int, const char *, int, ...) = nullptr;
int (*origOpenat64)(int, const char *, int, ...) = nullptr;
int (*origOpenat2)(int, const char *, int) = nullptr;
int (*origStat)(const char *, struct stat *) = nullptr;
int (*origLstat)(const char *, struct stat *) = nullptr;
int (*origFstatat)(int, const char *, struct stat *, int) = nullptr;
int (*origNewfstatat)(int, const char *, struct stat *, int) = nullptr;
int (*origAccess)(const char *, int) = nullptr;
int (*origFaccessat)(int, const char *, int, int) = nullptr;
char *(*origRealpath)(const char *, char *) = nullptr;
ssize_t (*origReadlink)(const char *, char *, size_t) = nullptr;
ssize_t (*origReadlinkat)(int, const char *, char *, size_t) = nullptr;
int (*origRename)(const char *, const char *) = nullptr;
int (*origRenameat)(int, const char *, int, const char *) = nullptr;
int (*origUnlink)(const char *) = nullptr;
int (*origUnlinkat)(int, const char *, int) = nullptr;
int (*origMkdir)(const char *, mode_t) = nullptr;
int (*origMkdirat)(int, const char *, mode_t) = nullptr;
int (*origRmdir)(const char *) = nullptr;
DIR *(*origOpendir)(const char *) = nullptr;
int (*origChdir)(const char *) = nullptr;
char *(*origGetcwd)(char *, size_t) = nullptr;
int (*origSymlink)(const char *, const char *) = nullptr;
int (*origSymlinkat)(const char *, int, const char *) = nullptr;

int hookOpen(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (hasMode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return origOpen(effective, flags, mode);
}

int hookOpen64(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (hasMode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return origOpen64(effective, flags, mode);
}

int hookOpen2(const char *path, int flags) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return origOpen2(effective, flags);
}

int hookOpenat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (hasMode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    // Absolute paths ignore dirfd; relative paths use the already-open backing fd.
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return origOpenat(dirfd, effective, flags, mode);
}

int hookOpenat64(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (hasMode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return origOpenat64(dirfd, effective, flags, mode);
}

int hookOpenat2(int dirfd, const char *path, int flags) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return origOpenat2(dirfd, effective, flags);
}

#define PATH_INT_HOOK(name, original, signature, call) \
    int name signature { \
        HookScope scope; \
        const int incomingErrno = errno; \
        std::string storage; \
        const char *effective = translated(path, storage); \
        errno = incomingErrno; \
        return original call; \
    }

PATH_INT_HOOK(hookStat, origStat,
              (const char *path, struct stat *result), (effective, result))
PATH_INT_HOOK(hookLstat, origLstat,
              (const char *path, struct stat *result), (effective, result))
PATH_INT_HOOK(hookFstatat, origFstatat,
              (int dirfd, const char *path, struct stat *result, int flags),
              (dirfd, effective, result, flags))
PATH_INT_HOOK(hookNewfstatat, origNewfstatat,
              (int dirfd, const char *path, struct stat *result, int flags),
              (dirfd, effective, result, flags))
PATH_INT_HOOK(hookAccess, origAccess,
              (const char *path, int mode), (effective, mode))
PATH_INT_HOOK(hookFaccessat, origFaccessat,
              (int dirfd, const char *path, int mode, int flags),
              (dirfd, effective, mode, flags))
PATH_INT_HOOK(hookUnlink, origUnlink,
              (const char *path), (effective))
PATH_INT_HOOK(hookUnlinkat, origUnlinkat,
              (int dirfd, const char *path, int flags), (dirfd, effective, flags))
PATH_INT_HOOK(hookMkdir, origMkdir,
              (const char *path, mode_t mode), (effective, mode))
PATH_INT_HOOK(hookMkdirat, origMkdirat,
              (int dirfd, const char *path, mode_t mode), (dirfd, effective, mode))
PATH_INT_HOOK(hookRmdir, origRmdir,
              (const char *path), (effective))
PATH_INT_HOOK(hookChdir, origChdir,
              (const char *path), (effective))

int hookRename(const char *oldPath, const char *newPath) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string oldStorage, newStorage;
    const char *oldEffective = translated(oldPath, oldStorage);
    const char *newEffective = translated(newPath, newStorage);
    errno = incomingErrno;
    return origRename(oldEffective, newEffective);
}

int hookRenameat(int oldDirfd, const char *oldPath, int newDirfd, const char *newPath) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string oldStorage, newStorage;
    const char *oldEffective = translated(oldPath, oldStorage);
    const char *newEffective = translated(newPath, newStorage);
    errno = incomingErrno;
    return origRenameat(oldDirfd, oldEffective, newDirfd, newEffective);
}

DIR *hookOpendir(const char *path) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return origOpendir(effective);
}

char *hookGetcwd(char *buffer, size_t capacity) {
    HookScope scope;
    char *result = origGetcwd(buffer, capacity);
    if (result == nullptr || hookDepth != 1) return result;
    const int resultErrno = errno;
    std::string logical;
    if (!IO::restorePath(result, logical)) {
        errno = resultErrno;
        return result;
    }
    if (buffer == nullptr) {
        char *replacement = strdup(logical.c_str());
        if (replacement == nullptr) {
            free(result);
            errno = ENOMEM;
            return nullptr;
        }
        free(result);
        errno = resultErrno;
        return replacement;
    }
    if (logical.size() + 1 > capacity) {
        errno = ERANGE;
        return nullptr;
    }
    std::memcpy(buffer, logical.c_str(), logical.size() + 1);
    errno = resultErrno;
    return buffer;
}

int hookSymlink(const char *target, const char *linkPath) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string targetStorage, linkStorage;
    const char *effectiveTarget = translated(target, targetStorage);
    const char *effectiveLink = translated(linkPath, linkStorage);
    errno = incomingErrno;
    return origSymlink(effectiveTarget, effectiveLink);
}

int hookSymlinkat(const char *target, int dirfd, const char *linkPath) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string targetStorage, linkStorage;
    const char *effectiveTarget = translated(target, targetStorage);
    const char *effectiveLink = translated(linkPath, linkStorage);
    errno = incomingErrno;
    return origSymlinkat(effectiveTarget, dirfd, effectiveLink);
}

char *hookRealpath(const char *path, char *resolved) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    char *result = origRealpath(effective, resolved);
    if (result == nullptr || hookDepth != 1) return result;
    const int resultErrno = errno;
    std::string logical;
    if (!IO::restorePath(result, logical)) {
        errno = resultErrno;
        return result;
    }
    if (resolved == nullptr) {
        char *replacement = strdup(logical.c_str());
        if (replacement == nullptr) {
            free(result);
            errno = ENOMEM;
            return nullptr;
        }
        free(result);
        errno = resultErrno;
        return replacement;
    }
    if (logical.size() >= PATH_MAX) {
        errno = ENAMETOOLONG;
        return nullptr;
    }
    std::memcpy(resolved, logical.c_str(), logical.size() + 1);
    errno = resultErrno;
    return resolved;
}

ssize_t restoreLink(char *buffer, size_t capacity, ssize_t length) {
    if (length < 0 || static_cast<size_t>(length) >= capacity || hookDepth != 1) return length;
    const int resultErrno = errno;
    std::string target(buffer, static_cast<size_t>(length));
    std::string logical;
    if (!IO::restorePath(target.c_str(), logical)) {
        errno = resultErrno;
        return length;
    }
    const size_t copied = logical.size() < capacity ? logical.size() : capacity;
    std::memcpy(buffer, logical.data(), copied);
    errno = resultErrno;
    return static_cast<ssize_t>(copied);
}

ssize_t hookReadlink(const char *path, char *buffer, size_t capacity) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return restoreLink(buffer, capacity, origReadlink(effective, buffer, capacity));
}

ssize_t hookReadlinkat(int dirfd, const char *path, char *buffer, size_t capacity) {
    HookScope scope;
    const int incomingErrno = errno;
    std::string storage;
    const char *effective = translated(path, storage);
    errno = incomingErrno;
    return restoreLink(buffer, capacity,
                       origReadlinkat(dirfd, effective, buffer, capacity));
}
} // namespace

void FileSystemHook::init() {
    std::lock_guard<std::mutex> lock(installMutex);
    if (installed) return;
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (handle == nullptr) {
        ALOGE("FileSystemHook: failed to open libc.so");
        return;
    }
    hookSymbol(handle, "open", reinterpret_cast<void *>(hookOpen), origOpen);
    hookSymbol(handle, "open64", reinterpret_cast<void *>(hookOpen64), origOpen64);
    hookSymbol(handle, "__open_2", reinterpret_cast<void *>(hookOpen2), origOpen2);
    hookSymbol(handle, "openat", reinterpret_cast<void *>(hookOpenat), origOpenat);
    hookSymbol(handle, "openat64", reinterpret_cast<void *>(hookOpenat64), origOpenat64);
    hookSymbol(handle, "__openat_2", reinterpret_cast<void *>(hookOpenat2), origOpenat2);
    hookSymbol(handle, "stat", reinterpret_cast<void *>(hookStat), origStat);
    hookSymbol(handle, "lstat", reinterpret_cast<void *>(hookLstat), origLstat);
    hookSymbol(handle, "fstatat", reinterpret_cast<void *>(hookFstatat), origFstatat);
    hookSymbol(handle, "newfstatat", reinterpret_cast<void *>(hookNewfstatat), origNewfstatat);
    hookSymbol(handle, "access", reinterpret_cast<void *>(hookAccess), origAccess);
    hookSymbol(handle, "faccessat", reinterpret_cast<void *>(hookFaccessat), origFaccessat);
    hookSymbol(handle, "realpath", reinterpret_cast<void *>(hookRealpath), origRealpath);
    hookSymbol(handle, "readlink", reinterpret_cast<void *>(hookReadlink), origReadlink);
    hookSymbol(handle, "readlinkat", reinterpret_cast<void *>(hookReadlinkat), origReadlinkat);
    hookSymbol(handle, "rename", reinterpret_cast<void *>(hookRename), origRename);
    hookSymbol(handle, "renameat", reinterpret_cast<void *>(hookRenameat), origRenameat);
    hookSymbol(handle, "unlink", reinterpret_cast<void *>(hookUnlink), origUnlink);
    hookSymbol(handle, "unlinkat", reinterpret_cast<void *>(hookUnlinkat), origUnlinkat);
    hookSymbol(handle, "mkdir", reinterpret_cast<void *>(hookMkdir), origMkdir);
    hookSymbol(handle, "mkdirat", reinterpret_cast<void *>(hookMkdirat), origMkdirat);
    hookSymbol(handle, "rmdir", reinterpret_cast<void *>(hookRmdir), origRmdir);
    hookSymbol(handle, "opendir", reinterpret_cast<void *>(hookOpendir), origOpendir);
    hookSymbol(handle, "chdir", reinterpret_cast<void *>(hookChdir), origChdir);
    hookSymbol(handle, "getcwd", reinterpret_cast<void *>(hookGetcwd), origGetcwd);
    hookSymbol(handle, "symlink", reinterpret_cast<void *>(hookSymlink), origSymlink);
    hookSymbol(handle, "symlinkat", reinterpret_cast<void *>(hookSymlinkat), origSymlinkat);
    installed = !installedAddresses.empty();
    xdl_close(handle);
}
