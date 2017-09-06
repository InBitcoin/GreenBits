package com.greenaddress.greenbits.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.blockstream.libwally.Wally;
import com.google.common.util.concurrent.FutureCallback;
import com.greenaddress.bitid.BitID;
import com.greenaddress.bitid.BitidSignIn;
import com.greenaddress.bitid.SignInResponse;
import com.greenaddress.greenapi.ISigningWallet;
import com.greenaddress.greenapi.Network;
import com.greenaddress.greenbits.ApplicationService;
import com.greenaddress.greenbits.GaService;
import com.greenaddress.greenbits.ui.monitor.NetworkMonitorActivity;
import com.greenaddress.greenbits.ui.preferences.SettingsActivity;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.utils.MonetaryFormat;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;

import de.schildbach.wallet.ui.ScanActivity;
import com.greenaddress.greenbits.ui.preferences.TwoFactorPreferenceFragment;

// Problem with the above is that in the horizontal orientation the tabs don't go in the top bar
public class TabbedMainActivity extends GaActivity implements Observer {

    private static final String TAG = TabbedMainActivity.class.getSimpleName();

    public static final int
            REQUEST_SEND_QR_SCAN = 0,
            REQUEST_SWEEP_PRIVKEY = 1,
            REQUEST_BITCOIN_URL_LOGIN = 2,
            REQUEST_SETTINGS = 3,
            REQUEST_TX_DETAILS = 4,
            REQUEST_SEND_QR_SCAN_NO_LOGIN = 5,
            REQUEST_SEND_QR_SCAN_VENDOR = 6,
            REQUEST_CLEAR_ACTIVITY = 7,
            REQUEST_VISIU = 8,
            REQUEST_BITID_URL_LOGIN = 9;
    public static final String REQUEST_RELOAD = "request_reload";
    private ViewPager mViewPager;
    private Menu mMenu;
    private Boolean mInternalQr = false;
    private Snackbar snackbar;
    private final int mSnackbarDuration = 10 * 1000;
    private Activity mActivity;
    private MaterialDialog mSegwitDialog = null;
    private final Runnable mSegwitCB = new Runnable() { public void run() { mSegwitDialog = null; } };

    // workaround to manage only the create/onresume when is not connected
    private Boolean firstRun = true;

    private final Observer mTwoFactorObserver = new Observer() {
        @Override
        public void update(final Observable o, final Object data) {
            runOnUiThread(new Runnable() { public void run() { onTwoFactorConfigChange(); } });
        }
    };
    private final Runnable mDialogCB = new Runnable() { public void run() { setBlockWaitDialog(false); } };

    private boolean isBitcoinScheme(final Intent intent) {
        final Uri uri = intent.getData();
        return uri != null && uri.getScheme() != null && uri.getScheme().equals("bitcoin");
    }

    private boolean isBitidcheme(final Intent intent) {
        final Uri uri = intent.getData();
        return uri != null && uri.getScheme() != null && uri.getScheme().equals("bitid");
    }

