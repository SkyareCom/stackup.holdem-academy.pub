package com.skyare.stackupacademy;

import androidx.fragment.app.FragmentActivity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;
import java.security.KeyStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    private WebView webView;
    private static final String LOCAL_APP_URL = "file:///android_asset/index.html";
    private static final String SUPABASE_URL = "https://mzlznwnxahixoqyspsdy.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_E9cnM9HPU19f9hdFxzjXrg_FkD6clWQ";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.addJavascriptInterface(new AndroidAuth(), "AndroidAuth");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri=request.getUrl();
                if (isTrustedAppUri(uri)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch(Exception ignored) {}
                return true;
            }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame() && request.getUrl() != null && "https".equals(request.getUrl().getScheme())) loadLocalFallback();
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view,url);
                if (isTrustedAppUri(Uri.parse(url))) restoreSession();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri=Uri.parse(url);
                if (isTrustedAppUri(uri)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch(Exception ignored) {}
                return true;
            }
        });
        webView.loadUrl(LOCAL_APP_URL);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> { if (webView.canGoBack()) webView.goBack(); else finish(); }
            );
        }
    }

    private boolean isTrustedAppUri(Uri uri) {
        if (uri == null) return false;
        if ("file".equals(uri.getScheme()) && "/android_asset/index.html".equals(uri.getPath())) return true;
        return false;
    }

    private void loadLocalFallback() {
        runOnUiThread(() -> { if (!LOCAL_APP_URL.equals(webView.getUrl())) webView.loadUrl(LOCAL_APP_URL); });
    }

    private void js(String function, String value) {
        if (!"authSuccess".equals(function) && !"authError".equals(function) && !"authRecovery".equals(function)) return;
        String safe = org.json.JSONObject.quote(value == null ? "" : value);
        runOnUiThread(() -> webView.evaluateJavascript("window."+function+"("+safe+")", null));
    }

    public class AndroidAuth {
        @JavascriptInterface public void googleLogin() {
            try {
                String redirect = "stackupexperiencia://auth/callback";
                String authUrl = SUPABASE_URL + "/auth/v1/authorize?provider=google&redirect_to=" +
                    java.net.URLEncoder.encode(redirect, "UTF-8");
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
            } catch(Exception e) { js("authError","Não foi possível iniciar o login Google."); }
        }

        @JavascriptInterface public void stackupLogin(String email, String password) {
            new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL(SUPABASE_URL + "/auth/v1/token?grant_type=password");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(15000);
                    c.setRequestMethod("POST"); c.setDoOutput(true);
                    c.setRequestProperty("apikey", SUPABASE_KEY);
                    c.setRequestProperty("Content-Type", "application/json");
                    org.json.JSONObject requestBody = new org.json.JSONObject();
                    requestBody.put("email", email == null ? "" : email.trim());
                    requestBody.put("password", password == null ? "" : password);
                    String body = requestBody.toString();
                    try(java.io.OutputStream os=c.getOutputStream()){os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                    int status=c.getResponseCode();
                    if(status>=200 && status<300) {
                        java.io.InputStream in=c.getInputStream();
                        String json=readUtf8(in);
                        in.close();
                        org.json.JSONObject o=new org.json.JSONObject(json);
                        String refresh=o.optString("refresh_token","");
                        if(refresh.isEmpty()) js("authError","Sessão inválida. Tente novamente.");
                        else { saveRefreshToken(refresh); js("authSuccess",""); }
                    } else js("authError","StackUp ID ou senha inválidos.");
                    c.disconnect();
                } catch(Exception e){ js("authError","Não foi possível conectar ao STACKUP ID."); }
            }).start();
        }

        @JavascriptInterface public void logout() {
            clearRefreshToken();
            js("authSuccess","logout");
        }

        @JavascriptInterface public void biometricLogin() {
            runOnUiThread(() -> {
                BiometricManager manager = BiometricManager.from(MainActivity.this);
                int available = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                if (available != BiometricManager.BIOMETRIC_SUCCESS) {
                    js("authError","Biometria não disponível neste aparelho."); return;
                }
                Executor executor = ContextCompat.getMainExecutor(MainActivity.this);
                BiometricPrompt prompt = new BiometricPrompt(MainActivity.this, executor, new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        String refresh=loadRefreshToken();
                        if(refresh.isEmpty()){ js("authError","Entre primeiro com Google ou STACKUP ID para ativar a biometria."); return; }
                        refreshSession(refresh);
                    }
                    @Override public void onAuthenticationError(int code, CharSequence msg) { super.onAuthenticationError(code,msg); js("authError",msg.toString()); }
                });
                BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("STACKUP HOLD'EM")
                    .setSubtitle("Confirme sua identidade")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();
                prompt.authenticate(info);
            });
        }
    }

    private static final String KEY_ALIAS="stackup_session_key";

    private SecretKey sessionKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(!ks.containsAlias(KEY_ALIAS)){
            KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY_ALIAS,null)).getSecretKey();
    }

    private void saveRefreshToken(String token) {
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,sessionKey());
            byte[] encrypted=cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            getSharedPreferences("stackup_auth",MODE_PRIVATE).edit()
                .putString("refresh_token_enc",Base64.encodeToString(encrypted,Base64.NO_WRAP))
                .putString("refresh_token_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .remove("refresh_token").apply();
        } catch(Exception e){ clearRefreshToken(); }
    }

    private String loadRefreshToken() {
        try {
            android.content.SharedPreferences p=getSharedPreferences("stackup_auth",MODE_PRIVATE);
            String enc=p.getString("refresh_token_enc",""), iv=p.getString("refresh_token_iv","");
            if(enc.isEmpty()||iv.isEmpty()) return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,sessionKey(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(enc,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception e){ clearRefreshToken(); return ""; }
    }

    private void clearRefreshToken() {
        getSharedPreferences("stackup_auth",MODE_PRIVATE).edit().clear().apply();
    }

    private static String readUtf8(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        byte[] buffer=new byte[4096]; int n;
        while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
        return out.toString("UTF-8");
    }

    private void restoreSession() {
        String refresh=loadRefreshToken();
        if(!refresh.isEmpty()) refreshSession(refresh);
    }

    private void refreshSession(String refresh) {
        new Thread(() -> {
            try {
                java.net.URL url=new java.net.URL(SUPABASE_URL+"/auth/v1/token?grant_type=refresh_token");
                java.net.HttpURLConnection c=(java.net.HttpURLConnection)url.openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(15000);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("apikey",SUPABASE_KEY); c.setRequestProperty("Content-Type","application/json");
                org.json.JSONObject body=new org.json.JSONObject(); body.put("refresh_token",refresh);
                try(java.io.OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                int status=c.getResponseCode();
                if(status>=200 && status<300){
                    String json;
                    try(java.io.InputStream in=c.getInputStream()){ json=readUtf8(in); }
                    org.json.JSONObject o=new org.json.JSONObject(json);
                    String next=o.optString("refresh_token",refresh);
                    saveRefreshToken(next);
                    js("authSuccess","");
                } else { clearRefreshToken(); js("authError","Sessão expirada. Entre novamente com Google ou STACKUP ID."); }
                c.disconnect();
            } catch(Exception e){ js("authError","Não foi possível validar sua sessão."); }
        }).start();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthCallback(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        handleAuthCallback(getIntent());
    }

    private void handleAuthCallback(Intent intent) {
        if(intent==null || intent.getData()==null) return;
        Uri u=intent.getData();
        if(!"stackupexperiencia".equals(u.getScheme()) || !"auth".equals(u.getHost())) return;
        String fragment=u.getFragment();
        if(fragment==null) {
            intent.setData(null);
            js("authError","Retorno do Google sem sessão válida."); return;
        }
        try {
            java.util.Map<String,String> values=new java.util.HashMap<>();
            for(String part:fragment.split("&")){
                String[] kv=part.split("=",2);
                if(kv.length==2) values.put(java.net.URLDecoder.decode(kv[0],"UTF-8"),java.net.URLDecoder.decode(kv[1],"UTF-8"));
            }
            String refresh=values.get("refresh_token");
            String access=values.get("access_token");
            String type=values.get("type");
            if("recovery".equals(type) && access!=null && !access.isEmpty()){
                intent.setData(null);
                js("authRecovery",access);
            } else if(refresh!=null && !refresh.isEmpty()){
                saveRefreshToken(refresh);
                intent.setData(null);
                js("authSuccess","");
            } else { intent.setData(null); js("authError","Não foi possível concluir a sessão Google."); }
        } catch(Exception e){ intent.setData(null); js("authError","Não foi possível validar o retorno Google."); }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}