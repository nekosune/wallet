/*
 * Copyright 2013, 2014 Megion Research and Development GmbH
 *
 * Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 * This license governs use of the accompanying software. If you use the software, you accept this license.
 * If you do not accept the license, do not use the software.
 *
 * 1. Definitions
 * The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 * "You" means the licensee of the software.
 * "Your company" means the company you worked for when you downloaded the software.
 * "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 * of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 * software, and specifically excludes the right to distribute the software outside of your company.
 * "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 * under this license.
 *
 * 2. Grant of Rights
 * (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free copyright license to reproduce the software for reference use.
 * (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free patent license under licensed patents for reference use.
 *
 * 3. Limitations
 * (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 * (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 * (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 * (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 * guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 * change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 * fitness for a particular purpose and non-infringement.
 */

package com.mycelium.wallet;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.mrd.bitlib.crypto.*;
import com.mrd.bitlib.model.Address;
import com.mrd.bitlib.model.NetworkParameters;
import com.mrd.bitlib.util.BitUtils;
import com.mrd.bitlib.util.CoinUtil;
import com.mrd.bitlib.util.CoinUtil.Denomination;
import com.mrd.bitlib.util.HashUtils;
import com.mycelium.lt.api.LtApiClient;
import com.mycelium.net.ServerEndpointType;
import com.mycelium.net.TorManager;
import com.mycelium.net.TorManagerOrbot;
import com.mycelium.wallet.activity.modern.ExploreHelper;
import com.mycelium.wallet.activity.util.BlockExplorer;
import com.mycelium.wallet.activity.util.BlockExplorerManager;
import com.mycelium.wallet.activity.util.Pin;
import com.mycelium.wallet.api.AndroidAsyncApi;
import com.mycelium.wallet.bitid.ExternalService;
import com.mycelium.wallet.event.*;
import com.mycelium.wallet.ledger.LedgerManager;
import com.mycelium.wallet.lt.LocalTraderManager;
import com.mycelium.wallet.persistence.MetadataStorage;
import com.mycelium.wallet.persistence.TradeSessionDb;
import com.mycelium.wallet.trezor.TrezorManager;
import com.mycelium.wallet.wapi.SqliteWalletManagerBackingWrapper;
import com.mycelium.wapi.api.WapiClient;
import com.mycelium.WapiLogger;
import com.mycelium.wapi.wallet.*;
import com.mycelium.wapi.wallet.bip44.Bip44Account;
import com.mycelium.wapi.wallet.bip44.Bip44AccountContext;
import com.mycelium.wapi.wallet.bip44.ExternalSignatureProviderProxy;
import com.mycelium.wapi.wallet.single.SingleAddressAccount;
import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.logging.Level;

public class MbwManager {

   public static final String PROXY_HOST = "socksProxyHost";
   public static final String PROXY_PORT = "socksProxyPort";
   public static final String SELECTED_ACCOUNT = "selectedAccount";
   private static volatile MbwManager _instance = null;

   /**
    * The root index we use for generating authentication keys.
    * 0x80 makes the number negative == hardened key derivation
    * 0x424944 = "BID"
    */
   private static final int BIP32_ROOT_AUTHENTICATION_INDEX = 0x80424944;
   private Optional<CoinapultManager> coinapultManager;

   /**
    * The index proposed for bit id as per https://github.com/bitid/bitid/blob/master/BIP_draft.md
    * Including hardened marker
    */
   private static final int BITID_BIP_INDEX = 0xb11e | HdKeyNode.HARDENED_MARKER;

   private final CurrencySwitcher _currencySwitcher;
   private Date lastSuccessfullPin;
   private boolean startUpPinUnlocked = false;

   public static synchronized MbwManager getInstance(Context context) {
      if (_instance == null) {
         _instance = new MbwManager(context);
      }
      return _instance;
   }

   private final Bus _eventBus;
   private final TrezorManager _trezorManager;
   private final LedgerManager _ledgerManager;
   private final WapiClient _wapi;

   private final LtApiClient _ltApi;
   private Handler torHandler;
   private Context _applicationContext;
   private AddressBookManager _addressBookManager;
   private MetadataStorage _storage;
   private LocalTraderManager _localTraderManager;
   private Pin _pin;
   private boolean _pinRequiredOnStartup;

   private MinerFee _minerFee;
   private boolean _enableContinuousFocus;
   private boolean _keyManagementLocked;
   private boolean _isBitidEnabled;
   private MrdExport.V1.EncryptionParameters _cachedEncryptionParameters;
   private final MrdExport.V1.ScryptParameters _deviceScryptParameters;
   private MbwEnvironment _environment;
   private final ExploreHelper exploreHelper;
   private HttpErrorCollector _httpErrorCollector;
   private String _language;
   private final VersionManager _versionManager;
   private final ExchangeRateManager _exchangeRateManager;
   private final WalletManager _walletManager;
   private WalletManager _tempWalletManager;
   private final RandomSource _randomSource;
   private final EventTranslator _eventTranslator;
   private ServerEndpointType.Types _torMode;
   private TorManager _torManager;
   public final BlockExplorerManager _blockExplorerManager;

   private EvictingQueue<LogEntry> _wapiLogs = EvictingQueue.create(100);
   private Cache<String, Object> _semiPersistingBackgroundObjects = CacheBuilder.newBuilder().maximumSize(10).build();

