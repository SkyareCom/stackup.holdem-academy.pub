package com.skyare.stackupacademy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONObject;

public class MainActivity extends FragmentActivity {
    private static final String APP_URL = "https://skyarecom.github.io/stackup.holdem-academy.pub/";
    private static final String APP_HOST = "skyarecom.github.io";
    private static final String APP_PATH = "/stackup.holdem-academy.pub/";
    private static final String LOCAL_ASSET_HOST = "appassets.androidplatform.net";
    private static final String LOCAL_ASSET_PATH = "/assets/";
    private static final String LOCAL_ASSET_URL =
            "https://" + LOCAL_ASSET_HOST + LOCAL_ASSET_PATH;
    private static final String TAG = "StackUpAcademy";
    private static final String PREFS = "stackup_android_shell";
    private static final String CACHE_SCHEMA_KEY = "cache_schema";
    private static final int CACHE_SCHEMA = 221;
    private static final String APP_ENTRY_URL =
            APP_URL + "index.html?android_build=221&source=remote";
    private static final String RECOVERY_URL =
            APP_URL + "index.html?android_build=221&cache_reset=1";
    private static final String LOCAL_ENTRY_URL =
            LOCAL_ASSET_URL + "index.html?android_build=221&source=local";

    private WebView webView;
    private FrameLayout root;
    private OnBackInvokedCallback backCallback;
    private boolean webRecoveryPending;
    private boolean cleanupStarted;
    private boolean localFallbackAttempted;
    private boolean rendererRecoveryAttempted;
    private BillingManager billingManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(3, 23, 11));
        getWindow().setNavigationBarColor(Color.rgb(3, 23, 11));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(3, 23, 11));
        setContentView(root);
        showLoadingMessage();
        billingManager = new BillingManager(this, this::callJavascript);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::handleBackNavigation;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback);
        }

        try {
            Log.i(TAG, "SHELL_CREATE version=221");
            createAndLoadWebView(savedInstanceState);
        } catch (Throwable error) {
            Log.e(TAG, "SHELL_CREATE_FAILED", error);
            showPermanentError();
        }
    }

    private void showLoadingMessage() {
        if (root == null) return;
        root.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("STACKUP HOLD'EM ACADEMY\n\nCarregando...");
        loading.setTextColor(Color.WHITE);
        loading.setTextSize(18f);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(32, 32, 32, 32);
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createAndLoadWebView(Bundle savedInstanceState) {
        try {
            webView = new WebView(this);
        } catch (Throwable error) {
            Log.e(TAG, "WEBVIEW_CREATE_FAILED", error);
            showPermanentError();
            return;
        }

        webView.setBackgroundColor(Color.rgb(3, 23, 11));

        boolean isDebuggable =
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(isDebuggable);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        webRecoveryPending = prefs.getInt(CACHE_SCHEMA_KEY, 0) < CACHE_SCHEMA;
        if (webRecoveryPending) {
            webView.clearCache(true);
        }

        webView.addJavascriptInterface(new NativeAuthBridge(), "StackUpNative");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (webRecoveryPending) {
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        root.removeAllViews();
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "CONSOLE " + consoleMessage.messageLevel() + ": "
                        + consoleMessage.message());
                return true;
            }
        });

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request) {
                WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
                if (request.isForMainFrame() && response != null) {
                    Log.i(TAG, "LOCAL_FALLBACK_MAIN_FRAME=true url=" + request.getUrl());
                }
                return response;
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e(TAG, "WEB_RENDERER_GONE");
                if (view != null) {
                    try {
                        if (view.getParent() instanceof ViewGroup) {
                            ((ViewGroup) view.getParent()).removeView(view);
                        }
                        view.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                webView = null;

                if (!rendererRecoveryAttempted) {
                    rendererRecoveryAttempted = true;
                    root.postDelayed(() -> {
                        showLoadingMessage();
                        try {
                            createAndLoadWebView(null);
                        } catch (Throwable error) {
                            Log.e(TAG, "WEB_RENDERER_RECOVERY_FAILED", error);
                            showPermanentError();
                        }
                    }, 250);
                } else {
                    showPermanentError();
                }
                return true;
            }

            @Override
            public void onReceivedHttpError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceResponse errorResponse) {
                if (request == null || !request.isForMainFrame()) {
                    return;
                }

                int statusCode = errorResponse == null ? 0 : errorResponse.getStatusCode();
                Log.e(TAG, "MAIN_FRAME_HTTP_ERROR status=" + statusCode
                        + " url=" + request.getUrl());

                loadLocalFallback(view, "http_" + statusCode);
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error) {
                if (request == null || !request.isForMainFrame()) {
                    return;
                }

                Log.e(TAG, "MAIN_FRAME_ERROR code=" + error.getErrorCode()
                        + " description=" + error.getDescription()
                        + " url=" + request.getUrl());

                loadLocalFallback(view, "network_" + error.getErrorCode());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "PAGE_FINISHED url=" + url + " recovery=" + webRecoveryPending);
                if (url != null && url.startsWith(APP_URL)) {
                    Log.i(TAG, "REMOTE_CONTENT_ACTIVE=true url=" + url);
                } else if (url != null && url.startsWith(LOCAL_ASSET_URL)) {
                    Log.i(TAG, "LOCAL_FALLBACK_ACTIVE=true url=" + url);
                }

                if (webRecoveryPending
                        && !cleanupStarted
                        && url != null
                        && url.startsWith(APP_URL)) {
                    cleanupStarted = true;
                    webRecoveryPending = false;
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putInt(CACHE_SCHEMA_KEY, CACHE_SCHEMA)
                            .apply();

                    String cleanupScript =
                            "(async()=>{try{" +
                            "if('caches' in window){const ks=await caches.keys();" +
                            "await Promise.all(ks.map(k=>caches.delete(k)));}" +
                            "if('serviceWorker' in navigator){const rs=await navigator.serviceWorker.getRegistrations();" +
                            "await Promise.all(rs.map(r=>r.unregister()));}" +
                            "}catch(e){}finally{" +
                            "window.location.replace('" + APP_ENTRY_URL + "&migrated=1');" +
                            "}})();";

                    view.evaluateJavascript(
                            cleanupScript,
                            value -> Log.i(TAG, "WEB_CACHE_CLEANUP_STARTED=" + value));
                    return;
                }

                view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);

                String scriptBase = null;
                if (url != null && url.startsWith(APP_URL)) {
                    scriptBase = APP_URL;
                } else if (url != null && url.startsWith(LOCAL_ASSET_URL)) {
                    scriptBase = LOCAL_ASSET_URL;
                }
                if (scriptBase != null) {
                    String authLoader =
                            "(function(){if(document.getElementById('stackup-auth-production'))return;" +
                            "var s=document.createElement('script');" +
                            "s.id='stackup-auth-production';" +
                            "s.src='" + scriptBase + "auth-production.js?v=221';" +
                            "document.head.appendChild(s);})();";
                    view.evaluateJavascript(authLoader, null);
                    String billingLoader =
                            "(function(){if(document.getElementById('stackup-billing-production'))return;" +
                            "var s=document.createElement('script');" +
                            "s.id='stackup-billing-production';" +
                            "s.src='" + scriptBase + "billing-production.js?v=221';" +
                            "document.head.appendChild(s);})();";
                    view.evaluateJavascript(billingLoader, null);
                }

                view.postDelayed(
                        () -> view.evaluateJavascript(
                                "(function(){return !!(document.querySelector('#app')&&document.querySelector('.screen'));})();",
                                value -> {
                                    Log.i(TAG, "WEB_CONTENT_READY=" + value);
                                    if (!"true".equals(value)
                                            && url != null
                                            && url.startsWith(APP_URL)) {
                                        loadLocalFallback(view, "content_not_ready");
                                    }
                                }),
                        1500);
            }
        });

        if (!webRecoveryPending
                && savedInstanceState != null
                && webView.restoreState(savedInstanceState) != null) {
            Log.i(TAG, "WEBVIEW_STATE_RESTORED");
            return;
        }

        String initialUrl = webRecoveryPending ? RECOVERY_URL + "&bootstrap=1" : APP_ENTRY_URL;
        Log.i(TAG, "LOAD_URL=" + initialUrl);
        webView.loadUrl(initialUrl);
    }

    private void loadLocalFallback(WebView view, String reason) {
        if (view == null) {
            showPermanentError();
            return;
        }
        Uri current = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
        if (LOCAL_ASSET_HOST.equalsIgnoreCase(current.getHost()) || localFallbackAttempted) {
            showPermanentError();
            return;
        }

        localFallbackAttempted = true;
        Log.w(TAG, "REMOTE_LOAD_FAILED fallback=local reason=" + reason);
        view.stopLoading();
        view.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        view.loadUrl(LOCAL_ENTRY_URL + "&reason=" + Uri.encode(reason == null ? "unknown" : reason));
    }

    private void callJavascript(String javascript) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(javascript, null);
            }
        });
    }

    private void notifyGoogleSuccess(String idToken, String email, String displayName) {
        String js = "window.StackUpProductionAuth&&window.StackUpProductionAuth.onGoogleToken(" +
                JSONObject.quote(idToken == null ? "" : idToken) + "," +
                JSONObject.quote(email == null ? "" : email) + "," +
                JSONObject.quote(displayName == null ? "" : displayName) + ");";
        callJavascript(js);
    }

    private void notifyAuthError(String method, String message) {
        String js = "window.StackUpProductionAuth&&window.StackUpProductionAuth.onNativeError(" +
                JSONObject.quote(method) + "," +
                JSONObject.quote(message == null ? "Authentication error" : message) + ");";
        callJavascript(js);
    }

    private void startGoogleSignIn() {
        runOnUiThread(() -> {
            try {
                CredentialManager credentialManager = CredentialManager.create(MainActivity.this);
                GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(getString(R.string.google_web_client_id))
                        .setAutoSelectEnabled(false)
                        .build();

                GetCredentialRequest request = new GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build();

                credentialManager.getCredentialAsync(
                        MainActivity.this,
                        request,
                        new CancellationSignal(),
                        command -> new Handler(Looper.getMainLooper()).post(command),
                        new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                            @Override
                            public void onResult(GetCredentialResponse result) {
                                try {
                                    if (!(result.getCredential() instanceof CustomCredential)) {
                                        notifyAuthError("google", "Unsupported Google credential");
                                        return;
                                    }

                                    CustomCredential credential =
                                            (CustomCredential) result.getCredential();
                                    if (!GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                            .equals(credential.getType())) {
                                        notifyAuthError("google", "Unexpected Google credential type");
                                        return;
                                    }

                                    GoogleIdTokenCredential google =
                                            GoogleIdTokenCredential.createFrom(credential.getData());
                                    notifyGoogleSuccess(
                                            google.getIdToken(),
                                            google.getId(),
                                            google.getDisplayName());
                                } catch (Throwable error) {
                                    Log.e(TAG, "GOOGLE_SIGN_IN_PARSE_FAILED", error);
                                    notifyAuthError("google", "Unable to read Google credential");
                                }
                            }

                            @Override
                            public void onError(GetCredentialException error) {
                                Log.w(TAG, "GOOGLE_SIGN_IN_FAILED", error);
                                notifyAuthError("google", "Google sign-in was cancelled or failed");
                            }
                        });
            } catch (Throwable error) {
                Log.e(TAG, "GOOGLE_SIGN_IN_START_FAILED", error);
                notifyAuthError("google", "Unable to start Google sign-in");
            }
        });
    }

    private void startBiometricUnlock() {
        runOnUiThread(() -> {
            int allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.BIOMETRIC_WEAK;
            int status = BiometricManager.from(MainActivity.this).canAuthenticate(allowed);
            if (status != BiometricManager.BIOMETRIC_SUCCESS) {
                notifyAuthError("biometric", "Biometric authentication is not available on this device");
                return;
            }

            BiometricPrompt prompt = new BiometricPrompt(
                    MainActivity.this,
                    command -> new Handler(Looper.getMainLooper()).post(command),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            callJavascript(
                                    "window.StackUpProductionAuth&&window.StackUpProductionAuth.onBiometricResult(true);");
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            notifyAuthError("biometric", String.valueOf(errString));
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();
                            callJavascript(
                                    "window.StackUpProductionAuth&&window.StackUpProductionAuth.onBiometricResult(false);");
                        }
                    });

            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("StackUp Hold'em Academy")
                    .setSubtitle("Confirme sua biometria para entrar")
                    .setAllowedAuthenticators(allowed)
                    .setNegativeButtonText("Cancelar")
                    .build();
            prompt.authenticate(info);
        });
    }

    private final class NativeAuthBridge {
        @JavascriptInterface
        public String getSupabaseUrl() {
            return BuildConfig.SUPABASE_URL;
        }

        @JavascriptInterface
        public String getSupabaseAnonKey() {
            return BuildConfig.SUPABASE_ANON_KEY;
        }

        @JavascriptInterface
        public void requestGoogleSignIn() {
            startGoogleSignIn();
        }

        @JavascriptInterface
        public void requestBiometricUnlock() {
            startBiometricUnlock();
        }

        @JavascriptInterface
        public void requestSubscription(String planId) {
            if (billingManager != null) billingManager.requestSubscription(planId);
        }

        @JavascriptInterface
        public void restoreSubscriptions() {
            if (billingManager != null) billingManager.restoreSubscriptions();
        }
    }

    private boolean handleNavigation(Uri uri) {
        if (uri != null && "https".equalsIgnoreCase(uri.getScheme())) {
            String host = uri.getHost();
            String path = uri.getPath();
            boolean remoteAcademy = APP_HOST.equalsIgnoreCase(host)
                    && path != null
                    && path.startsWith(APP_PATH);
            boolean localFallback = LOCAL_ASSET_HOST.equalsIgnoreCase(host)
                    && path != null
                    && path.startsWith(LOCAL_ASSET_PATH);
            if (remoteAcademy || localFallback) {
                return false;
            }
        }

        if (uri != null) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    private void showPermanentError() {
        Log.e(TAG, "PERMANENT_ERROR_SCREEN");
        if (root == null) return;

        if (webView != null) {
            try {
                webView.stopLoading();
            } catch (Throwable ignored) {
            }
        }

        root.removeAllViews();
        TextView error = new TextView(this);
        error.setText("STACKUP HOLD'EM ACADEMY\n\nNao foi possivel abrir o aplicativo.\nVerifique sua conexao com a internet e abra novamente.");
        error.setTextColor(Color.WHITE);
        error.setTextSize(17f);
        error.setGravity(Gravity.CENTER);
        error.setPadding(40, 40, 40, 40);
        root.addView(error, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (billingManager != null) billingManager.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    private void handleBackNavigation() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && keyCode == KeyEvent.KEYCODE_BACK
                && event.getRepeatCount() == 0) {
            handleBackNavigation();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (billingManager != null) {
            billingManager.destroy();
            billingManager = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }

        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                if (webView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) webView.getParent()).removeView(webView);
                }
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }
}
