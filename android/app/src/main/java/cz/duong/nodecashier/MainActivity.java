package cz.duong.nodecashier;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import java.util.HashMap;
import java.util.Map;

import cz.duong.nodecashier.printer.BluetoothService;
import cz.duong.nodecashier.termux.Task;
import cz.duong.nodecashier.termux.TermuxService;
import cz.duong.nodecashier.utils.LauncherUtils;
import cz.duong.nodecashier.utils.UrlChecker;

import static cz.duong.nodecashier.printer.BluetoothService.MESSAGE_CONNECTION_LOST;
import static cz.duong.nodecashier.printer.BluetoothService.MESSAGE_STATE_CHANGE;
import static cz.duong.nodecashier.printer.BluetoothService.MESSAGE_UNABLE_CONNECT;
import static cz.duong.nodecashier.printer.BluetoothService.STATE_LISTEN;
import static cz.duong.nodecashier.printer.BluetoothService.STATE_NONE;
import static cz.duong.nodecashier.termux.EmulatorDebug.LOG_TAG;
import static cz.duong.nodecashier.termux.TermuxService.ACTION_STOP_SERVICE;

public class MainActivity extends Activity implements AppInterface.Listener, ServiceConnection, UrlChecker.CheckListener {

    private final static int MAX_ATTEMPTS = 3;
    private final static int DELAY_FACTOR = 3000;

    private final static boolean FORCE_STAY = false;
    private final static boolean FORCE_WAKE = false;
    private final static boolean SINGLE_BACK_MENU = true;

    public static final int INPUT_FILE_REQUEST_CODE = 1;
    public static final int BT_REQUEST_CODE = 2;

    public static final String KEY_MIMETYPE = "application/x-pkcs12";

    private WebView webView;
    private View errorView;
    private ProgressBar progressBar;
    private Snackbar snackbar;

    private PowerManager.WakeLock wakeLock;

    private Map<String, String> actions = new HashMap<>();

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private TermuxService termuxService;

    private BluetoothService bluetoothService;
    private ValueCallback<Uri[]> mFilePathCallback;

    private boolean isClosing = false;
    private boolean isPrinterConnected = false;
    private int attempts = 0;

    private BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (FORCE_WAKE) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

