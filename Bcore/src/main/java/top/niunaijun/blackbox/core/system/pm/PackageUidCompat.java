package top.niunaijun.blackbox.core.system.pm;

import top.niunaijun.blackbox.core.system.user.BUserHandle;

/** The UID assigned to an installed package in one virtual Android user. */
final class PackageUidCompat {
    private PackageUidCompat() {
    }

    static int forInstalledPackage(int userId, int appId, boolean installed) {
        if (!installed || appId < 0) {
            return -1;
        }
        return BUserHandle.getUid(userId, appId);
    }
}