   private MbwManager(Context evilContext) {
      _applicationContext = Preconditions.checkNotNull(evilContext.getApplicationContext());
      _environment = MbwEnvironment.determineEnvironment(_applicationContext);
      String version = VersionManager.determineVersion(_applicationContext);

      // Preferences
      SharedPreferences preferences = getPreferences();
      // setProxy(preferences.getString(Constants.PROXY_SETTING, ""));
      // Initialize proxy early, to enable error reporting during startup..

      _eventBus = new Bus();
      _eventBus.register(this);

      // init tor - if needed
      try {
         setTorMode(ServerEndpointType.Types.valueOf(preferences.getString(Constants.TOR_MODE, "")));
      } catch (IllegalArgumentException ex) {
         setTorMode(ServerEndpointType.Types.ONLY_HTTPS);
      }

      _wapi = initWapi();
      _httpErrorCollector = HttpErrorCollector.registerInVM(_applicationContext, version, _wapi);

      if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.GINGERBREAD) {
         // Disable HTTP keep-alive on systems predating Gingerbread
         System.setProperty("http.keepAlive", "false");
      }

      _randomSource = new AndroidRandomSource();

      //_connectionWatcher = new NetworkConnectionWatcher(_applicationContext);


      _isBitidEnabled = _applicationContext.getResources().getBoolean(R.bool.bitid_enabled);

      // Local Trader
      TradeSessionDb tradeSessionDb = new TradeSessionDb(_applicationContext);
      _ltApi = initLt();
      _localTraderManager = new LocalTraderManager(_applicationContext, tradeSessionDb, getLtApi(), this);

      _pin = new Pin(
            preferences.getString(Constants.PIN_SETTING, ""),
            preferences.getString(Constants.PIN_SETTING_RESETTABLE, "1").equals("1")
      );
      _pinRequiredOnStartup = preferences.getBoolean(Constants.PIN_SETTING_REQUIRED_ON_STARTUP, false);

      _minerFee = MinerFee.fromString(preferences.getString(Constants.MINER_FEE_SETTING, MinerFee.NORMAL.toString()));
      _enableContinuousFocus = preferences.getBoolean(Constants.ENABLE_CONTINUOUS_FOCUS_SETTING, false);
      _keyManagementLocked = preferences.getBoolean(Constants.KEY_MANAGEMENT_LOCKED_SETTING, false);

      // Get the display metrics of this device
      DisplayMetrics dm = new DisplayMetrics();
      WindowManager windowManager = (WindowManager) _applicationContext.getSystemService(Context.WINDOW_SERVICE);
      windowManager.getDefaultDisplay().getMetrics(dm);

      _addressBookManager = new AddressBookManager(_applicationContext);
      _storage = new MetadataStorage(_applicationContext);
      exploreHelper = new ExploreHelper();
      _language = preferences.getString(Constants.LANGUAGE_SETTING, Locale.getDefault().getLanguage());
      _versionManager = new VersionManager(_applicationContext, _language, new AndroidAsyncApi(_wapi, _eventBus), version, _eventBus);

      Set<String> currencyList = getPreferences().getStringSet(Constants.SELECTED_CURRENCIES, null);
      ArrayList<String> fiatCurrencies = new ArrayList<String>();
      if (currencyList == null) {
         //if there is no list take the default currency
         fiatCurrencies.add(Constants.DEFAULT_CURRENCY);
      } else {
         //else take all dem currencies, yeah
         fiatCurrencies.addAll(currencyList);
      }

      _exchangeRateManager = new ExchangeRateManager(_applicationContext, _wapi, fiatCurrencies);

      _currencySwitcher = new CurrencySwitcher(
            _applicationContext,
            _exchangeRateManager,
            fiatCurrencies,
            getPreferences().getString(Constants.FIAT_CURRENCY_SETTING, Constants.DEFAULT_CURRENCY),
            Denomination.fromString(preferences.getString(Constants.BITCOIN_DENOMINATION_SETTING, Denomination.BTC.toString()))
      );

      // Check the device MemoryClass and set the scrypt-parameters for the PDF backup
      ActivityManager am = (ActivityManager) _applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
      int memoryClass = am.getMemoryClass();

      _deviceScryptParameters = memoryClass > 20
            ? MrdExport.V1.ScryptParameters.DEFAULT_PARAMS
            : MrdExport.V1.ScryptParameters.LOW_MEM_PARAMS;

      _trezorManager = new TrezorManager(_applicationContext, getNetwork(), getEventBus());
      _ledgerManager = new LedgerManager(_applicationContext, getNetwork(), getEventBus());
      _walletManager = createWalletManager(_applicationContext, _environment);
      _eventTranslator = new EventTranslator(new Handler(), _eventBus);
      _walletManager.addObserver(_eventTranslator);
      _exchangeRateManager.subscribe(_eventTranslator);
      coinapultManager = createCoinapultManager();
      _walletManager.setExtraAccount(coinapultManager);
      migrateOldKeys();
      createTempWalletManager();

