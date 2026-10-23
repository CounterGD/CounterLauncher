package me.countergd.launcher.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.countergd.launcher.model.ElyByAccount;
import java.awt.Desktop;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Ely.by OAuth 2.0 authorization-code flow with a loopback callback and state verification. */
public final class ElyByOAuthService {
    private static final String AUTHORIZE="https://account.ely.by/oauth2/v1";
    private static final String TOKEN="https://account.ely.by/api/oauth2/v1/token";
    private static final String INFO="https://account.ely.by/api/account/v1/info";
    private final HttpService http;
    public ElyByOAuthService(HttpService http) { this.http=http; }

    public ElyByAccount login(String clientId,String clientSecret,String redirectUri) throws Exception {
        if(clientId==null||clientId.isBlank()||clientSecret==null||clientSecret.isBlank())
            throw new IOException("Enter your Ely.by OAuth client ID and client secret.");
        URI redirect=URI.create(redirectUri);
        if(!"http".equalsIgnoreCase(redirect.getScheme()) || !("127.0.0.1".equals(redirect.getHost())||"localhost".equalsIgnoreCase(redirect.getHost())))
            throw new IOException("For this desktop flow, the callback must use http://127.0.0.1 or http://localhost.");
        int port=redirect.getPort()<0?80:redirect.getPort();
        String path=redirect.getRawPath()==null||redirect.getRawPath().isBlank()?"/":redirect.getRawPath();
        String state=UUID.randomUUID().toString()+UUID.randomUUID();
        CompletableFuture<Map<String,String>> callback=new CompletableFuture<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),0);
        server.createContext(path, exchange -> handleCallback(exchange,state,callback));
        server.setExecutor(Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"elyby-oauth-callback");t.setDaemon(true);return t;}));
        server.start();
        try {
            String url=AUTHORIZE+"?client_id="+enc(clientId)+"&redirect_uri="+enc(redirectUri)+
                    "&response_type=code&scope="+enc("account_info minecraft_server_session offline_access")+"&state="+enc(state);
            openBrowser(url);
            Map<String,String> params;
            try { params=callback.get(4,TimeUnit.MINUTES); }
            catch(TimeoutException e) { throw new IOException("OAuth timed out. Complete sign-in in the browser and try again.",e); }
            if(params.containsKey("error")) throw new IOException("Ely.by authorization failed: "+params.getOrDefault("error_description",params.get("error")));
            if(!state.equals(params.get("state"))) throw new IOException("OAuth state did not match; sign-in was rejected.");
            String code=params.get("code"); if(code==null||code.isBlank()) throw new IOException("Ely.by did not return an authorization code.");
            Map<String,String> tokenParams=new LinkedHashMap<>();
            tokenParams.put("client_id",clientId); tokenParams.put("client_secret",clientSecret);
            tokenParams.put("redirect_uri",redirectUri); tokenParams.put("grant_type","authorization_code"); tokenParams.put("code",code);
            JsonObject tokens=JsonParser.parseString(http.postForm(TOKEN,form(tokenParams))).getAsJsonObject();
            String access=tokens.get("access_token").getAsString();
            String refresh=tokens.has("refresh_token")?tokens.get("refresh_token").getAsString():"";
            long expiry=Instant.now().getEpochSecond()+(tokens.has("expires_in")?tokens.get("expires_in").getAsLong():86400);
            JsonObject profile=JsonParser.parseString(http.getAuthorizedText(INFO,access)).getAsJsonObject();
            if(!profile.has("username")||!profile.has("uuid")) throw new IOException("Ely.by profile response did not include username and UUID.");
            return new ElyByAccount(profile.get("username").getAsString(),profile.get("uuid").getAsString(),access,refresh,expiry,clientId);
        } finally { server.stop(0); }
    }

    public ElyByAccount refresh(ElyByAccount account,String clientSecret) throws Exception {
        if(account.refreshToken()==null||account.refreshToken().isBlank()) throw new IOException("No refresh token was saved; sign in again.");
        Map<String,String> params=new LinkedHashMap<>(); params.put("client_id",account.clientId()); params.put("client_secret",clientSecret);
        params.put("scope","account_info minecraft_server_session offline_access"); params.put("refresh_token",account.refreshToken()); params.put("grant_type","refresh_token");
        JsonObject token=JsonParser.parseString(http.postForm(TOKEN,form(params))).getAsJsonObject();
        String access=token.get("access_token").getAsString();
        String refresh=token.has("refresh_token")?token.get("refresh_token").getAsString():account.refreshToken();
        long expiry=Instant.now().getEpochSecond()+(token.has("expires_in")?token.get("expires_in").getAsLong():86400);
        JsonObject profile=JsonParser.parseString(http.getAuthorizedText(INFO,access)).getAsJsonObject();
        return new ElyByAccount(profile.get("username").getAsString(),profile.get("uuid").getAsString(),access,refresh,expiry,account.clientId());
    }

    private static void handleCallback(HttpExchange ex,String expectedState,CompletableFuture<Map<String,String>> future) throws IOException {
        String response="<html><meta charset='utf-8'><title>CounterLauncher</title><body style='font-family:sans-serif'><h2>CounterLauncher</h2><p>Sign-in response received. You can return to the launcher.</p></body></html>";
        try {
            Map<String,String> query=parseQuery(ex.getRequestURI().getRawQuery());
            if(query.containsKey("state") && !expectedState.equals(query.get("state"))) future.completeExceptionally(new IOException("OAuth state mismatch."));
            else future.complete(query);
        } catch(Exception e) { future.completeExceptionally(e); response="Invalid OAuth callback. Return to CounterLauncher."; }
        byte[] bytes=response.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
        ex.sendResponseHeaders(200,bytes.length); try(var out=ex.getResponseBody()){out.write(bytes);}
    }
    private static void openBrowser(String url) throws IOException {
        try { if(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { Desktop.getDesktop().browse(URI.create(url)); return; } }
        catch(Exception ignored) {}
        try { new ProcessBuilder("xdg-open",url).start(); }
        catch(IOException e) { throw new IOException("Could not open a browser. Install/configure xdg-open or a desktop browser, then retry.",e); }
    }
    private static String form(Map<String,String> values) { StringJoiner j=new StringJoiner("&"); values.forEach((k,v)->j.add(enc(k)+"="+enc(v))); return j.toString(); }
    private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    private static Map<String,String> parseQuery(String query) {
        Map<String,String> out=new HashMap<>(); if(query==null)return out;
        for(String pair:query.split("&")){int i=pair.indexOf('='); String k=i<0?pair:pair.substring(0,i); String v=i<0?"":pair.substring(i+1); out.put(URLDecoder.decode(k,StandardCharsets.UTF_8),URLDecoder.decode(v,StandardCharsets.UTF_8));}
        return out;
    }
}