    @Override
    protected void onCreateWithService(final Bundle savedInstanceState) {
        final Intent intent = getIntent();
        mActivity = this;

        mInternalQr = intent.getBooleanExtra("internal_qr", false);

        final int flags = getIntent().getFlags();
        if ((flags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            Log.d(TAG, "onCreate arrives from history, clear data and category");
            // The activity was launched from history
            // remove extras here
            intent.setData(null);
            intent.removeCategory(Intent.CATEGORY_BROWSABLE);
        }

        final boolean isBitidUri = isBitidcheme(intent);
        final boolean isBitcoinUri = (isBitcoinScheme(intent) ||
                intent.hasCategory(Intent.CATEGORY_BROWSABLE) ||
                NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) && !isBitidUri;

        if (!mService.isLoggedOrLoggingIn()) {
            // Not logged in, force the user to login
            mService.disconnect(false);
            final Intent login = new Intent(this, RequestLoginActivity.class);
            if (isBitcoinUri)
                startActivityForResult(login, REQUEST_BITCOIN_URL_LOGIN);
            else if (isBitidUri)
                startActivityForResult(login, REQUEST_BITID_URL_LOGIN);
            else
                startActivityForResult(login, REQUEST_CLEAR_ACTIVITY);
            return;
        }
        firstRun = false;

        launch(isBitcoinUri, isBitidUri);

        startService(new Intent(this, ApplicationService.class));
    }

    private void onTwoFactorConfigChange() {

        // if total amount is less then 0 BTC hide snackbar
        if (mService.getTotalBalance() == 0) {
            if (snackbar != null) {
                snackbar.dismiss();
            }
            return;
        }

        final Map<?, ?> twoFacConfig = mService.getTwoFactorConfig();
        if (twoFacConfig == null) {
            Log.d(TAG, "twoFacConfig is null");
            return;
        }

        if (mActivity == null) {
            Log.d(TAG, "mActivity is null");
            return;
        }


        if (!((Boolean) twoFacConfig.get("email_confirmed")) &&
                !mService.cfg().getBoolean("hideNoEmailWarning", false)) {
            snackbar = Snackbar
                    .make(findViewById(R.id.main_content), getString(R.string.noEmailWarning), mSnackbarDuration)
                    .setActionTextColor(Color.RED)
                    .setAction(getString(R.string.setEmail), new View.OnClickListener() {
                        @Override
                        public void onClick(final View v) {
                            final int REQUEST_ENABLE_EMAIL = 0;
                            startActivityForResult(new Intent(TabbedMainActivity.this, SetEmailActivity.class), REQUEST_ENABLE_EMAIL);
                        }
                    });

            final View snackbarView = snackbar.getView();
            snackbarView.setBackgroundColor(Color.DKGRAY);
            final TextView textView = UI.find(snackbarView, android.support.design.R.id.snackbar_text);
            textView.setTextColor(Color.WHITE);
            snackbar.show();
        } else if (!((Boolean) twoFacConfig.get("any")) &&
            !mService.cfg().getBoolean("hideTwoFacWarning", true)) {
            snackbar = Snackbar
                    .make(findViewById(R.id.main_content), getString(R.string.noTwoFactorWarning), mSnackbarDuration)
                    .setActionTextColor(Color.RED)
                    .setAction(getString(R.string.set2FA), new View.OnClickListener() {
                        @Override
                        public void onClick(final View v) {
                            Intent intent = new Intent(TabbedMainActivity.this, SettingsActivity.class);
                            intent.putExtra(SettingsActivity.EXTRA_SHOW_FRAGMENT, TwoFactorPreferenceFragment.class.getName());
                            intent.putExtra(SettingsActivity.EXTRA_NO_HEADERS, true);
                            startActivityForResult(intent, REQUEST_SETTINGS);
                        }
                    });

            final View snackbarView = snackbar.getView();
            snackbarView.setBackgroundColor(Color.DKGRAY);
            final TextView textView = UI.find(snackbarView, android.support.design.R.id.snackbar_text);
            textView.setTextColor(Color.WHITE);
            snackbar.show();
        }
    }

    private void setAccountTitle(final int subAccount) {
        String suffix = "";

        if (mService.showBalanceInTitle()) {
            final Coin rawBalance = mService.getCoinBalance(subAccount);
            if (rawBalance != null) {
                // We have a balance, i.e. our login callbacks have finished.
                // This check is only needed until login returns balances atomically.
                final String btcUnit = (String) mService.getUserConfig("unit");
                final MonetaryFormat bitcoinFormat = CurrencyMapper.mapBtcUnitToFormat(btcUnit);

                final String btcBalance = bitcoinFormat.noCode().format(rawBalance).toString();
                suffix = String.format("%s %s", UI.setAmountText(null, btcBalance), bitcoinFormat.code());
            }
        } else if (mService.haveSubaccounts()) {
            final Map<String, ?> m = mService.findSubaccount(subAccount);
            if (m == null)
                suffix = getResources().getString(R.string.main_account);
            else
                suffix = (String) m.get("name");
        }
        if (!suffix.isEmpty())
            suffix = " " + suffix;
        setTitle(String.format("%s%s", getResources().getText(R.string.app_name), suffix));
    }

    private void setBlockWaitDialog(final boolean doBlock) {
        final SectionsPagerAdapter adapter;
        adapter = (SectionsPagerAdapter) mViewPager.getAdapter();
        adapter.setBlockWaitDialog(doBlock);
    }

    private void configureSubaccountsFooter(final int subAccount) {
        setAccountTitle(subAccount);
        if (!mService.haveSubaccounts())
            return;

        final ArrayList<GaService.Subaccount> subaccounts = mService.getSubaccountObjs();
        boolean subaccountEnabledFound = false;
        for(final GaService.Subaccount subaccount : subaccounts) {
            subaccountEnabledFound = subaccount.mEnabled;
            if (subaccount.mEnabled)
                break;
        }
        if (!subaccountEnabledFound)
            return;

        final FloatingActionButton fab = UI.find(this, R.id.fab);
        UI.show(fab);

        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                setBlockWaitDialog(true);
                final ArrayList<GaService.Subaccount> subaccounts = mService.getSubaccountObjs();
                final int subaccount_len = subaccounts.size() + 1;
                final ArrayList<String> names = new ArrayList<>(subaccount_len);
                final ArrayList<Integer> pointers = new ArrayList<>(subaccount_len);

                names.add(getResources().getString(R.string.main_account));
                pointers.add(0);

                for(final GaService.Subaccount subaccount : subaccounts) {
                    if (subaccount.mEnabled) {
                        names.add(subaccount.mName);
                        pointers.add(subaccount.mPointer);
                    }
                }

                final AccountItemAdapter adapter = new AccountItemAdapter(names, pointers, mService);
                final MaterialDialog dialog = new MaterialDialog.Builder(TabbedMainActivity.this)
                        .title(R.string.footerAccount)
                        .adapter(adapter, null)
                        .show();
                UI.setDialogCloseHandler(dialog, mDialogCB);

                adapter.setCallback(new AccountItemAdapter.OnAccountSelected() {
                    @Override
                    public void onAccountSelected(final int account) {
                        dialog.dismiss();
                        final int pointer = pointers.get(account);
                        if (pointer == mService.getCurrentSubAccount())
                            return;
                        setAccountTitle(pointer);
                        onSubaccountUpdate(pointer);
                    }
                });
            }
        });
    }

    private void onSubaccountUpdate(final int subAccount) {
        mService.setCurrentSubAccount(subAccount);

        final Intent data = new Intent("fragmentupdater");
        data.putExtra("sub", subAccount);
        sendBroadcast(data);
        mTwoFactorObserver.update(null, null);
    }

    @SuppressLint("NewApi") // NdefRecord#toUri disabled for API < 16
    private void launch(final boolean isBitcoinUri, final boolean isBitidUri) {

        setContentView(R.layout.activity_tabbed_main);
        final Toolbar toolbar = UI.find(this, R.id.toolbar);
        setSupportActionBar(toolbar);

        // Set up the action bar.
        final SectionsPagerAdapter sectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

        // Set up the ViewPager with the sections adapter.
        mViewPager = UI.find(this, R.id.container);

        // Keep all of our tabs in memory while paging. This helps any races
        // left where broadcasts/callbacks are called on the pager when its not
        // shown.
        mViewPager.setOffscreenPageLimit(3);

        // Re-show our 2FA warning if config is changed to remove all methods
        // Fake a config change to show the warning if no current 2FA method
        mTwoFactorObserver.update(null, null);

        configureSubaccountsFooter(mService.getCurrentSubAccount());

        // by default go to center tab
        int goToTab = 1;

        if (isBitcoinUri) {
            // go to send page tab
            goToTab = 2;

            // Started by clicking on a bitcoin URI, show the send tab initially.
            if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())) {
                mViewPager.setTag(R.id.tag_bitcoin_uri, getIntent().getData());
            } else {
                // NdefRecord#toUri not available in API < 16
                if (Build.VERSION.SDK_INT > 16) {
                    final Parcelable[] rawMessages;
                    rawMessages = getIntent().getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
                    for (Parcelable parcel : rawMessages) {
                        final NdefMessage ndefMsg = (NdefMessage) parcel;
                        for (NdefRecord record : ndefMsg.getRecords())
                            if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN &&
                                    Arrays.equals(record.getType(), NdefRecord.RTD_URI)) {
                                mViewPager.setTag(R.id.tag_bitcoin_uri, record.toUri());
                            }
                    }
                }
            }
            // if arrives from internal QR scan
            if (mInternalQr) {
                mViewPager.setTag(R.id.internal_qr, "internal_qr");
            }
        }

        // set adapter and tabs only after all setTag in ViewPager container
        mViewPager.setAdapter(sectionsPagerAdapter);
        mViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int index) {
                sectionsPagerAdapter.onViewPageSelected(index);
            }
        });
        final TabLayout tabLayout = UI.find(this, R.id.tabs);
        tabLayout.setupWithViewPager(mViewPager);

        mViewPager.setCurrentItem(goToTab);

        final Boolean isVendorMode = mService.cfg("is_vendor_mode").getBoolean("enabled", false);
        if (isVendorMode) {
            Intent intent = new Intent(this, VendorActivity.class);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_from_right, R.anim.fade_out);
        }
        final boolean segwit = mService.getLoginData().get("segwit_server");
        if (segwit && mService.isSegwitUnconfirmed()) {
            // The user has not yet enabled segwit. Opt them in and display
            // a dialog explaining how to opt-out.
            CB.after(mService.setUserConfig("use_segwit", true, false),
                     new CB.Toast<Boolean>(this) {
                @Override
                public void onSuccess(final Boolean result) {
                    TabbedMainActivity.this.runOnUiThread(new Runnable() {
                        public void run() {
                            mSegwitDialog = UI.popup(TabbedMainActivity.this, R.string.segwit_dialog_title, 0)
                                              .content(R.string.segwit_dialog_content).build();
                            UI.setDialogCloseHandler(mSegwitDialog, mSegwitCB);
                            mSegwitDialog.show();
                        }
                    });
                }
            });
        }

        if (isBitidUri) {
            final Intent intent = getIntent();
            final Uri uri = intent.getData();
            bitidAuth(uri.toString());
        }
    }

    @Override
    public void onResumeWithService() {
        mService.addConnectionObserver(this);
        mService.addTwoFactorObserver(mTwoFactorObserver);

        if (mService.isForcedOff()) {
            // FIXME: Should pass flag to activity so it shows it was forced logged out
            startActivity(new Intent(this, FirstScreenActivity.class));
            finish();
            return;
        }

        if (!firstRun && !mService.isLoggedOrLoggingIn()) {
            // Not logged in, force the user to login
            mService.disconnect(false);
            final Intent login = new Intent(this, RequestLoginActivity.class);
            startActivity(login);
            return;
        }
     }

    @Override
    public void onPauseWithService() {
        mService.deleteTwoFactorObserver(mTwoFactorObserver);
        mService.deleteConnectionObserver(this);
    }

    private final static int BIP38_FLAGS = (NetworkParameters.fromID(NetworkParameters.ID_MAINNET).equals(Network.NETWORK)
            ? Wally.BIP38_KEY_MAINNET : Wally.BIP38_KEY_TESTNET) | Wally.BIP38_KEY_COMPRESSED;

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        final TabbedMainActivity caller = TabbedMainActivity.this;

        switch (requestCode) {
            case REQUEST_TX_DETAILS:
                if (data != null && data.getBooleanExtra(REQUEST_RELOAD, false)) {
                    mService.updateBalance(mService.getCurrentSubAccount());
                    startActivity(new Intent(this, TabbedMainActivity.class));
                    finish();
                }
                break;
            case REQUEST_SETTINGS:
                mService.updateBalance(mService.getCurrentSubAccount());
                startActivity(new Intent(this, TabbedMainActivity.class));
                finish();
                break;
            case REQUEST_BITID_URL_LOGIN:
                if (resultCode != RESULT_OK) {
                    // The user failed to login after clicking on a bitcoin Uri
                    finish();
                    return;
                }
                launch(false, true);
                break;
            case REQUEST_BITCOIN_URL_LOGIN:
                if (resultCode != RESULT_OK) {
                    // The user failed to login after clicking on a bitcoin Uri
                    finish();
                    return;
                }
                final boolean isBitcoinUri = true;
                launch(isBitcoinUri, false);
                break;
            case REQUEST_CLEAR_ACTIVITY:
                recreate();
                break;
            case REQUEST_SWEEP_PRIVKEY:
                if (data == null)
                    return;
                ECKey keyNonFinal = null;
                final String qrText = data.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
                try {
                    keyNonFinal = DumpedPrivateKey.fromBase58(Network.NETWORK,
                            qrText).getKey();
                } catch (final AddressFormatException e) {
                    try {
                        Wally.bip38_to_private_key(qrText, null, Wally.BIP38_KEY_COMPRESSED | Wally.BIP38_KEY_QUICK_CHECK);
                    } catch (final IllegalArgumentException e2) {

                        String qrTextPaperwallet = data.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
                        if (qrTextPaperwallet.startsWith("bitcoin:")) {
                            qrTextPaperwallet = qrTextPaperwallet.replaceFirst("^bitcoin:", "").replace("?.*$", "");
                        }
                        if (!isPublicKey(qrTextPaperwallet)) {
                            toast(R.string.invalid_paperwallet);
                            return;
                        }
                        // open webview to verify the wallet content
                        Intent intent = new Intent(caller, VisiuWebview.class);
                        intent.putExtra("public_address", qrTextPaperwallet);
                        startActivityForResult(intent, REQUEST_VISIU);
                        overridePendingTransition(R.anim.slide_from_right, R.anim.fade_out);
                        return;
                    }
                }

                final MaterialDialog dialogLoading = UI.popupWait(TabbedMainActivity.this, R.string.sweep_wait_message);
                dialogLoading.hide();
                dialogLoading.setCancelable(false);

                final ECKey keyNonBip38 = keyNonFinal;
                final FutureCallback<Map<?, ?>> callback = new CB.Toast<Map<?, ?>>(caller) {
                    @Override
                    public void onSuccess(final Map<?, ?> sweepResult) {
                        dialogLoading.dismiss();
                        final View v = getLayoutInflater().inflate(R.layout.dialog_sweep_address, null, false);
                        final TextView passwordPrompt = UI.find(v, R.id.sweepAddressPasswordPromptText);
                        final TextView mainText = UI.find(v, R.id.sweepAddressMainText);
                        final TextView addressText = UI.find(v, R.id.sweepAddressAddressText);
                        final EditText passwordEdit = UI.find(v, R.id.sweepAddressPasswordText);
                        final Transaction txNonBip38;
                        final String address;

                        if (keyNonBip38 != null) {
                            UI.hide(passwordPrompt, passwordEdit);
                            txNonBip38 = getSweepTx(sweepResult);
                            final MonetaryFormat format;
                            format = CurrencyMapper.mapBtcUnitToFormat( (String) mService.getUserConfig("unit"));
                            Coin outputsValue = Coin.ZERO;
                            for (final TransactionOutput output : txNonBip38.getOutputs())
                                outputsValue = outputsValue.add(output.getValue());
                            mainText.setText(Html.fromHtml("Are you sure you want to sweep <b>all</b> ("
                                    + format.postfixCode().format(outputsValue) + ") funds from the address below?"));
                            address = keyNonBip38.toAddress(Network.NETWORK).toString();
                        } else {
                            passwordPrompt.setText(R.string.sweep_bip38_passphrase_prompt);
                            txNonBip38 = null;
                            // amount not known until decrypted
                            mainText.setText(Html.fromHtml("Are you sure you want to sweep <b>all</b> funds from the password protected BIP38 key below?"));
                            address = data.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
                        }


                        addressText.setText(String.format("%s\n%s\n%s", address.substring(0, 12), address.substring(12, 24), address.substring(24)));

                        final MaterialDialog.Builder builder = UI.popup(caller, R.string.sweepAddressTitle, R.string.sweep, R.string.cancel)
                            .customView(v, true)
                            .onPositive(new MaterialDialog.SingleButtonCallback() {
                                Transaction tx;
                                ECKey key;

                                private void doSweep() {
                                    final ArrayList<String> scripts = (ArrayList) sweepResult.get("prevout_scripts");
                                    final Integer outPointer = (Integer) sweepResult.get("out_pointer");
                                    CB.after(mService.verifySpendableBy(tx.getOutputs().get(0), 0, outPointer),
                                             new CB.Toast<Boolean>(caller) {
                                        @Override
                                        public void onSuccess(final Boolean isSpendable) {
                                            if (!isSpendable) {
                                                caller.toast(R.string.err_tabbed_sweep_failed);
                                                return;
                                            }
                                            final List<byte[]> signatures = new ArrayList<>();
                                            for (int i = 0; i < tx.getInputs().size(); ++i) {
                                                final byte[] script = Wally.hex_to_bytes(scripts.get(i));
                                                final TransactionSignature sig;
                                                sig = tx.calculateSignature(i, key, script, Transaction.SigHash.ALL, false);
                                                signatures.add(sig.encodeToBitcoin());
                                            }
                                            CB.after(mService.sendTransaction(signatures),
                                                     new CB.Toast<String>(caller) { });
                                        }
                                    });
                                }

                                @Override
                                public void onClick(final MaterialDialog dialog, final DialogAction which) {
                                    if (keyNonBip38 != null) {
                                        tx = txNonBip38;
                                        key = keyNonBip38;
                                        doSweep();
                                        return;
                                    }
                                    try {
                                        final String password = UI.getText(passwordEdit);
                                        final byte[] passbytes = password.getBytes();
                                        final byte[] decryptedPKey = Wally.bip38_to_private_key(qrText, passbytes, BIP38_FLAGS);
                                        key = ECKey.fromPrivate(decryptedPKey);

                                        CB.after(mService.prepareSweepSocial(key.getPubKey(), true),
                                                 new CB.Toast<Map<?, ?>>(caller) {
                                            @Override
                                            public void onSuccess(final Map<?, ?> sweepResult) {
                                                tx = getSweepTx(sweepResult);
                                                doSweep();
                                            }
                                        });
                                    } catch (final IllegalArgumentException e) {
                                        caller.toast(R.string.invalid_passphrase);
                                    }
                                }
                            });

                        runOnUiThread(new Runnable() { public void run() { builder.build().show(); } });
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        super.onFailure(t);
                        dialogLoading.dismiss();
                    }
                };


                // FIXME workaround to get info about paperwallet before call WAMP GA server because is too slow the GA server to reply
                @Deprecated
                class GetPublicKeyBalance extends AsyncTask<String, Void, String> {

                    private String pubKey;

                    @Override
                    protected String doInBackground(String... strings) {
                        String balance = "";
                        try {
                            pubKey = strings[0];
                            final String apikey = "9e3043e0226a7f5e94f881c4bc37340efb265f1e";
                            final String apiurl = "http://api.blocktrail.com/v1/" +
                                    (Network.NETWORK.getId().equals(NetworkParameters.ID_MAINNET)? "BTC" : "tBTC") +
                                    "/address/";
                            URL url = new URL(apiurl + pubKey + "?api_key=" + apikey);
                            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

                            InputStream stream = new BufferedInputStream(urlConnection.getInputStream());
                            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream));
                            StringBuilder builder = new StringBuilder();

                            String inputString;
                            while ((inputString = bufferedReader.readLine()) != null) {
                                builder.append(inputString);
                            }

                            JSONObject topLevel = new JSONObject(builder.toString());
                            balance = topLevel.getString("balance");
                            urlConnection.disconnect();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        return balance;
                    }

                    @Override
                    protected void onPostExecute(String result) {
                        if (result.isEmpty()) {
                            toast(R.string.invalid_paperwallet);
                            return;
                        }
                        final Float balanceBtc = Float.valueOf(result)/100000000;
                        if (balanceBtc == 0) {
                            // open webview to verify the wallet content
                            Intent intent = new Intent(caller, VisiuWebview.class);
                            intent.putExtra("public_address", pubKey);
                            startActivity(intent);
                            overridePendingTransition(R.anim.slide_from_right, R.anim.fade_out);
                            return;
                        }
                        final String warningSweepPrivKey = String.format(mActivity.getResources().getString(R.string.warningSweepPrivKey), balanceBtc.toString());
                        final Dialog confirmDialog = UI.popup(mActivity, R.string.warning)
                                .content(warningSweepPrivKey)
                                .onPositive(new MaterialDialog.SingleButtonCallback() {
                                    @Override
                                    public void onClick(final MaterialDialog dialog, final DialogAction which) {
                                        dialogLoading.show();
                                        if (keyNonBip38 != null)
                                            CB.after(mService.prepareSweepSocial(keyNonBip38.getPubKey(), true), callback);
                                        else
                                            callback.onSuccess(null);
                                    }
                                })
                                .onNegative(new MaterialDialog.SingleButtonCallback() {
                                    @Override
                                    public void onClick(final MaterialDialog dialog, final DialogAction which) {
                                        dialog.cancel();
                                    }
                                }).build();

                        confirmDialog.show();
                    }
                }
                final String pubKey = getPublicKeyStringFromHash(keyNonBip38.getPubKeyHash());
                new GetPublicKeyBalance().execute(pubKey);

                break;
            case REQUEST_VISIU:
                if (data != null && isBitcoinScheme(data)) {
                    final Intent browsable = new Intent(this, TabbedMainActivity.class);
                    browsable.setData(data.getData());
                    browsable.addCategory(Intent.CATEGORY_BROWSABLE);
                    browsable.putExtra("internal_qr", true);
                    // start new activity and finish old one
                    startActivity(browsable);
                    this.finish();
                }
                break;
        }
    }

    /**
     * get public key from the byte array hash
     * @param pubKeyBytes the byte array hash of the public key
     * @return String of public key
     */
    @Deprecated
    private String getPublicKeyStringFromHash(byte [] pubKeyBytes) {
        byte [] pubKeyBytes2 = new byte [pubKeyBytes.length + 1];
        byte [] pubKeyBytes3 = new byte [pubKeyBytes2.length + 4];

        if (Network.NETWORK != null) {
            if (Network.NETWORK.getId().equals(NetworkParameters.ID_MAINNET)) {
                pubKeyBytes2[0] = 0;
            } else {
                pubKeyBytes2[0] = 0x6F;
            }
        } else {
            return "";
        }
        System.arraycopy(pubKeyBytes, 0, pubKeyBytes2, 1, pubKeyBytes.length);

        byte[] sha256 = Sha256Hash.hash(Sha256Hash.hash(pubKeyBytes2));

        // get checksum and put 4 bytes in the end
        System.arraycopy(pubKeyBytes2, 0, pubKeyBytes3, 0, pubKeyBytes2.length);
        System.arraycopy(sha256, 0, pubKeyBytes3, pubKeyBytes3.length - 4, 4);

        return Base58.encode(pubKeyBytes3);
    }

    private Transaction getSweepTx(final Map<?, ?> sweepResult) {
        return GaService.buildTransaction((String) sweepResult.get("tx"));
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        final int id = mService.isWatchOnly() ? R.menu.watchonly : R.menu.main;
        getMenuInflater().inflate(R.menu.camera_menu, menu);
        getMenuInflater().inflate(id, menu);
        mMenu = menu;

        // get advanced_options flag and show/hide menu items
        Boolean advancedOptionsValue = mService.cfg("advanced_options").getBoolean("enabled", false);
        MenuItem actionNetwork = menu.findItem(R.id.action_network);
        if (!mService.isWatchOnly() && !advancedOptionsValue) {
            actionNetwork.setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {

        final TabbedMainActivity caller = TabbedMainActivity.this;

        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivityForResult(new Intent(caller, SettingsActivity.class), REQUEST_SETTINGS);
                return true;
            case R.id.action_sweep:
                final Intent scanner = new Intent(caller, ScanActivity.class);
                //New Marshmallow permissions paradigm
                final String[] perms = {"android.permission.CAMERA"};
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1 &&
                        checkSelfPermission(perms[0]) != PackageManager.PERMISSION_GRANTED) {
                    if (item.getItemId() == R.id.action_sweep)
                        requestPermissions(perms, /*permsRequestCode*/ 200);
                    else
                        requestPermissions(perms, /*permsRequestCode*/ 300);
                } else {
                    startActivityForResult(scanner, REQUEST_SWEEP_PRIVKEY);
                }
                return true;
            case R.id.network_unavailable:
                return true;
            case R.id.action_logout:
                mService.disconnect(false);
                finish();
                return true;
            case R.id.action_network:
                startActivity(new Intent(caller, NetworkMonitorActivity.class));
                return true;
            case R.id.action_about:
                startActivity(new Intent(caller, AboutActivity.class));
                return true;
            case R.id.action_vendor:
                Intent intent = new Intent(caller, VendorActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.slide_from_right, R.anim.fade_out);
                return true;

        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (mViewPager.getCurrentItem() == 1)
            finish();
        else
            mViewPager.setCurrentItem(1);
    }

    @Override
    public void update(final Observable observable, final Object data) {
        final GaService.State state = (GaService.State) data;
        if (state.isForcedOff()) {
            // FIXME: Should pass flag to activity so it shows it was forced logged out
            startActivity(new Intent(this, FirstScreenActivity.class));
        }
        setMenuItemVisible(mMenu, R.id.network_unavailable, !state.isLoggedIn());
    }

    private void handlePermissionResult(final int[] granted, int action, int msgId) {
        if (granted[0] == PackageManager.PERMISSION_GRANTED)
            startActivityForResult(new Intent(this, ScanActivity.class), action);
        else
            shortToast(msgId);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions, final int[] granted) {
        if (requestCode == 200)
                handlePermissionResult(granted, REQUEST_SWEEP_PRIVKEY,
                                       R.string.err_tabbed_sweep_requires_camera_permissions);
        else if (requestCode == 100)
                handlePermissionResult(granted, REQUEST_SEND_QR_SCAN,
                                       R.string.err_qrscan_requires_camera_permissions);
    }

    /**
     * Try to manage BitID url and login/authenticate
     * @param bitidUrl String
     */
    private void bitidAuth(final String bitidUrl) {
        final BitID bitId;
        try {
            bitId = BitID.parse(bitidUrl);
        } catch (URISyntaxException e) {
            toast(getResources().getString(R.string.err_unsupported_bitid));
            return;
        }

        final String bitIdHost = bitId.getUri().getHost();

        final ISigningWallet signingWallet;
        try {
            signingWallet = mService.getBitidWallet(bitidUrl, 0);
        } catch (UnsupportedOperationException | IOException e) {
            toast(getResources().getString(R.string.err_unsupported_wallet));
            return;
        } catch (URISyntaxException e) {
            new QrcodeScanDialog(this, bitidUrl).show();
            return;
        }

        // wait login popup
        final MaterialDialog waitLoginPopup = UI.popupWaitCustom(mActivity,R.string.bitid_dialog_title)
                .content(getResources().getString(R.string.bitid_login_to, bitId.getUri().getHost())).build();

        // callback to manage thw sign in response
        final BitidSignIn.OnPostSignInListener callback = new BitidSignIn.OnPostSignInListener() {
            @Override
            public void postExecuteSignIn(SignInResponse response) {
                waitLoginPopup.cancel();
                if (response == null) {
                    UI.popup(mActivity, R.string.bitid_dialog_title, android.R.string.ok)
                            .content(getResources().getString(R.string.err_unsupported_bitid))
                            .build().show();
                } else if (response.getResultCode() == 0) {
                    UI.toast(mActivity, getResources().getString(R.string.bitid_login_successful, bitIdHost), Toast.LENGTH_LONG);
                    Log.d(TAG, "bitid succsssful login." + " message: " + (!response.getMessage().isEmpty() ? response.getMessage() : "no message received"));
                    if (!mInternalQr) {
                        // when arrives from external call, close activity and back to the caller
                        finish();
                    }
                } else {
                    final String errMessage = getResources().getString(R.string.err_bitid_login_error, response.getResultCode());

                    final View bitidErrorDialogView = getLayoutInflater().inflate(R.layout.dialog_bitid_error, null, false);

                    final TextView bitIdErrorMessage = UI.find(bitidErrorDialogView, R.id.bitIdErrorMessage);

                    String serverMessageError = null;
                    String jsonData = response.getMessage();
                    try {
                        final JSONObject json = new JSONObject(jsonData);
                        serverMessageError = (String) json.get("message");
                    } catch (JSONException e) {
                        Log.d(TAG, "bitid login error. Not valid error: " + response.getMessage());
                        UI.hide(bitIdErrorMessage);
                    } finally {
                        bitIdErrorMessage.setText(serverMessageError);
                    }
                    ((TextView) UI.find(bitidErrorDialogView, R.id.bitIdError)).setText(errMessage);

                    UI.popup(mActivity, R.string.bitid_dialog_title, android.R.string.ok)
                            .customView(bitidErrorDialogView, true)
                            .build().show();
                    Log.d(TAG, "bitid login error. code: " + response.getResultCode() + " message:" + response.getMessage());
                }
            }
        };

        final View bitidDialogView = getLayoutInflater().inflate(R.layout.dialog_bitid_request, null, false);

        final TextView bitidHostView = UI.find(bitidDialogView, R.id.bitidHost);
        bitidHostView.setText(bitIdHost);

        // warning message if callback is http (insecure)
        final TextView bitidInsecureText = UI.find(bitidDialogView, R.id.bitidInsecureText);
        if (!bitId.isSecured())
            UI.show(bitidInsecureText);

        UI.popup(this, R.string.bitid_dialog_title, R.string.confirm, R.string.cancel)
                .customView(bitidDialogView, true)
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        new BitidSignIn().execute(mActivity, bitId, signingWallet, callback, mService);
                        waitLoginPopup.show();
                    }
                }).build().show();
    }

    /**
     * A {@link FragmentPagerAdapter} that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    public class SectionsPagerAdapter extends FragmentPagerAdapter {
        private final SubaccountFragment[] mFragments = new SubaccountFragment[3];
        private int mSelectedPage = -1;
        private int mInitialSelectedPage = -1;
        private boolean mInitialPage = true;

        public SectionsPagerAdapter(final FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(final int index) {
            Log.d(TAG, "SectionsPagerAdapter -> getItem " + index);
            switch (index) {
                case 0: return new ReceiveFragment();
                case 1: return new MainFragment();
                case 2: return new SendFragment();
            }
            return null;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int index) {
            Log.d(TAG, "SectionsPagerAdapter -> instantiateItem " + index);

            mFragments[index] = (SubaccountFragment) super.instantiateItem(container, index);

            if (mInitialPage && index == mInitialSelectedPage) {
                // Call setPageSelected() on the first page, now that it is created
                Log.d(TAG, "SectionsPagerAdapter -> selecting first page " + index);
                mFragments[index].setPageSelected(true);
                mInitialSelectedPage = -1;
                mInitialPage = false;
            }
            return mFragments[index];
        }

        @Override
        public void destroyItem(ViewGroup container, int index, Object object) {
            Log.d(TAG, "SectionsPagerAdapter -> destroyItem " + index);
            if (index >=0 && index <=2 && mFragments[index] != null) {
                // Make sure the fragment is not kept alive and does not
                // try to process any callbacks it registered for.
                mFragments[index].detachObservers();
                // Make sure any wait dialog being shown is dismissed
                mFragments[index].setPageSelected(false);
                mFragments[index] = null;
            }
            super.destroyItem(container, index, object);
        }

        @Override
        public int getCount() {
            // We don't show the send tab in watch only mode
            return mService.isWatchOnly() ? 2 : 3;
        }

        @Override
        public CharSequence getPageTitle(final int index) {
            final Locale l = Locale.getDefault();
            switch (index) {
                case 0: return getString(R.string.receive_title).toUpperCase(l);
                case 1: return getString(R.string.main_title).toUpperCase(l);
                case 2: return getString(R.string.send_title).toUpperCase(l);
            }
            return null;
        }

        public void onViewPageSelected(final int index) {
            Log.d(TAG, "SectionsPagerAdapter -> onViewPageSelected " + index +
                       " current is " + mSelectedPage + " initial " + mInitialPage);

            if (mInitialPage)
                mInitialSelectedPage = index; // Store so we can notify it when constructed

            if (index == mSelectedPage)
                return; // No change to the selected page

            // Un-select any old selected page
            if (mSelectedPage != -1 && mFragments[mSelectedPage] != null)
                mFragments[mSelectedPage].setPageSelected(false);

            // Select the current page
            mSelectedPage = index;
            if (mFragments[mSelectedPage] != null)
                mFragments[mSelectedPage].setPageSelected(true);
        }

        public void setBlockWaitDialog(final boolean doBlock) {
            for (SubaccountFragment fragment : mFragments)
                if (fragment != null)
                    fragment.setBlockWaitDialog(doBlock);
        }
    }
}