      _versionManager.initBackgroundVersionChecker();
      _blockExplorerManager = new BlockExplorerManager(this,
            _environment.getBlockExplorerList(),
            getPreferences().getString(Constants.BLOCK_EXPLORER,
                  _environment.getBlockExplorerList().get(0).getIdentifier()));
   }

   private Optional<CoinapultManager> createCoinapultManager() {
      if (_walletManager.hasBip32MasterSeed() && _storage.isPairedService("coinapult")) {
         BitidKeyDerivation derivation = new BitidKeyDerivation() {
            @Override
            public InMemoryPrivateKey deriveKey(int accountIndex, String site) {
               try {
                  Bip39.MasterSeed masterSeed = _walletManager.getMasterSeed(AesKeyCipher.defaultKeyCipher());
                  return createBip32WebsitePrivateKey(masterSeed.getBip32Seed(), accountIndex, site);
               } catch (KeyCipher.InvalidKeyCipher invalidKeyCipher) {
                  throw new RuntimeException(invalidKeyCipher);
               }
            }
         };
         return Optional.of(new CoinapultManager(
               _environment,
               derivation,
               _eventBus,
               new Handler(_applicationContext.getMainLooper()),
               _storage,
               _exchangeRateManager,
               retainingWapiLogger));

      } else {
         return Optional.absent();
      }
   }

   private void createTempWalletManager() {
      //for managing temp accounts created through scanning
      _tempWalletManager = createTempWalletManager(_applicationContext, _environment);
      _tempWalletManager.addObserver(_eventTranslator);
   }

   private LtApiClient initLt() {
      return new LtApiClient(_environment.getLtEndpoints(), new LtApiClient.Logger() {
         @Override
         public void logError(String message, Exception e) {
            Log.e("", message, e);
            retainLog(Level.SEVERE, message);
         }

         @Override
         public void logError(String message) {
            Log.e("", message);
            retainLog(Level.SEVERE, message);
         }

         @Override
         public void logInfo(String message) {
            Log.i("", message);
            retainLog(Level.INFO, message);
         }
      });
   }

   private synchronized void retainLog(Level level, String message) {
      _wapiLogs.add(new LogEntry(message, level, new Date()));
   }

   public WapiLogger retainingWapiLogger = new WapiLogger() {
      @Override
      public void logError(String message) {
         Log.e("Wapi", message);
         retainLog(Level.SEVERE, message);
      }

      @Override
      public void logError(String message, Exception e) {
         Log.e("Wapi", message, e);
         retainLog(Level.SEVERE, message);
      }

      @Override
      public void logInfo(String message) {
         Log.i("Wapi", message);
         retainLog(Level.INFO, message);
      }
   };

   private WapiClient initWapi() {
      String version;
      try {
         PackageInfo packageInfo = _applicationContext.getPackageManager().getPackageInfo(_applicationContext.getPackageName(), 0);
         if (packageInfo != null) {
            version = String.valueOf(packageInfo.versionCode);
         } else {
            version = "na";
         }
      } catch (PackageManager.NameNotFoundException e) {
         version = "na";
      }


      return new WapiClient(_environment.getWapiEndpoints(), retainingWapiLogger, version);
   }

   private void initTor() {
      torHandler = new Handler(Looper.getMainLooper());

      if (_torMode == ServerEndpointType.Types.ONLY_TOR) {
         this._torManager = new TorManagerOrbot();
      } else {
         throw new IllegalArgumentException();
      }

      _torManager.setStateListener(new TorManager.TorState() {
         @Override
         public void onStateChange(String status, final int percentage) {
            Log.i("Tor init", status + ", " + String.valueOf(percentage));
            retainLog(Level.INFO, "Tor: " + status + ", " + String.valueOf(percentage));
            torHandler.post(new Runnable() {
               @Override
               public void run() {
                  _eventBus.post(new TorStateChanged(percentage));
               }
            });
         }
      });

      _environment.getWapiEndpoints().setTorManager(this._torManager);
      _environment.getLtEndpoints().setTorManager(this._torManager);
   }


   private void migrateOldKeys() {
      // We only migrate old keys if we don't have any accounts yet - otherwise, migration has already taken place
      if (!_walletManager.getAccountIds().isEmpty()) {
         return;
      }

      // Get the local trader address, may be null
      Address localTraderAddress = _localTraderManager.getLocalTraderAddress();
      if (localTraderAddress == null) {
         _localTraderManager.unsetLocalTraderAccount();
      }

      //check which address was the last recently selected one
      SharedPreferences prefs = _applicationContext.getSharedPreferences("selected", Context.MODE_PRIVATE);
      String lastAddress = prefs.getString("last", null);

      // Migrate all existing records to accounts
      List<Record> records = loadClassicRecords();
      for (Record record : records) {

         // Create an account from this record
         UUID account;
         if (record.hasPrivateKey()) {
            try {
               account = _walletManager.createSingleAddressAccount(record.key, AesKeyCipher.defaultKeyCipher());
            } catch (KeyCipher.InvalidKeyCipher invalidKeyCipher) {
               throw new RuntimeException(invalidKeyCipher);
            }
         } else {
            account = _walletManager.createSingleAddressAccount(record.address);
         }

         //check whether this was the selected record
         if (record.address.toString().equals(lastAddress)) {
            setSelectedAccount(account);
         }

         //check whether the record was archived
         if (record.tag.equals(Record.Tag.ARCHIVE)) {
            _walletManager.getAccount(account).archiveAccount();
         }

         // See if we need to migrate this account to local trader
         if (record.address.equals(localTraderAddress)) {
            if (record.hasPrivateKey()) {
               _localTraderManager.setLocalTraderData(account, record.key, record.address, _localTraderManager.getNickname());
            } else {
               _localTraderManager.unsetLocalTraderAccount();
            }
         }
      }

      migrateAddressAndAccountLabelsToMetadataStorage();
   }

   private List<Record> loadClassicRecords() {
      SharedPreferences prefs = _applicationContext.getSharedPreferences("data", Context.MODE_PRIVATE);
      List<Record> recordList = new LinkedList<Record>();

      // Load records
      String records = prefs.getString("records", "");
      for (String one : records.split(",")) {
         one = one.trim();
         if (one.length() == 0) {
            continue;
         }
         Record record = Record.fromSerializedString(one);
         if (record != null) {
            recordList.add(record);
         }
      }

      // Sort all records
      Collections.sort(recordList);
      return recordList;
   }

   private void migrateAddressAndAccountLabelsToMetadataStorage() {
      List<AddressBookManager.Entry> entries = _addressBookManager.getEntries();
      for (AddressBookManager.Entry entry : entries) {

         Address address = entry.getAddress();
         String label = entry.getName();
         //check whether this is actually an addressbook entry, or the name of an account
         UUID accountid = SingleAddressAccount.calculateId(address);
         if (_walletManager.getAccountIds().contains(accountid)) {
            //its one of our accounts, so we name it
            _storage.storeAccountLabel(accountid, label);
         } else {
            //we just put it into the addressbook
            _storage.storeAddressLabel(address, label);
         }
      }
   }

   /**
    * Create a Wallet Manager instance
    *
    * @param context     the application context
    * @param environment the Mycelium environment
    * @return a new wallet manager instance
    */
   private WalletManager createWalletManager(final Context context, MbwEnvironment environment) {
      WalletManagerBacking backing;
      SecureKeyValueStore secureKeyValueStore;


      // Create persisted account backing
      backing = new SqliteWalletManagerBackingWrapper(context);

      // Create persisted secure storage instance
      secureKeyValueStore = new SecureKeyValueStore(backing,
            new AndroidRandomSource());

      ExternalSignatureProviderProxy externalSignatureProviderProxy = new ExternalSignatureProviderProxy(
            getTrezorManager(),
            getLedgerManager()
      );

      // Create and return wallet manager
      return new WalletManager(secureKeyValueStore,
            backing, environment.getNetwork(), _wapi, externalSignatureProviderProxy);
   }

   /**
    * Create a Wallet Manager instance for temporary accounts just backed by in-memory persistence
    *
    * @param context     the application context
    * @param environment the Mycelium environment
    * @return a new in memory backed wallet manager instance
    */
   private WalletManager createTempWalletManager(final Context context, MbwEnvironment environment) {

      // Create in-memory account backing
      WalletManagerBacking backing = new InMemoryWalletManagerBacking();

      // Create secure storage instance
      SecureKeyValueStore secureKeyValueStore = new SecureKeyValueStore(backing, new AndroidRandomSource());

      // Create and return wallet manager
      WalletManager walletManager = new WalletManager(secureKeyValueStore,
            backing, environment.getNetwork(), _wapi, null);

      walletManager.disableTransactionHistorySynchronization();
      return walletManager;
   }

   public String getFiatCurrency() {
      return _currencySwitcher.getCurrentFiatCurrency();
   }

   public boolean hasFiatCurrency() {
      return !getCurrencyList().isEmpty();
   }

   private SharedPreferences getPreferences() {
      return _applicationContext.getSharedPreferences(Constants.SETTINGS_NAME, Activity.MODE_PRIVATE);
   }

   public List<String> getCurrencyList() {
      return _currencySwitcher.getCurrencyList();
   }

   public void setCurrencyList(List<String> currencies) {
      _exchangeRateManager.setCurrencyList(currencies);
      _currencySwitcher.setCurrencyList(currencies);

      SharedPreferences.Editor editor = getEditor();
      editor.putStringSet(Constants.SELECTED_CURRENCIES, new HashSet<String>(currencies));
      editor.commit();
   }

   public boolean nextCurrencyReady() {
      List<String> currencies = getCurrencyList();
      if (currencies.isEmpty()) {
         return false;
      }
      int index = currencies.indexOf(getFiatCurrency());
      Preconditions.checkState(index >= 0);
      index++; //hop one forward
      if (index >= currencies.size()) {
         index -= currencies.size(); //wrap around
      }
      String newCurrency = currencies.get(index);
      if (_exchangeRateManager.getExchangeRate(newCurrency) == null) {
         _exchangeRateManager.requestRefresh();
         return false;
      }
      return true;
   }

   public String getNextCurrency(boolean includeBitcoin) {
      return _currencySwitcher.getNextCurrency(includeBitcoin);
   }

   private SharedPreferences.Editor getEditor() {
      return getPreferences().edit();
   }

   public LocalTraderManager getLocalTraderManager() {
      return _localTraderManager;
   }

   public ExchangeRateManager getExchangeRateManager() {
      return _exchangeRateManager;
   }

   public CurrencySwitcher getCurrencySwitcher() {
      return _currencySwitcher;
   }

   public AddressBookManager getAddressBookManager() {
      return _addressBookManager;
   }

   public boolean isPinProtected() {
      return getPin().isSet();
   }

   // returns the age of the PIN in blocks (~10min)
   public Optional<Integer> getRemainingPinLockdownDuration() {
      Optional<Integer> pinSetHeight = getMetadataStorage().getLastPinSetBlockheight();
      if (!pinSetHeight.isPresent()) {
         return Optional.absent();
      }

      int blockHeight = getSelectedAccount().getBlockChainHeight();

      int pinAge = blockHeight - pinSetHeight.get();
      if (pinAge > Constants.MIN_PIN_BLOCKHEIGHT_AGE_ADDITIONAL_BACKUP) {
         return Optional.of(0);
      } else {
         return Optional.of(Constants.MIN_PIN_BLOCKHEIGHT_AGE_ADDITIONAL_BACKUP - pinAge);
      }
   }

   public boolean isPinOldEnough() {
      if (!isPinProtected()) {
         return false;
      }

      Optional<Integer> pinLockdownDuration = getRemainingPinLockdownDuration();
      if (!pinLockdownDuration.isPresent()) {
         // PIN height was not set (older version) - take the current height and let the user wait...
         setPinBlockheight();
         return false;
      }
      return !(pinLockdownDuration.get() > 0);
   }

   public Pin getPin() {
      return _pin;
   }

   public void showClearPinDialog(final Context context, final Optional<Runnable> afterDialogClosed) {
      this.runPinProtectedFunction(context, new ClearPinDialog(context, true), new Runnable() {
         @Override
         public void run() {
            MbwManager.this.savePin(Pin.CLEAR_PIN);
            Toast.makeText(_applicationContext, R.string.pin_cleared, Toast.LENGTH_LONG).show();
            if (afterDialogClosed.isPresent()) {
               afterDialogClosed.get().run();
            }
         }
      });
   }

   public void showSetPinDialog(final Context context, final Optional<Runnable> afterDialogClosed) {

      // Must make a backup before setting PIN
      if (this.getMetadataStorage().getMasterSeedBackupState() != MetadataStorage.BackupState.VERIFIED) {
         Utils.showSimpleMessageDialog(context, R.string.pin_backup_first);
         return;
      }


      final NewPinDialog _dialog = new NewPinDialog(context, false);
      _dialog.setOnPinValid(new PinDialog.OnPinEntered() {
         private String newPin = null;

         @Override
         public void pinEntered(PinDialog dialog, Pin pin) {
            if (newPin == null) {
               newPin = pin.getPin();
               dialog.setTitle(R.string.pin_confirm_pin);
            } else if (newPin.equals(pin.getPin())) {
               MbwManager.this.savePin(pin);
               Toast.makeText(context, R.string.pin_set, Toast.LENGTH_LONG).show();
               dialog.dismiss();
               if (afterDialogClosed.isPresent()) {
                  afterDialogClosed.get().run();
               }
            } else {
               Toast.makeText(context, R.string.pin_codes_dont_match, Toast.LENGTH_LONG).show();
               MbwManager.this.vibrate(500);
               dialog.dismiss();
               if (afterDialogClosed.isPresent()) {
                  afterDialogClosed.get().run();
               }
            }
         }
      });


      this.runPinProtectedFunction(context, new Runnable() {
         @Override
         public void run() {
            _dialog.show();
         }
      });
   }

   public void savePin(Pin pin) {
      // if we were not pin protected and get a new pin, remember the blockheight
      // at which the pin was set - so that we can measure the age of the pin.
      if (!isPinProtected()) {
         setPinBlockheight();
      } else {
         // if we were pin-protected and now the pin is removed, reset the blockheight
         if (!pin.isSet()) {
            getMetadataStorage().clearLastPinSetBlockheight();
         }
      }
      _pin = pin;
      getEditor().putString(Constants.PIN_SETTING, _pin.getPin()).commit();
      getEditor().putString(Constants.PIN_SETTING_RESETTABLE, pin.isResettable() ? "1" : "0").commit();
   }

   private void setPinBlockheight() {
      int blockHeight = getSelectedAccount().getBlockChainHeight();
      getMetadataStorage().setLastPinSetBlockheight(blockHeight);
   }

   // returns the PinDialog or null, if no pin was needed
   public PinDialog runPinProtectedFunction(final Context context, final Runnable fun, boolean cancelable) {
      return runPinProtectedFunctionInternal(context, fun, cancelable);
   }

   // returns the PinDialog or null, if no pin was needed
   public PinDialog runPinProtectedFunction(final Context context, final Runnable fun) {
      return runPinProtectedFunctionInternal(context, fun, true);
   }

   // returns the PinDialog or null, if no pin was needed
   private PinDialog runPinProtectedFunctionInternal(Context context, Runnable fun, boolean cancelable) {
      // if last Pin entry was 1sec ago, dont ask for it again.
      // to prevent if there are two pin protected functions cascaded
      // like startup-pin request and account-choose-pin request if opened by a bitcoin url
      boolean lastPinAgeOkay = false;
      if (lastSuccessfullPin != null) {
         lastPinAgeOkay = (new Date().getTime() - lastSuccessfullPin.getTime()) < 1 * 1000;
      }

      if (isPinProtected() && !lastPinAgeOkay) {
         PinDialog d = new PinDialog(context, true, cancelable);
         runPinProtectedFunction(context, d, fun);
         return d;
      } else {
         fun.run();
         return null;
      }
   }


   protected void runPinProtectedFunction(final Context context, PinDialog pinDialog, final Runnable fun) {
      if (isPinProtected()) {
         pinDialog.setOnPinValid(new PinDialog.OnPinEntered() {
            @Override
            public void pinEntered(final PinDialog pinDialog, Pin pin) {
               if (pin.equals(getPin())) {
                  pinDialog.dismiss();

                  // as soon as you enter the correct pin once, abort the reset-pin-procedure
                  MbwManager.this.getMetadataStorage().clearResetPinStartBlockheight();
                  MbwManager.this.lastSuccessfullPin = new Date();

                  fun.run();
               } else {
                  if (_pin.isResettable()) {
                     // Show hint, that this pin is resettable
                     AlertDialog d = new AlertDialog.Builder(context)
                           .setTitle(R.string.pin_invalid_pin)
                           .setPositiveButton(context.getString(R.string.ok), new DialogInterface.OnClickListener() {
                              @Override
                              public void onClick(DialogInterface dialogInterface, int i) {
                                 pinDialog.dismiss();
                              }
                           })
                           .setNeutralButton(context.getString(R.string.reset_pin_button), new DialogInterface.OnClickListener() {
                              @Override
                              public void onClick(DialogInterface dialogInterface, int i) {
                                 pinDialog.dismiss();
                                 MbwManager.this.showClearPinDialog(context, Optional.<Runnable>absent());
                              }
                           })

                           .setMessage(context.getString(R.string.wrong_pin_message))
                           .show();
                  } else {
                     // This pin is not resettable, you are out of luck
                     Toast.makeText(context, R.string.pin_invalid_pin, Toast.LENGTH_LONG).show();
                     vibrate(500);
                     pinDialog.dismiss();
                  }
               }
            }
         });
         pinDialog.show();
      } else {
         fun.run();
      }
   }

   public void startResetPinProcedure() {
      getMetadataStorage().setResetPinStartBlockheight(getSelectedAccount().getBlockChainHeight());
   }

   public Optional<Integer> getResetPinRemainingBlocksCount() {
      Optional<Integer> resetPinStartBlockHeight = getMetadataStorage().getResetPinStartBlockHeight();
      if (!resetPinStartBlockHeight.isPresent()) {
         // no reset procedure ongoing
         return Optional.absent();
      } else {
         int blockAge = getSelectedAccount().getBlockChainHeight() - resetPinStartBlockHeight.get();
         return Optional.of(Math.max(0, Constants.MIN_PIN_BLOCKHEIGHT_AGE_RESET_PIN - blockAge));
      }
   }

   public void vibrate() {
      vibrate(500);
   }

   public void vibrate(int milliseconds) {
      Vibrator v = (Vibrator) _applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
      if (v != null) {
         v.vibrate(milliseconds);
      }
   }

   public MinerFee getMinerFee() {
      return _minerFee;
   }

   public void setMinerFee(MinerFee minerFee) {
      _minerFee = minerFee;
      getEditor().putString(Constants.MINER_FEE_SETTING, _minerFee.toString()).commit();
   }

   public void setBlockExplorer(BlockExplorer blockExplorer) {
      _blockExplorerManager.setBlockExplorer(blockExplorer);
      getEditor().putString(Constants.BLOCK_EXPLORER, blockExplorer.getIdentifier()).commit();
   }


   public CoinUtil.Denomination getBitcoinDenomination() {
      return _currencySwitcher.getBitcoinDenomination();
   }

   public void setBitcoinDenomination(CoinUtil.Denomination denomination) {
      _currencySwitcher.setBitcoinDenomination(denomination);
      getEditor().putString(Constants.BITCOIN_DENOMINATION_SETTING, denomination.toString()).commit();
   }

   public String getBtcValueString(long satoshis) {
      return _currencySwitcher.getBtcValueString(satoshis);
   }

   public boolean isKeyManagementLocked() {
      return _keyManagementLocked;
   }

   public void setKeyManagementLocked(boolean locked) {
      _keyManagementLocked = locked;
      getEditor().putBoolean(Constants.KEY_MANAGEMENT_LOCKED_SETTING, _keyManagementLocked).commit();
   }

   public boolean getContinuousFocus() {
      return _enableContinuousFocus;
   }

   public void setContinuousFocus(boolean enableContinuousFocus) {
      _enableContinuousFocus = enableContinuousFocus;
      getEditor().putBoolean(Constants.ENABLE_CONTINUOUS_FOCUS_SETTING, _enableContinuousFocus).commit();
   }


   public void setProxy(String proxy) {
      getEditor().putString(Constants.PROXY_SETTING, proxy).commit();
      ImmutableList<String> vals = ImmutableList.copyOf(Splitter.on(":").split(proxy));
      if (vals.size() != 2) {
         noProxy();
         return;
      }
      Integer portNumber = Ints.tryParse(vals.get(1));
      if (portNumber == null || portNumber < 1 || portNumber > 65535) {
         noProxy();
         return;
      }
      String hostname = vals.get(0);
      System.setProperty(PROXY_HOST, hostname);
      System.setProperty(PROXY_PORT, portNumber.toString());
   }

   private void noProxy() {
      System.clearProperty(PROXY_HOST);
      System.clearProperty(PROXY_PORT);
   }

   public MrdExport.V1.EncryptionParameters getCachedEncryptionParameters() {
      return _cachedEncryptionParameters;
   }

   public void setCachedEncryptionParameters(MrdExport.V1.EncryptionParameters cachedEncryptionParameters) {
      _cachedEncryptionParameters = cachedEncryptionParameters;
   }

   public void clearCachedEncryptionParameters() {
      _cachedEncryptionParameters = null;
   }

   public Bus getEventBus() {
      return _eventBus;
   }

   /**
    * Get the Bitcoin network parameters that the wallet operates on
    */
   public NetworkParameters getNetwork() {
      return _environment.getNetwork();
   }

   /**
    * Get the brand of the wallet. This allows us to behave differently
    * depending on the brand of the wallet.
    */
   public String getBrand() {
      return _environment.getBrand();
   }

   public boolean isBitidEnabled() {
      return _isBitidEnabled;
   }

   public ExploreHelper getExploreHelper() {
      return exploreHelper;
   }

   public void reportIgnoredException(Throwable e) {
      if (_httpErrorCollector != null) {
         RuntimeException msg = new RuntimeException("We caught an exception that we chose to ignore.\n", e);
         _httpErrorCollector.reportErrorToServer(msg);
      }
   }

   public void reportIgnoredException(String Message, Throwable e) {
      if (_httpErrorCollector != null) {
         RuntimeException msg = new RuntimeException("We caught an exception that we chose to ignore.\n" + Message + "\n", e);
         _httpErrorCollector.reportErrorToServer(msg);
      }
   }

   public String getLanguage() {
      return _language;
   }

   public Locale getLocale() {
      return new Locale(_language);
   }

   public void setLanguage(String _language) {
      this._language = _language;
      SharedPreferences.Editor editor = getEditor();
      editor.putString(Constants.LANGUAGE_SETTING, _language);
      editor.commit();
   }

   public void setTorMode(ServerEndpointType.Types torMode) {
      this._torMode = torMode;
      SharedPreferences.Editor editor = getEditor();
      editor.putString(Constants.TOR_MODE, torMode.toString());
      editor.commit();

      ServerEndpointType serverEndpointType = ServerEndpointType.fromType(torMode);
      if (serverEndpointType.mightUseTor()) {
         initTor();
      } else {
         if (_torManager != null) {
            _torManager.stopClient();
         }
      }

      _environment.getWapiEndpoints().setAllowedEndpointTypes(serverEndpointType);
      _environment.getLtEndpoints().setAllowedEndpointTypes(serverEndpointType);
   }

   public ServerEndpointType.Types getTorMode() {
      return _torMode;
   }

   public VersionManager getVersionManager() {
      return _versionManager;
   }

   public MrdExport.V1.ScryptParameters getDeviceScryptParameters() {
      return _deviceScryptParameters;
   }

   public WalletManager getWalletManager(boolean isColdStorage) {
      if (isColdStorage) {
         return _tempWalletManager;
      }
      return _walletManager;
   }

   public UUID createOnTheFlyAccount(Address address) {
      UUID accountId = _tempWalletManager.createSingleAddressAccount(address);
      _tempWalletManager.getAccount(accountId).setAllowZeroConfSpending(true);
      _tempWalletManager.startSynchronization();
      return accountId;
   }

   public UUID createOnTheFlyAccount(InMemoryPrivateKey privateKey) {
      UUID accountId;
      try {
         accountId = _tempWalletManager.createSingleAddressAccount(privateKey, AesKeyCipher.defaultKeyCipher());
      } catch (KeyCipher.InvalidKeyCipher invalidKeyCipher) {
         throw new RuntimeException(invalidKeyCipher);
      }
      _tempWalletManager.getAccount(accountId).setAllowZeroConfSpending(true);
      return accountId;
   }


   public void forgetColdStorageWalletManager() {
      createTempWalletManager();
   }

   public WalletAccount getSelectedAccount() {
      // Get the selected account ID
      String uuidStr = getPreferences().getString(SELECTED_ACCOUNT, "");
      UUID uuid = null;
      if (uuidStr.length() != 0) {
         try {
            uuid = UUID.fromString(uuidStr);
         } catch (IllegalArgumentException e) {
            // default to null and select another account below
         }
      }

      if (coinapultManager.isPresent() && coinapultManager.get().getId().equals(uuid)) {
         return coinapultManager.get();
      }

      // If nothing is selected, or selected is archived, pick the first one
      if (uuid == null || !_walletManager.hasAccount(uuid) || _walletManager.getAccount(uuid).isArchived()) {
         uuid = _walletManager.getActiveAccounts().get(0).getId();
         setSelectedAccount(uuid);
      }

      return _walletManager.getAccount(uuid);
   }

   public void setSelectedAccount(UUID uuid) {
      final WalletAccount account;
      account = _walletManager.getAccount(uuid);
      Preconditions.checkState(account.isActive());
      getEditor().putString(SELECTED_ACCOUNT, uuid.toString()).commit();
      getEventBus().post(new SelectedAccountChanged(uuid));
      Optional<Address> receivingAddress = account.getReceivingAddress();
      getEventBus().post(new ReceivingAddressChanged(receivingAddress));
      // notify the wallet manager that this is the active account now
      _walletManager.setActiveAccount(account.getId());
   }

   public InMemoryPrivateKey obtainPrivateKeyForAccount(WalletAccount account, String website, KeyCipher cipher) {
      if (account instanceof SingleAddressAccount) {
         // For single address accounts we use the private key directly
         try {
            return ((SingleAddressAccount) account).getPrivateKey(cipher);
         } catch (KeyCipher.InvalidKeyCipher invalidKeyCipher) {
            throw new RuntimeException();
         }
      } else if (account instanceof Bip44Account && ((Bip44Account) account).getAccountType() == Bip44AccountContext.ACCOUNT_TYPE_FROM_MASTERSEED) {
         // For BIP44 accounts we derive a private key from the BIP32 hierarchy
         try {
            Bip39.MasterSeed masterSeed = _walletManager.getMasterSeed(cipher);
            int accountIndex = ((Bip44Account) account).getAccountIndex();
            return createBip32WebsitePrivateKey(masterSeed.getBip32Seed(), accountIndex, website);
         } catch (KeyCipher.InvalidKeyCipher invalidKeyCipher) {
            throw new RuntimeException(invalidKeyCipher);
         }
      } else {
         throw new RuntimeException("Invalid account type");
      }
   }

   public InMemoryPrivateKey getBitIdKeyForWebsite(String website) {
      try {
         IdentityAccountKeyManager identity = _walletManager.getIdentityAccountKeyManager(AesKeyCipher.defaultKeyCipher());
         return identity.getPrivateKeyForWebsite(website, AesKeyCipher.defaultKeyCipher());
      } catch (KeyCipher.InvalidKeyCipher invalidKeyCipher) {
         throw new RuntimeException(invalidKeyCipher);
      }
   }

   private InMemoryPrivateKey createBip32WebsitePrivateKey(byte[] masterSeed, int accountIndex, String site) {
      // Create BIP32 root node
      HdKeyNode rootNode = HdKeyNode.fromSeed(masterSeed);
      // Create bit id node
      HdKeyNode bidNode = rootNode.createChildNode(BIP32_ROOT_AUTHENTICATION_INDEX);
      // Create the private key for the specified account
      InMemoryPrivateKey accountPriv = bidNode.createChildPrivateKey(accountIndex);
      // Concatenate the private key bytes with the site name
      byte[] sitePrivateKeySeed = new byte[0];
      try {
         sitePrivateKeySeed = BitUtils.concatenate(accountPriv.getPrivateKeyBytes(), site.getBytes("UTF-8"));
      } catch (UnsupportedEncodingException e) {
         // Does not happen
         throw new RuntimeException(e);
      }
      // Hash the seed and create a new private key from that which uses compressed public keys
      byte[] sitePrivateKeyBytes = HashUtils.doubleSha256(sitePrivateKeySeed).getBytes();
      return new InMemoryPrivateKey(sitePrivateKeyBytes, true);
   }

   public boolean isWalletPaired(ExternalService service) {
      return getMetadataStorage().isPairedService(service.getHost(getNetwork()));
   }

   public MetadataStorage getMetadataStorage() {
      return _storage;
   }

   public RandomSource getRandomSource() {
      return _randomSource;
   }

   public TrezorManager getTrezorManager() {
      return _trezorManager;
   }
   
   public LedgerManager getLedgerManager() {
	   return _ledgerManager;
   }

   public WapiClient getWapi() {
      return _wapi;
   }

   public EvictingQueue<LogEntry> getWapiLogs() {
      return _wapiLogs;
   }

   public TorManager getTorManager() {
      return _torManager;
   }

   public LtApiClient getLtApi() {
      return _ltApi;
   }

   @Subscribe
   public void onSelectedCurrencyChanged(SelectedCurrencyChanged event) {
      SharedPreferences.Editor editor = getEditor();
      editor.putString(Constants.FIAT_CURRENCY_SETTING, _currencySwitcher.getCurrentFiatCurrency());
      editor.commit();

   }

   public boolean getPinRequiredOnStartup() {
      return this._pinRequiredOnStartup;
   }

   public boolean isUnlockPinRequired() {
      return getPinRequiredOnStartup() && !startUpPinUnlocked;
   }

   public void setStartUpPinUnlocked(boolean unlocked) {
      this.startUpPinUnlocked = unlocked;
   }

   public void setPinRequiredOnStartup(boolean _pinRequiredOnStartup) {
      SharedPreferences.Editor editor = getEditor();
      editor.putBoolean(Constants.PIN_SETTING_REQUIRED_ON_STARTUP, _pinRequiredOnStartup);
      editor.commit();

      this._pinRequiredOnStartup = _pinRequiredOnStartup;
   }

   /**
    * this should only be called if a coinapult account was created once.
    *
    * @return
    */
   public CoinapultManager getCoinapultManager() {
      if (coinapultManager.isPresent()) {
         return coinapultManager.get();
      } else {
         //lazily create one
         coinapultManager = createCoinapultManager();
         //still not certain, if user never created one
         if (coinapultManager.isPresent()) {
            _walletManager.setExtraAccount(coinapultManager);
            return coinapultManager.get();
         } else {
            throw new IllegalStateException("tried to obtain coinapult manager without having created one");
         }
      }
   }

   public Cache<String, Object> getBackgroundObjectsCache() {
      return _semiPersistingBackgroundObjects;
   }

   public void switchServer() {
      _environment.getWapiEndpoints().switchToNextEndpoint();
   }
}
