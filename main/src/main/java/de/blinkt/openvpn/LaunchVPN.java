/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn;

import static de.blinkt.openvpn.core.OpenVPNService.EXTRA_START_REASON;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.widget.EditText;

import de.blinkt.openvpn.minab.MinabUiBridge;

import java.io.IOException;

import de.blinkt.openvpn.api.ExternalAppDatabase;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IServiceStatus;
import de.blinkt.openvpn.core.OpenVPNStatusService;
import de.blinkt.openvpn.core.PasswordCache;
import de.blinkt.openvpn.core.Preferences;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;
import de.blinkt.openvpn.activities.LogWindow;

/**
 * This Activity actually handles two stages of a launcher shortcut's life cycle.
 * <p/>
 * 1. Your application offers to provide shortcuts to the launcher.  When
 * the user installs a shortcut, an activity within your application
 * generates the actual shortcut and returns it to the launcher, where it
 * is shown to the user as an icon.
 * <p/>
 * 2. Any time the user clicks on an installed shortcut, an intent is sent.
 * Typically this would then be handled as necessary by an activity within
 * your application.
 * <p/>
 * We handle stage 1 (creating a shortcut) by simply sending back the information (in the form
 * of an {@link android.content.Intent} that the launcher will use to create the shortcut.
 * <p/>
 * You can also implement this in an interactive way, by having your activity actually present
 * UI for the user to select the specific nature of the shortcut, such as a contact, picture, URL,
 * media item, or action.
 * <p/>
 * We handle stage 2 (responding to a shortcut) in this sample by simply displaying the contents
 * of the incoming {@link android.content.Intent}.
 * <p/>
 * In a real application, you would probably use the shortcut intent to display specific content
 * or start a particular operation.
 */
public class LaunchVPN extends Activity {

    public static final String EXTRA_KEY = "de.blinkt.openvpn.shortcutProfileUUID";
    public static final String EXTRA_NAME = "de.blinkt.openvpn.shortcutProfileName";
    public static final String EXTRA_HIDELOG = "de.blinkt.openvpn.showNoLogWindow";

    public static final String CLEARLOG = "clearlogconnect";


    private static final int START_VPN_PROFILE = 70;


    private VpnProfile mSelectedProfile;
    private boolean mhideLog = false;