                //noinspection deprecation
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "NW-WAKELOCK");
                wakeLock.acquire();
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        LauncherUtils.bindFullscreenMode(this);

        if (AppPreferences.shouldFirstInit(this)) {
            showSetup();
            return;
        }

        if (FORCE_STAY && LauncherUtils.restartToLauncher(this)) {
            return;
        }

        setContentView(R.layout.activity_main);

        webView = (WebView) findViewById(R.id.webView);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        errorView = findViewById(R.id.main_error);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSaveFormData(false);

        webView.addJavascriptInterface(new AppInterface(webView, this), AppInterface.JS_NAME);
        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        });
        webView.setHapticFeedbackEnabled(false);
        WebView.setWebContentsDebuggingEnabled(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if(mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentSelectionIntent.setType(KEY_MIMETYPE);

                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "EET klíč");

                isClosing = true;
                startActivityForResult(chooserIntent, INPUT_FILE_REQUEST_CODE);
                return true;
            }
        });

        Button reloadButton = (Button) findViewById(R.id.retry_btn);
        reloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attempts = 0;
                loadPage();
            }
        });

        Button setupButton = (Button) findViewById(R.id.setup_btn);
        setupButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isClosing = true;

                Intent launcher = LauncherUtils.getOtherLauncher(MainActivity.this);
                startActivity(launcher);
                finish();
            }
        });

        showLoading();
        startPrinter();

        registerReceiver(powerReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPrinter();
        startServer();

        LauncherUtils.bindFullscreenMode(this);

        if (webView != null) webView.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            unbindService(this);
        } catch (Exception e) {
            Log.e("Test error", Log.getStackTraceString(e));
        }


        if (termuxService != null) {
            termuxService.onDestroy();
        }

        if (powerReceiver != null) {
            try {
                unregisterReceiver(powerReceiver);
            } catch (IllegalArgumentException ignored) {}

            powerReceiver = null;
        }

        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }

        stopPrinter();
        stopServer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (FORCE_STAY) {
            LauncherUtils.bindFullscreenMode(this);
        }
    }

    @Override
    public void onBackPressed() {
        if (!FORCE_STAY && !SINGLE_BACK_MENU) {
            super.onBackPressed();
        }

        if (SINGLE_BACK_MENU) {
            openMenu();
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (openMenu()) return true;
        }

        return super.onKeyLongPress(keyCode, event);
    }

    private boolean openMenu() {
        ExitDialog dialog = new ExitDialog(this, new ExitDialog.ExitInterface() {
            @Override
            public void onJavascriptFunction(String function) {
                if (webView != null) webView.loadUrl("javascript:"+function+"()");
            }

            @Override
            public void onExit() {
                isClosing = true;

                Intent launcher = LauncherUtils.getOtherLauncher(MainActivity.this);
                startActivity(launcher);
                finish();
            }

            @Override
            public void onReload() {
                showLoading();
                if (webView != null) webView.reload();
            }

            @Override
            public void onRediscover() {
                showSetup();
            }

            @Override
            public Map<String, String> getActions() {
                return actions;
            }
        });

        if (dialog.getWindow() == null) return false;

        dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.show();
        dialog.getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (!isClosing && FORCE_STAY) {
            ActivityManager am = (ActivityManager) getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
            am.moveTaskToFront(getTaskId(), 0);
        } else if (mFilePathCallback == null) {
            stopPrinter();
            stopServer();
        }
    }

    @Override
    public void onActivityResult (int requestCode, int resultCode, Intent data) {
//        LauncherUtils.setFullscreen(this);
        switch (requestCode) {
            case INPUT_FILE_REQUEST_CODE:
                if (mFilePathCallback == null) {
                    super.onActivityResult(requestCode, resultCode, data);
                    return;
                }

                Uri[] results = null;

                // Check that the response is a good one
                if(resultCode == Activity.RESULT_OK) {
                    if(data != null) {
                        String dataString = data.getDataString();
                        if (dataString != null) {
                            results = new Uri[]{Uri.parse(dataString)};
                        }
                    }
                }

                isClosing = false;
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
                break;
            case BT_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK) {
                    startPrinter();
                } else {
                    showSnackbar("Bluetooth je vypnutý", true);
                }
        }
    }

    @Override
    public void onAppLoaded(Map<String, String> actions) {
        this.actions = actions;
        this.attempts = 0;
        showBrowser();
    }

    @Override
    public void onNativePrint(byte[] buffer) {
        bluetoothService.write(buffer);
    }

    void loadPage() {
        if (attempts < MAX_ATTEMPTS) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    showLoading();
                }
            });

            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attempts += 1;

                    Log.d(LOG_TAG, "Attempting to load: "+ AppPreferences.getServerUrl(MainActivity.this));
                    showLoading();

                    new UrlChecker(MainActivity.this).execute(AppPreferences.getServerUrl(MainActivity.this));
                }
            }, attempts * DELAY_FACTOR);
        } else {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    showError();
                }
            });
        }
    }

    void showBrowser() {
        if (webView != null) webView.setVisibility(View.VISIBLE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    void showLoading() {
        if (webView != null) webView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
    }

    void showError() {
        if (webView != null) webView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.VISIBLE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    void showSetup() {
        isClosing = true;
        Intent intent = new Intent(this, SetupActivity.class);
        startActivity(intent);
        finish();
    }

    void showSnackbar(String info, boolean infinite) {
        if (snackbar != null && snackbar.isShown()) {
            snackbar.dismiss();
        }

        snackbar = Snackbar.make(findViewById(android.R.id.content), info, infinite ? Snackbar.LENGTH_INDEFINITE : Snackbar.LENGTH_LONG);
        snackbar.setAction("Zavřít", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (snackbar != null) {
                    snackbar.dismiss();
                }
            }
        });
        Snackbar.SnackbarLayout layout = (Snackbar.SnackbarLayout)snackbar.getView();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) layout.getLayoutParams();
        params.gravity = Gravity.END | Gravity.TOP;
        params.topMargin = 20;
        params.rightMargin = 20;
        params.leftMargin = 20;

        layout.setLayoutParams(params);

        snackbar.show();
    }

    void setupPrinter() {
        Handler bluetoothHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MESSAGE_STATE_CHANGE:
                        if (msg.arg1 == BluetoothService.STATE_CONNECTED) {
                            if (webView != null) webView.loadUrl("javascript:nativePrinterStatus(true)");
                            isPrinterConnected = true;
                            // showSnackbar("Tiskárna připojena", true);
                        }
                        break;
                    case MESSAGE_CONNECTION_LOST:
                    case MESSAGE_UNABLE_CONNECT:
                        if (isPrinterConnected) {
                            if (webView != null) webView.loadUrl("javascript:nativePrinterStatus(false)");
                            isPrinterConnected = false;
                            // showSnackbar("Nelze se připojit k tiskárně", true);
                        }
                        break;
                }
            }
        };

        bluetoothService = new BluetoothService(bluetoothHandler);
    }

    void startPrinter() {
        String address = AppPreferences.getBtAddress(this);
        if (address == null) return;

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, BT_REQUEST_CODE);
            return;
        }

        if (bluetoothService == null) {
            setupPrinter();
        }

        if (bluetoothService.getState() == STATE_NONE || bluetoothService.getState() == STATE_LISTEN) {
            Log.d("MainActivity", "Starting printer");
            bluetoothService.connect(bluetoothAdapter.getRemoteDevice(address));
        }
    }

    void stopPrinter() {
        Log.d("MainActivity", "Stopping printer");
        if (bluetoothService != null) {
            bluetoothService.stop();
            bluetoothService = null;
        }
    }

    void startServer() {
        Intent serviceIntent = new Intent(this, TermuxService.class);

        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0)) {
            throw new RuntimeException("bindService() failed");
        }
    }

    void stopServer() {
        if (AppPreferences.shouldRunServer(MainActivity.this)) {
            startService(new Intent(MainActivity.this, TermuxService.class).setAction(ACTION_STOP_SERVICE));
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        termuxService = ((TermuxService.LocalBinder) service).service;
        termuxService.mListener = new TermuxService.TaskListener() {
            @Override
            public void onStarted(String name) {}

            @Override
            public void onStopped(String name, int exitCode) {
                if (Task.fromName(name) == Task.RUN) {
                    loadPage();
                }
            }
        };

        if (AppPreferences.shouldRunServer(this)) {
            TermuxService.runScript(Task.RUN, this, this);
        } else {
            loadPage();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        termuxService = null;
    }

    @Override
    public void onUrlValid(String url, boolean valid) {
        if (!valid) {
            loadPage();
        } else {
            if (webView != null) webView.loadUrl(url);
        }
    }

    @Override
    public boolean hasPrinterAdded() {
        return isPrinterConnected;
    }
}
