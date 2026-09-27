package top.niunaijun.blackbox.app;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.accounts.IAccountManagerResponse;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.R;
import top.niunaijun.blackbox.core.system.accounts.IBAccountManagerService;
import top.niunaijun.blackbox.fake.frameworks.BAccountManager;
import top.niunaijun.blackbox.utils.Slog;

// Stands in for Android's account picker when a guest asks for it. Android's runs outside
// the container and lists the phone's own accounts - a guest was offered the owner's real
// Google account. This one lists the container user's accounts and adds new ones through
// the container's authenticators (microG's Google sign-in, for one). Like Android's, it
// answers RESULT_OK with KEY_ACCOUNT_NAME and KEY_ACCOUNT_TYPE, and picking an account
// makes it visible to the app that asked.
public class ChooseAccountActivity extends Activity {
    public static final String TAG = "ChooseAccountActivity";

    private static final String ANDROID_PICKER = "android.accounts.ChooseTypeAndAccountActivity";
    private static final String EXTRA_USER_ID = "_B_|_account_user_id_";
    private static final String EXTRA_CALLING_PACKAGE = "_B_|_account_calling_package_";

    // Android's, as AccountManager.newChooseAccountIntent fills them in.
    private static final String EXTRA_ALLOWABLE_ACCOUNTS = "allowableAccounts";
    private static final String EXTRA_ALLOWABLE_ACCOUNT_TYPES = "allowableAccountTypes";
    private static final String EXTRA_ADD_ACCOUNT_OPTIONS = "addAccountOptions";
    private static final String EXTRA_ADD_ACCOUNT_REQUIRED_FEATURES = "addAccountRequiredFeatures";
    private static final String EXTRA_AUTH_TOKEN_TYPE = "authTokenType";
    private static final String EXTRA_DESCRIPTION_OVERRIDE = "descriptionTextOverride";

    // For the guest's startActivity hook: sends a request for Android's picker here
    // instead. True if the intent was one.
    public static boolean redirect(Intent intent, int userId, String callingPackage) {
        ComponentName component = intent.getComponent();
        if (component == null || !"android".equals(component.getPackageName())
                || !ANDROID_PICKER.equals(component.getClassName())) {
            return false;
        }
        intent.setComponent(new ComponentName(BlackBoxCore.getHostPkg(), ChooseAccountActivity.class.getName()));
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra(EXTRA_CALLING_PACKAGE, callingPackage);
        return true;
    }

