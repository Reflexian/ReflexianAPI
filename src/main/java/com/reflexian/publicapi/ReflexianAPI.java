package com.reflexian.publicapi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.reflexian.publicapi.adapters.DateTimeTypeAdapter;
import com.reflexian.publicapi.adapters.UUIDTypeAdapter;
import com.reflexian.publicapi.exceptions.*;
import com.reflexian.publicapi.license.LicenseBuilder;
import com.reflexian.publicapi.reply.AbstractReply;
import com.reflexian.publicapi.reply.license.LicenseReply;
import com.reflexian.publicapi.reply.prison.KeyReply;
import com.reflexian.publicapi.reply.prison.PlayerReply;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class ReflexianAPI {

    private static ReflexianAPI reflexianAPI;


    private static final String BASE_URL = "https://api.reflexian.com/";
    private final String apiKey;
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new UUIDTypeAdapter())
            .registerTypeAdapter(ZonedDateTime.class, new DateTimeTypeAdapter())
            .create();

    private final ExecutorService executorService;
    private final HttpClient httpClient;

    public ReflexianAPI(String apiKey) {
        this.apiKey = apiKey;

        this.executorService = Executors.newCachedThreadPool();
        this.httpClient = HttpClientBuilder.create().build();
    }

    /**
     * @param uuid uuid of a player in string format, can be both dashed or undashed.
     * @return the future
     */
    public CompletableFuture<PlayerReply> getPlayerByUuid(String uuid) {
        return get(PlayerReply.class, "player", uuid);
    }

    /**
     * @param key the key in string format, must be dashed.
     * @return the future
     */
    public CompletableFuture<KeyReply> getKeyByKey(String key) {
        return get(KeyReply.class, "key", key);
    }

    public CompletableFuture<LicenseReply> getLicenseByUuid(String uuid) {
        return get(LicenseReply.class, "licenses", uuid);
    }



    private <R extends String> CompletableFuture<R> createLicense(String json) {
        CompletableFuture<R> future = new CompletableFuture<>();
        try {
            executorService.submit(()->{
                try{
                    String url = BASE_URL+"api/v1/licenses/";
                    HttpPost httpPost = new HttpPost(url);
                    httpPost.addHeader("Content-Type", "application/json");
                    httpPost.addHeader("reflexian-api", apiKey);
                    httpPost.setEntity(new StringEntity(json));
                    R response=httpClient.execute(httpPost, obj -> {

                        String content = EntityUtils.toString(obj.getEntity(), "UTF-8");
                        JsonObject j = GSON.fromJson(content, JsonObject.class);
                        if (!j.get("success").getAsBoolean()) {
                            future.completeExceptionally(new LicenseCreationException(j.get("cause").getAsString()));
                        }
                        return (R) GSON.fromJson(content, JsonObject.class).toString();
                    });
                    future.complete(response);
                }catch (Exception t) {
                    future.completeExceptionally(t);
                    t.printStackTrace();
                }
            });
        }catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    private <R extends AbstractReply> CompletableFuture<R> get(Class<R> clazz, String request, String params) {
        CompletableFuture<R> future = new CompletableFuture<>();
        try {
            if (params==null|| params.equals(""))
                throw new IllegalArgumentException("Need both key and value for parameters");

            StringBuilder url = new StringBuilder(BASE_URL);

            url.append("api/v1/");

            url.append(request);
            url.append("/").append(params);

            executorService.submit(() -> {
                try {
                    HttpGet get = new HttpGet(url.toString());
                    get.setHeader("reflexian-api", apiKey);
                    R response = httpClient.execute(get, obj -> {
                        String content = EntityUtils.toString(obj.getEntity(), "UTF-8");
                        if (clazz == PlayerReply.class) {
                            return (R) new PlayerReply(GSON.fromJson(content, JsonObject.class));
                        } else if (clazz == KeyReply.class) {
                            return (R) new KeyReply(GSON.fromJson(content, JsonObject.class));
                        } else if (clazz == LicenseReply.class) {
                            return (R) new LicenseReply(GSON.fromJson(content, JsonObject.class));
                        } else {
                            return GSON.fromJson(content, clazz);
                        }
                    });

                    checkReply(response);

                    future.complete(response);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    private <T extends AbstractReply> void checkReply(T reply) {
        if (reply != null) {
            if (reply.isThrottled()) {
                throw new APIThrottleException();
            } else if (reply.isBlacklisted()) {
                throw new KeyBlacklistedException();
            } else if (!reply.isSuccess()) {
                throw new ReflexianException(reply.getCause());
            } else if (reply.isLicenseKeyError()) {
                throw new NotLicenseKeyException();
            }
        }
    }

}
