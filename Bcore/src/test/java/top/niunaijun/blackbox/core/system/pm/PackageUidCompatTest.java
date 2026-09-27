package top.niunaijun.blackbox.core.system.pm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PackageUidCompatTest {
    @Test
    public void installedPackageUsesVirtualUserZero() {
        assertEquals(10023, PackageUidCompat.forInstalledPackage(0, 10023, true));
    }

    @Test
    public void installedPackageUsesNonzeroVirtualUser() {
        assertEquals(110023, PackageUidCompat.forInstalledPackage(1, 10023, true));
        assertEquals(210023, PackageUidCompat.forInstalledPackage(2, 10023, true));
    }

    @Test
    public void absentOrUninstalledPackageHasNoUid() {
        assertEquals(-1, PackageUidCompat.forInstalledPackage(0, -1, false));
        assertEquals(-1, PackageUidCompat.forInstalledPackage(1, 10023, false));
    }
}
