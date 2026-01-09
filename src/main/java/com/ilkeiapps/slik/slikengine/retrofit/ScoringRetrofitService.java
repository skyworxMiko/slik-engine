package com.ilkeiapps.slik.slikengine.retrofit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.lang.reflect.Proxy;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class ScoringRetrofitService {
    @Value("${cbas.engine.core.endpoint}")
    private String scoringBaseUrl;

    @Bean
    public ISCORING scoringApi() {
        return scoringRetrofitBuilder().build().create(ISCORING.class);
    }

    private Retrofit.Builder scoringRetrofitBuilder() {
        String base = scoringBaseUrl.endsWith("/") ? scoringBaseUrl : scoringBaseUrl + "/";

        return new Retrofit.Builder()
                .baseUrl(base)
                .client(provideOkHttpClient(provideHttpLoggingInterceptor()))
                .addConverterFactory(GsonConverterFactory.create(provideGson()));
    }

    private OkHttpClient provideOkHttpClient(HttpLoggingInterceptor httpLoggingInterceptor) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        try {
            final SSLContext sslContext = SSLContext.getInstance(SSLManager.SSL);
            sslContext.init(null, new TrustManager[]{SSLManager.trustManager}, new java.security.SecureRandom());
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            builder.sslSocketFactory(sslSocketFactory, SSLManager.trustManager);
            builder.hostnameVerifier((hostname, sslSession) -> SSLManager.isVerifiedUrl(hostname));
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            // boleh di-log kalau mau
        }

        builder.readTimeout(10, TimeUnit.MINUTES);
        builder.connectTimeout(10, TimeUnit.MINUTES);
        builder.followRedirects(true);
        builder.addInterceptor(httpLoggingInterceptor);

        return builder.build();
    }

    private HttpLoggingInterceptor provideHttpLoggingInterceptor() {
        HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor();
        httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.NONE);
        return httpLoggingInterceptor;
    }

    private Gson provideGson() {
        return new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new LocalDateTypeAdapter())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter())
                .setPrettyPrinting()
                .create();
    }
}