    private int mUserId;
    private String mCallingPackage;
    private Set<String> mAllowedTypes;
    private List<Account> mAllowedAccounts;
    private AlertDialog mDialog;
    // Set while an authenticator's sign-in runs; a single new account then answers at once.
    private Set<Account> mAccountsBeforeAdding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mUserId = intent.getIntExtra(EXTRA_USER_ID, 0);
        mCallingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE);
        String[] types = intent.getStringArrayExtra(EXTRA_ALLOWABLE_ACCOUNT_TYPES);
        mAllowedTypes = types == null ? null : new LinkedHashSet<>(Arrays.asList(types));
        mAllowedAccounts = intent.getParcelableArrayListExtra(EXTRA_ALLOWABLE_ACCOUNTS);
        if (mCallingPackage == null) {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        List<Account> accounts = candidates();
        if (mAccountsBeforeAdding != null) {
            List<Account> added = new ArrayList<>(accounts);
            added.removeAll(mAccountsBeforeAdding);
            mAccountsBeforeAdding = null;
            if (added.size() == 1) {
                choose(added.get(0));
                return;
            }
        }
        show(accounts);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    private void show(List<Account> accounts) {
        List<String> rows = new ArrayList<>();
        for (Account account : accounts) {
            rows.add(account.name);
        }
        Set<String> addable = addableTypes();
        Slog.d(TAG, mCallingPackage + " (user " + mUserId + "): offering " + accounts.size()
                + " account(s), allowed types " + mAllowedTypes + ", can add " + addable);
        boolean canAdd = !addable.isEmpty();
        if (canAdd) {
            rows.add(getString(R.string.choose_account_add));
        }
        String title = getIntent().getStringExtra(EXTRA_DESCRIPTION_OVERRIDE);
        mDialog = new AlertDialog.Builder(this, dialogTheme())
                .setTitle(title != null ? title : getString(R.string.choose_account_title))
                .setItems(rows.toArray(new String[0]), (dialog, which) -> {
                    if (which < accounts.size()) {
                        choose(accounts.get(which));
                    } else {
                        addAccount(accounts);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> cancel())
                .setOnCancelListener(dialog -> cancel())
                .show();
    }

    private void choose(Account account) {
        try {
            service().setAccountVisibility(account, mCallingPackage, AccountManager.VISIBILITY_VISIBLE, mUserId);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to make " + account.type + " account visible to " + mCallingPackage, e);
        }
        Intent result = new Intent();
        result.putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name);
        result.putExtra(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        setResult(RESULT_OK, result);
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    // The container user's accounts the app may be offered, in the app's constraints.
    private List<Account> candidates() {
        List<Account> accounts = new ArrayList<>();
        Set<String> types = mAllowedTypes != null ? mAllowedTypes : addableTypes();
        for (String type : types) {
            try {
                for (Account account : service().getAccountsAsUser(type, mUserId)) {
                    if (mAllowedAccounts == null || mAllowedAccounts.contains(account)) {
                        accounts.add(account);
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Unable to list " + type + " accounts", e);
            }
        }
        return accounts;
    }

    // Account types an authenticator in the container can add, within the app's allowed types.
    private Set<String> addableTypes() {
        Set<String> types = new LinkedHashSet<>();
        try {
            AuthenticatorDescription[] authenticators = service().getAuthenticatorTypes(mUserId);
            if (authenticators != null) {
                for (AuthenticatorDescription authenticator : authenticators) {
                    if (mAllowedTypes == null || mAllowedTypes.contains(authenticator.type)) {
                        types.add(authenticator.type);
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to list authenticators", e);
        }
        return types;
    }

    private void addAccount(List<Account> current) {
        List<String> types = new ArrayList<>(addableTypes());
        if (types.size() == 1) {
            addAccount(types.get(0), current);
            return;
        }
        mDialog = new AlertDialog.Builder(this, dialogTheme())
                .setTitle(R.string.choose_account_add)
                .setItems(types.toArray(new String[0]), (dialog, which) -> addAccount(types.get(which), current))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> show(current))
                .setOnCancelListener(dialog -> show(current))
                .show();
    }

    private void addAccount(String type, List<Account> current) {
        Intent intent = getIntent();
        IAccountManagerResponse response = new IAccountManagerResponse.Stub() {
            @Override
            public void onResult(Bundle result) {
                runOnUiThread(() -> onAddAccountResult(result, current));
            }

            @Override
            public void onError(int errorCode, String errorMessage) {
                Slog.w(TAG, "Adding a " + type + " account failed: " + errorCode + " " + errorMessage);
                runOnUiThread(() -> show(candidates()));
            }
        };
        try {
            service().addAccount(response, type,
                    intent.getStringExtra(EXTRA_AUTH_TOKEN_TYPE),
                    intent.getStringArrayExtra(EXTRA_ADD_ACCOUNT_REQUIRED_FEATURES),
                    true,
                    intent.getBundleExtra(EXTRA_ADD_ACCOUNT_OPTIONS),
                    mUserId);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to add a " + type + " account", e);
            show(current);
        }
    }

    private void onAddAccountResult(Bundle result, List<Account> current) {
        if (result == null) {
            show(candidates());
            return;
        }
        // The authenticator's sign-in screen, a guest activity; the new account shows up
        // when this activity resumes.
        Intent signIn = result.getParcelable(AccountManager.KEY_INTENT);
        if (signIn != null) {
            mAccountsBeforeAdding = new LinkedHashSet<>(current);
            BlackBoxCore.getBActivityManager().startActivity(signIn, mUserId);
            return;
        }
        String name = result.getString(AccountManager.KEY_ACCOUNT_NAME);
        String type = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
        if (name != null && type != null) {
            choose(new Account(name, type));
        } else {
            show(candidates());
        }
    }

    private int dialogTheme() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private static IBAccountManagerService service() {
        return BAccountManager.get().getService();
    }
}
