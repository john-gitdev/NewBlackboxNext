package top.niunaijun.blackbox.core.system.pm.installer;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;


public class CreateUserExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        FileUtils.deleteDir(BEnvironment.getBackingDataLibDir(packageName, userId));

        
        FileUtils.mkdirs(BEnvironment.getBackingDataDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getBackingDataCacheDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getBackingDataFilesDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getBackingDataDatabasesDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDeDataDir(packageName, userId));








        return 0;
    }
}
