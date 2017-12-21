/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.Preference.OnPreferenceClickListener;
import android.support.v7.preference.PreferenceScreen;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Switch;
import android.widget.TextView;

import com.android.ims.ImsConfig;
import com.android.ims.ImsManager;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.telephony.Phone;
import com.android.settings.widget.SwitchBar;
import org.codeaurora.ims.QtiCallConstants;

/**
 * "Wi-Fi Calling settings" screen.  This preference screen lets you
 * enable/disable Wi-Fi Calling and change Wi-Fi Calling mode.
 */
public class WifiCallingSettings extends SettingsPreferenceFragment
        implements SwitchBar.OnSwitchChangeListener,
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "WifiCallingSettings";

    //String keys for preference lookup
    private static final String BUTTON_WFC_MODE = "wifi_calling_mode";
    private static final String BUTTON_WFC_ROAMING_MODE = "wifi_calling_roaming_mode";
    private static final String PREFERENCE_EMERGENCY_ADDRESS = "emergency_address_key";

    private static final int REQUEST_CHECK_WFC_EMERGENCY_ADDRESS = 1;

    public static final String EXTRA_LAUNCH_CARRIER_APP = "EXTRA_LAUNCH_CARRIER_APP";

    public static final int LAUCH_APP_ACTIVATE = 0;
    public static final int LAUCH_APP_UPDATE = 1;

    //UI objects
    private SwitchBar mSwitchBar;
    private Switch mSwitch;
    private ListPreference mButtonWfcMode;
    private ListPreference mButtonWfcRoamingMode;
    private Preference mUpdateAddress;
    private TextView mEmptyView;

    private int[] mCallState = null;
    private PhoneStateListener[] mPhoneStateListener = null;
    private boolean mValidListener = false;
    private boolean mEditableWfcMode = true;
    private boolean mEditableWfcRoamingMode = true;
    private final int DEFAULT_PHONE_ID = 0;
    private int mPhoneId = DEFAULT_PHONE_ID;
    private ImsManager mImsMgr = null;

    private final OnPreferenceClickListener mUpdateAddressListener =
            new OnPreferenceClickListener() {
                /*
                 * Launch carrier emergency address managemnent activity
                 */
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    final Context context = getActivity();
                    Intent carrierAppIntent = getCarrierActivityIntent(context);
                    if (carrierAppIntent != null) {
                        carrierAppIntent.putExtra(EXTRA_LAUNCH_CARRIER_APP, LAUCH_APP_UPDATE);
                        startActivity(carrierAppIntent);
                    }
                    return true;
                }
    };

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final SettingsActivity activity = (SettingsActivity) getActivity();

        mSwitchBar = activity.getSwitchBar();
        mSwitch = mSwitchBar.getSwitch();
        mSwitchBar.show();

        mEmptyView = (TextView) getView().findViewById(android.R.id.empty);
        setEmptyView(mEmptyView);
        String emptyViewText = activity.getString(R.string.wifi_calling_off_explanation)
                + activity.getString(R.string.wifi_calling_off_explanation_2);
        mEmptyView.setText(emptyViewText);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSwitchBar.hide();
    }

    private void showAlert(Intent intent) {
        Context context = getActivity();

        CharSequence title = intent.getCharSequenceExtra(Phone.EXTRA_KEY_ALERT_TITLE);
        CharSequence message = intent.getCharSequenceExtra(Phone.EXTRA_KEY_ALERT_MESSAGE);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setMessage(message)
                .setTitle(title)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(android.R.string.ok, null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private IntentFilter mIntentFilter;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ImsManager.ACTION_IMS_REGISTRATION_ERROR)) {
                // If this fragment is active then we are immediately
                // showing alert on screen. There is no need to add
                // notification in this case.
                //
                // In order to communicate to ImsPhone that it should
                // not show notification, we are changing result code here.
                setResultCode(Activity.RESULT_CANCELED);

                showAlert(intent);
            }
        }
    };

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.WIFI_CALLING;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.wifi_calling_settings);

        mButtonWfcMode = (ListPreference) findPreference(BUTTON_WFC_MODE);
        mButtonWfcMode.setOnPreferenceChangeListener(this);

        mButtonWfcRoamingMode = (ListPreference) findPreference(BUTTON_WFC_ROAMING_MODE);
        mButtonWfcRoamingMode.setOnPreferenceChangeListener(this);

        mUpdateAddress = (Preference) findPreference(PREFERENCE_EMERGENCY_ADDRESS);
        mUpdateAddress.setOnPreferenceClickListener(mUpdateAddressListener);

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(ImsManager.ACTION_IMS_REGISTRATION_ERROR);
        mPhoneId = getIntent().getIntExtra(QtiCallConstants.EXTRA_PHONE_ID, DEFAULT_PHONE_ID);
        CarrierConfigManager configManager = (CarrierConfigManager)
                getSystemService(Context.CARRIER_CONFIG_SERVICE);
        boolean isWifiOnlySupported = true;
        if (configManager != null) {
            PersistableBundle b = configManager.getConfigForSubId(getSubscriptionId());
            if (b != null) {
                mEditableWfcMode = b.getBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL);
                mEditableWfcRoamingMode = b.getBoolean(
                        CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL);
                isWifiOnlySupported = b.getBoolean(
                        CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, true);
            }
        }

        if (!isWifiOnlySupported) {
            mButtonWfcMode.setEntries(R.array.wifi_calling_mode_choices_without_wifi_only);
            mButtonWfcMode.setEntryValues(R.array.wifi_calling_mode_values_without_wifi_only);
            mButtonWfcRoamingMode.setEntries(
                    R.array.wifi_calling_mode_choices_v2_without_wifi_only);
            mButtonWfcRoamingMode.setEntryValues(
                    R.array.wifi_calling_mode_values_without_wifi_only);
        }

        mImsMgr = ImsManager.getInstance(getActivity(), mPhoneId);
        mPhoneStateListener = new PhoneStateListener[TelephonyManager.getDefault().getPhoneCount()];
        mCallState = new int[mPhoneStateListener.length];
    }


    private int getSubscriptionId() {
        SubscriptionManager subscriptionManager = SubscriptionManager.from(getActivity());
        if (subscriptionManager == null) {
            return subscriptionManager.INVALID_SUBSCRIPTION_ID;
        }

        SubscriptionInfo subInfo = subscriptionManager.
                getActiveSubscriptionInfoForSimSlotIndex(mPhoneId);
        if (subInfo == null) {
            return subscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        return subInfo.getSubscriptionId();
    }

    @Override
    public void onResume() {
        super.onResume();

        final Context context = getActivity();

        // NOTE: Buttons will be enabled/disabled in mPhoneStateListener
        boolean wfcEnabled = mImsMgr.isWfcEnabledByUserForSlot()
                && mImsMgr.isNonTtyOrTtyOnVolteEnabledForSlot();
        mSwitch.setChecked(wfcEnabled);
        int wfcMode = mImsMgr.getWfcModeForSlot(false);
        int wfcRoamingMode = mImsMgr.getWfcModeForSlot(true);
        mButtonWfcMode.setValue(Integer.toString(wfcMode));
        mButtonWfcRoamingMode.setValue(Integer.toString(wfcRoamingMode));
        updateButtonWfcMode(context, wfcEnabled, wfcMode, wfcRoamingMode);

        if (mImsMgr.isWfcEnabledByPlatformForSlot()) {
            registerPhoneStateListeners(context);

            mSwitchBar.addOnSwitchChangeListener(this);

            mValidListener = true;
        }

        context.registerReceiver(mIntentReceiver, mIntentFilter);

        Intent intent = getActivity().getIntent();
        if (intent.getBooleanExtra(Phone.EXTRA_KEY_ALERT_SHOW, false)) {
            showAlert(intent);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        final Context context = getActivity();

        if (mValidListener) {
            mValidListener = false;

            unRegisterPhoneStateListeners(context);

            mSwitchBar.removeOnSwitchChangeListener(this);
        }

        context.unregisterReceiver(mIntentReceiver);
    }

    private void registerPhoneStateListeners(Context context) {
        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        SubscriptionManager subMgr = SubscriptionManager.from(getActivity());
        if (tm == null || subMgr == null) {
            Log.e(TAG, "TelephonyManager or SubscriptionManager is null");
            return;
        }

        for (int i = 0; i < mPhoneStateListener.length; i++) {
            final SubscriptionInfo subInfo =
                    subMgr.getActiveSubscriptionInfoForSimSlotIndex(i);
            if (subInfo == null) {
                Log.e(TAG, "registerPhoneStateListener subInfo : " + subInfo +
                        " for phone Id: " + i);
                continue;
            }

            final int phoneId = i;
            /*
            * Enable/disable controls when in/out of a call and depending on
            * TTY mode and TTY support over VoLTE.
            * @see android.telephony.PhoneStateListener#onCallStateChanged(int,
            * java.lang.String)
            */
            mPhoneStateListener[i]  = new PhoneStateListener(subInfo.getSubscriptionId()) {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    Log.d(TAG, "PhoneStateListener onCallStateChanged: state is " + state +
                            " SubId: " + mSubId);
                    final SettingsActivity activity = (SettingsActivity) getActivity();
                    if (activity == null) {
                        return;
                    }
                    boolean isNonTtyOrTtyOnVolteEnabled =
                            mImsMgr.isNonTtyOrTtyOnVolteEnabledForSlot();
                    final SwitchBar switchBar = activity.getSwitchBar();
                    boolean isWfcEnabled = switchBar.getSwitch().isChecked()
                            && isNonTtyOrTtyOnVolteEnabled;

                    mCallState[phoneId] = state;
                    switchBar.setEnabled(isCallStateIdle() && isNonTtyOrTtyOnVolteEnabled);

                    boolean isWfcModeEditable = true;
                    boolean isWfcRoamingModeEditable = false;
                    final CarrierConfigManager configManager = (CarrierConfigManager)
                            activity.getSystemService(Context.CARRIER_CONFIG_SERVICE);
                    if (configManager != null) {
                        PersistableBundle b = configManager.getConfigForSubId(getSubscriptionId());
                        if (b != null) {
                            isWfcModeEditable = b.getBoolean(
                                    CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL);
                            isWfcRoamingModeEditable = b.getBoolean(
                                    CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL);
                        }
                    }

                    Preference pref = getPreferenceScreen().findPreference(BUTTON_WFC_MODE);
                    if (pref != null) {
                        pref.setEnabled(isWfcEnabled && isWfcModeEditable && isCallStateIdle());
                    }
                    Preference pref_roam = getPreferenceScreen().
                            findPreference(BUTTON_WFC_ROAMING_MODE);
                    if (pref_roam != null) {
                        pref_roam.setEnabled(isWfcEnabled && isWfcRoamingModeEditable &&
                                isCallStateIdle());
                    }
                }
            };
            Log.d(TAG, "Register for call state change for phone Id: " + i);
            tm.listen(mPhoneStateListener[i], PhoneStateListener.LISTEN_CALL_STATE);
        }
    }

    private void unRegisterPhoneStateListeners(Context context) {
        TelephonyManager tm =
               (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        for (int i = 0; i < mPhoneStateListener.length; i++) {
            if (mPhoneStateListener[i] != null) {
                Log.d(TAG, "unRegister for call state change for phone Id: " + i);
                tm.listen(mPhoneStateListener[i], PhoneStateListener.LISTEN_NONE);
                mPhoneStateListener[i] = null;
            }
        }
    }

    /**
     * Listens to the state change of the switch.
     */
    @Override
    public void onSwitchChanged(Switch switchView, boolean isChecked) {
        final Context context = getActivity();
        Log.d(TAG, "onSwitchChanged(" + isChecked + ")");

        if (!isChecked) {
            updateWfcMode(context, false);
            return;
        }

        // Call address management activity before turning on WFC
        Intent carrierAppIntent = getCarrierActivityIntent(context);
        if (carrierAppIntent != null) {
            carrierAppIntent.putExtra(EXTRA_LAUNCH_CARRIER_APP, LAUCH_APP_ACTIVATE);
            startActivityForResult(carrierAppIntent, REQUEST_CHECK_WFC_EMERGENCY_ADDRESS);
        } else {
            updateWfcMode(context, true);
        }
    }

    /*
     * Get the Intent to launch carrier emergency address management activity.
     * Return null when no activity found.
     */
    private static Intent getCarrierActivityIntent(Context context) {
        // Retrive component name from carrirt config
        CarrierConfigManager configManager = context.getSystemService(CarrierConfigManager.class);
        if (configManager == null) return null;

        PersistableBundle bundle = configManager.getConfig();
        if (bundle == null) return null;

        String carrierApp = bundle.getString(
                CarrierConfigManager.KEY_WFC_EMERGENCY_ADDRESS_CARRIER_APP_STRING);
        if (TextUtils.isEmpty(carrierApp)) return null;

        ComponentName componentName = ComponentName.unflattenFromString(carrierApp);
        if (componentName == null) return null;

        // Build and return intent
        Intent intent = new Intent();
        intent.setComponent(componentName);
        return intent;
    }

    /*
     * Turn on/off WFC mode with ImsManager and update UI accordingly
     */
    private void updateWfcMode(Context context, boolean wfcEnabled) {
        Log.i(TAG, "updateWfcMode(" + wfcEnabled + ")");
        mImsMgr.setWfcSettingForSlot(wfcEnabled);

        int wfcMode = mImsMgr.getWfcModeForSlot(false);
        int wfcRoamingMode = mImsMgr.getWfcModeForSlot(true);
        updateButtonWfcMode(context, wfcEnabled, wfcMode, wfcRoamingMode);
        if (wfcEnabled) {
            mMetricsFeatureProvider.action(getActivity(), getMetricsCategory(), wfcMode);
        } else {
            mMetricsFeatureProvider.action(getActivity(), getMetricsCategory(), -1);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        final Context context = getActivity();

        if (requestCode == REQUEST_CHECK_WFC_EMERGENCY_ADDRESS) {
            Log.d(TAG, "WFC emergency address activity result = " + resultCode);

            if (resultCode == Activity.RESULT_OK) {
                updateWfcMode(context, true);
            }
        }
    }

    private void updateButtonWfcMode(Context context, boolean wfcEnabled,
                                     int wfcMode, int wfcRoamingMode) {
        mButtonWfcMode.setSummary(getWfcModeSummaryForSlot(context, wfcMode));
        mButtonWfcMode.setEnabled(wfcEnabled && mEditableWfcMode);
        // mButtonWfcRoamingMode.setSummary is not needed; summary is just selected value.
        mButtonWfcRoamingMode.setEnabled(wfcEnabled && mEditableWfcRoamingMode);

        final PreferenceScreen preferenceScreen = getPreferenceScreen();
        boolean updateAddressEnabled = (getCarrierActivityIntent(context) != null);
        if (wfcEnabled) {
            if (mEditableWfcMode) {
                preferenceScreen.addPreference(mButtonWfcMode);
            } else {
                // Don't show WFC (home) preference if it's not editable.
                preferenceScreen.removePreference(mButtonWfcMode);
            }
            if (mEditableWfcRoamingMode) {
                preferenceScreen.addPreference(mButtonWfcRoamingMode);
            } else {
                // Don't show WFC roaming preference if it's not editable.
                preferenceScreen.removePreference(mButtonWfcRoamingMode);
            }
            if (updateAddressEnabled) {
                preferenceScreen.addPreference(mUpdateAddress);
            } else {
                preferenceScreen.removePreference(mUpdateAddress);
            }
        } else {
            preferenceScreen.removePreference(mButtonWfcMode);
            preferenceScreen.removePreference(mButtonWfcRoamingMode);
            preferenceScreen.removePreference(mUpdateAddress);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getActivity();
        if (preference == mButtonWfcMode) {
            mButtonWfcMode.setValue((String) newValue);
            int buttonMode = Integer.valueOf((String) newValue);
            int currentWfcMode = mImsMgr.getWfcModeForSlot(false);
            if (buttonMode != currentWfcMode) {
                mImsMgr.setWfcModeForSlot(buttonMode, false);
                mButtonWfcMode.setSummary(getWfcModeSummaryForSlot(context, buttonMode));
                mMetricsFeatureProvider.action(getActivity(), getMetricsCategory(), buttonMode);
            }
            if (!mEditableWfcRoamingMode) {
                int currentWfcRoamingMode = mImsMgr.getWfcModeForSlot(true);
                if (buttonMode != currentWfcRoamingMode) {
                    mImsMgr.setWfcModeForSlot(buttonMode, true);
                    // mButtonWfcRoamingMode.setSummary is not needed; summary is selected value
                }
            }
        } else if (preference == mButtonWfcRoamingMode) {
            mButtonWfcRoamingMode.setValue((String) newValue);
            int buttonMode = Integer.valueOf((String) newValue);
            int currentMode = mImsMgr.getWfcModeForSlot(true);
            if (buttonMode != currentMode) {
                mImsMgr.setWfcModeForSlot(buttonMode, true);
                // mButtonWfcRoamingMode.setSummary is not needed; summary is just selected value.
                mMetricsFeatureProvider.action(getActivity(), getMetricsCategory(), buttonMode);
            }
        }
        return true;
    }

    public static int getWfcModeSummary(Context context, int wfcMode) {
        int resId = com.android.internal.R.string.wifi_calling_off_summary;
        if (ImsManager.isWfcEnabledByUser(context)) {
            switch (wfcMode) {
                case ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY:
                    resId = com.android.internal.R.string.wfc_mode_wifi_only_summary;
                    break;
                case ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED:
                    resId = com.android.internal.R.string.wfc_mode_cellular_preferred_summary;
                    break;
                case ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED:
                    resId = com.android.internal.R.string.wfc_mode_wifi_preferred_summary;
                    break;
                default:
                    Log.e(TAG, "Unexpected WFC mode value: " + wfcMode);
            }
        }
        return resId;
    }

    public int getWfcModeSummaryForSlot(Context context, int wfcMode) {
        int resId = com.android.internal.R.string.wifi_calling_off_summary;
        if (mImsMgr.isWfcEnabledByUserForSlot()) {
            switch (wfcMode) {
                case ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY:
                    resId = com.android.internal.R.string.wfc_mode_wifi_only_summary;
                    break;
                case ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED:
                    resId = com.android.internal.R.string.wfc_mode_cellular_preferred_summary;
                    break;
                case ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED:
                    resId = com.android.internal.R.string.wfc_mode_wifi_preferred_summary;
                    break;
                default:
                    Log.e(TAG, "Unexpected WFC mode value: " + wfcMode);
            }
        }
        return resId;
    }

    private boolean isCallStateIdle() {
        for (int i = 0; i < mCallState.length; i++) {
            if (TelephonyManager.CALL_STATE_IDLE != mCallState[i]) {
                return false;
            }
        }
        return true;
    }
}