    private boolean mCmfixed = false;
    private String mTransientAuthPW;
    private String mTransientCertOrPKCS12PW;
    private String mSelectedProfileReason;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.launchvpn);
        startVpnFromIntent();
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            IServiceStatus service = IServiceStatus.Stub.asInterface(binder);
            try {
                if (mTransientAuthPW != null)
                    service.setCachedPassword(mSelectedProfile.getUUIDString(), PasswordCache.AUTHPASSWORD, mTransientAuthPW);
                if (mTransientCertOrPKCS12PW != null)
                    service.setCachedPassword(mSelectedProfile.getUUIDString(), PasswordCache.PKCS12ORCERTPASSWORD, mTransientCertOrPKCS12PW);

                onActivityResult(START_VPN_PROFILE, Activity.RESULT_OK, null);

            } catch (RemoteException e) {
                e.printStackTrace();
            }

            unbindService(this);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {

        }
    };

    protected void startVpnFromIntent() {
        // Resolve the intent

        final Intent intent = getIntent();
        final String action = intent.getAction();

        // If the intent is a request to create a shortcut, we'll do that and exit
        if (!Intent.ACTION_MAIN.equals(action)) {
            return;
        }

        // Check if we need to clear the log
        if (Preferences.getDefaultSharedPreferences(this).getBoolean(CLEARLOG, true))
            VpnStatus.clearLog();

        // we got called to be the starting point, most likely a shortcut
        String shortcutUUID = intent.getStringExtra(EXTRA_KEY);
        String shortcutName = intent.getStringExtra(EXTRA_NAME);
        String startReason = intent.getStringExtra(EXTRA_START_REASON);
        mhideLog = intent.getBooleanExtra(EXTRA_HIDELOG, false);

        VpnProfile profileToConnect = ProfileManager.get(this, shortcutUUID);
        if (shortcutName != null && profileToConnect == null) {
            profileToConnect = ProfileManager.getInstance(this).getProfileByName(shortcutName);
            if (!(new ExternalAppDatabase(this).checkRemoteActionPermission(this, getCallingPackage()))) {
                finish();
                return;
            }
        }


        if (profileToConnect == null) {
            VpnStatus.logError(R.string.shortcut_profile_notfound);
            // show Log window to display error
            showLogWindow();
            finish();
        } else {
            mSelectedProfile = profileToConnect;
            mSelectedProfileReason = startReason;
            launchVPN();
        }

    }

    private void askForPW(final int type) {
        Runnable onCancelled = () -> {
            VpnStatus.updateStateString("USER_VPN_PASSWORD_CANCELLED", "",
                    R.string.state_user_vpn_password_cancelled,
                    ConnectionStatus.LEVEL_NOTCONNECTED);
            finish();
        };

        if (type == R.string.password) {
            String initUser = mSelectedProfile.mUsername == null ? "" : mSelectedProfile.mUsername;
            String initPass = mSelectedProfile.mPassword == null ? "" : mSelectedProfile.mPassword;
            if (MinabUiBridge.showPasswordPrompt(
                    this,
                    getString(R.string.pw_request_dialog_title, getString(type)),
                    getString(R.string.pw_request_dialog_prompt, mSelectedProfile.mName),
                    initUser,
                    initPass,
                    (username, password, saveAsDefault) -> {
                        mSelectedProfile.mUsername = username;
                        mSelectedProfile.mPassword = password;
                        mSelectedProfile.addChangeLogEntry("saved password");
                        ProfileManager.saveProfile(LaunchVPN.this, mSelectedProfile);
                        Intent intent = new Intent(LaunchVPN.this, OpenVPNStatusService.class);
                        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
                    },
                    onCancelled
            )) {
                return;
            }
        } else {
            if (MinabUiBridge.showSecretPrompt(
                    this,
                    getString(R.string.pw_request_dialog_title, getString(type)),
                    getString(R.string.pw_request_dialog_prompt, mSelectedProfile.mName),
                    getString(type),
                    secret -> {
                        mTransientCertOrPKCS12PW = secret;
                        Intent intent = new Intent(LaunchVPN.this, OpenVPNStatusService.class);
                        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
                    },
                    onCancelled
            )) {
                return;
            }
        }

        // Fallback for non-UI (skeleton) flavor builds
        final EditText entry = new EditText(this);
        entry.setSingleLine();
        entry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        entry.setTransformationMethod(new PasswordTransformationMethod());
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.pw_request_dialog_title, getString(type)))
                .setMessage(getString(R.string.pw_request_dialog_prompt, mSelectedProfile.mName))
                .setView(entry)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String pw = entry.getText().toString();
                    if (type == R.string.password) {
                        mTransientAuthPW = pw;
                    } else {
                        mTransientCertOrPKCS12PW = pw;
                    }
                    Intent intent = new Intent(LaunchVPN.this, OpenVPNStatusService.class);
                    bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> onCancelled.run())
                .setOnCancelListener(d -> onCancelled.run())
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == START_VPN_PROFILE) {
            if (resultCode == Activity.RESULT_OK) {
                int needpw = mSelectedProfile.needUserPWInput(mTransientCertOrPKCS12PW, mTransientAuthPW);
                if (needpw != 0) {
                    VpnStatus.updateStateString("USER_VPN_PASSWORD", "", R.string.state_user_vpn_password,
                            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT);
                    askForPW(needpw);
                } else {
                    SharedPreferences prefs = Preferences.getDefaultSharedPreferences(this);
                    boolean showLogWindow = prefs.getBoolean("showlogwindow", true);

                    if (!mhideLog && showLogWindow)
                        showLogWindow();
                    ProfileManager.updateLRU(this, mSelectedProfile);
                    VPNLaunchHelper.startOpenVpn(mSelectedProfile, getBaseContext(), mSelectedProfileReason, true);
                    finish();
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                // User does not want us to start, so we just vanish
                VpnStatus.updateStateString("USER_VPN_PERMISSION_CANCELLED", "", R.string.state_user_vpn_permission_cancelled,
                        ConnectionStatus.LEVEL_NOTCONNECTED);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    VpnStatus.logError(R.string.nought_alwayson_warning);

                finish();
            }
        }
    }

    void showLogWindow() {
        Intent startLW = new Intent();
        startLW.setComponent(new ComponentName(this, LogWindow.class));
        startLW.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(startLW);

    }

    void showConfigErrorDialog(int vpnok) {
        if (MinabUiBridge.showConfigError(
                this,
                getString(R.string.config_error_found),
                getString(vpnok),
                this::finish
        )) {
            return;
        }
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(R.string.config_error_found)
                .setMessage(vpnok)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish());
        d.show();
    }

    void launchVPN() {
        int vpnok = mSelectedProfile.checkProfile(this);
        if (vpnok != R.string.no_error_found) {
            showConfigErrorDialog(vpnok);
            return;
        }

        Intent intent = VpnService.prepare(this);

        if (intent != null) {
            VpnStatus.updateStateString("USER_VPN_PERMISSION", "", R.string.state_user_vpn_permission,
                    ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT);
            // Start the query
            try {
                startActivityForResult(intent, START_VPN_PROFILE);
            } catch (ActivityNotFoundException ane) {
                // Shame on you Sony! At least one user reported that
                // an official Sony Xperia Arc S image triggers this exception
                VpnStatus.logError(R.string.no_vpn_support_image);
                showLogWindow();
            }
        } else {
            onActivityResult(START_VPN_PROFILE, Activity.RESULT_OK, null);
        }

    }

    private void executeSUcmd(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
            Process p = pb.start();
            int ret = p.waitFor();
            if (ret == 0)
                mCmfixed = true;
        } catch (InterruptedException | IOException e) {
            VpnStatus.logException("SU command", e);
        }
    }
}
