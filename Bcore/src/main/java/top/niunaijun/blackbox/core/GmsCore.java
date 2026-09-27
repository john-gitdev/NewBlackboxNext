package top.niunaijun.blackbox.core;

import android.content.pm.Signature;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackage;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;


public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    private static void uninstallPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            blackBoxCore.uninstallPackageAsUser(packageName, userId);
        }
    }

    // Google's services come from microG (microg.org), not copies of the phone's own
    // Google apps. Google's Play services expects to be a privileged system app, and in
    // a container every call reserved for those fails; microG runs as an ordinary app.
    // Its APKs - Services (com.google.android.gms) and Companion (com.android.vending) -
    // ship in BlackBox's assets (the downloadMicroG task in Bcore/build.gradle).
    private static final String MICROG_ASSETS = "microg";

    public static InstallResult installGApps(int userId) {
        String[] apks;
        try {
            apks = BlackBoxCore.getContext().getAssets().list(MICROG_ASSETS);
        } catch (IOException e) {
            apks = null;
        }
        if (apks == null || apks.length == 0) {
            return new InstallResult().installError("microG is missing from BlackBox's assets");
        }
        enableMicroGServices(userId);
        // Installing copies each APK into the container, so these copies are temporary.
        File unpacked = new File(BlackBoxCore.getContext().getCacheDir(), MICROG_ASSETS);
        FileUtils.mkdirs(unpacked);
        try {
            for (String name : apks) {
                File apk = new File(unpacked, name);
                try (InputStream in = BlackBoxCore.getContext().getAssets().open(MICROG_ASSETS + "/" + name)) {
                    FileUtils.writeToFile(in, apk);
                }
                InstallResult installResult = BlackBoxCore.get().installPackageAsUser(apk, userId);
                if (!installResult.success) {
                    uninstallGApps(userId);
                    return installResult;
                }
            }
        } catch (IOException e) {
            uninstallGApps(userId);
            return new InstallResult().installError("Unable to unpack microG: " + e.getMessage());
        } finally {
            FileUtils.deleteDir(unpacked);
        }
        return new InstallResult();
    }

    // A fresh microG has Google device registration and Cloud Messaging off, and push
    // notifications need both. Written before microG is installed, so the boot broadcast
    // its install sends already finds them on; users can still switch them off in
    // microG's settings. An existing settings file is left as it is.
    private static void enableMicroGServices(int userId) {
        File prefs = new File(BEnvironment.getDataDir(GMS_PKG, userId),
                "shared_prefs/" + GMS_PKG + "_preferences.xml");
        if (prefs.exists()) {
            return;
        }
        FileUtils.mkdirs(prefs.getParentFile());
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<map>\n"
                + "    <boolean name=\"checkin_enable_service\" value=\"true\" />\n"
                + "    <boolean name=\"gcm_enable_mcs_service\" value=\"true\" />\n"
                + "</map>\n";
        try {
            FileUtils.writeToFile(xml.getBytes(StandardCharsets.UTF_8), prefs);
        } catch (IOException e) {
            Slog.w(TAG, "Unable to preset microG's settings", e);
        }
    }

    // microG's signing certificate (SHA-256). Only packages signed with it may present
    // another certificate, as ROMs that support signature spoofing restrict it.
    private static final String MICROG_CERT_SHA256 =
            "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165";
    private static final String FAKE_PACKAGE_SIGNATURE = "android.permission.FAKE_PACKAGE_SIGNATURE";

    // The certificate a microG package asks to be seen with - Google's, so apps accept it
    // as Play services - or null if the package isn't microG or asks for none.
    public static Signature[] getFakeSignatures(BPackage p) {
        if (p.mAppMetaData == null || p.requestedPermissions == null
                || !p.requestedPermissions.contains(FAKE_PACKAGE_SIGNATURE)) {
            return null;
        }
        String fake = p.mAppMetaData.getString("fake-signature");
        if (fake == null || !isSignedBy(p.mSignatures, MICROG_CERT_SHA256)) {
            return null;
        }
        try {
            return new Signature[]{new Signature(fake)};
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Bad fake-signature in " + p.packageName);
            return null;
        }
    }

    private static boolean isSignedBy(Signature[] signatures, String sha256) {
        if (signatures == null || signatures.length != 1) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equals(sha256);
        } catch (Exception e) {
            return false;
        }
    }

    public static void uninstallGApps(int userId) {
        uninstallPackages(GOOGLE_SERVICE, userId);
        uninstallPackages(GOOGLE_APP, userId);
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }


    // microG doesn't depend on the phone having Google's apps.
    public static boolean isSupportGms() {
        return true;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }
}