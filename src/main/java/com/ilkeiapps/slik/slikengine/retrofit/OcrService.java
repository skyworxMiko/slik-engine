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

import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

@Service
public class OcrService {

    @Value("${cbas.engine.core.endpoint}")
    private String m2mUrl;

    @Value("${cbas.engine.core.captcha.endpoint}")
    private String captchaUrl;

    @Bean
    public IOCR getOcrService() {
        return providedRetrofitBuilder().build().create(IOCR.class);
    }

    private Retrofit.Builder providedRetrofitBuilder() {
        return new Retrofit.Builder()
                .baseUrl(captchaUrl)
                .client(provideOkHttpClient(provideHttpLoggingInterceptor()))
                .addConverterFactory(GsonConverterFactory.create(provideGson()));
    }

    private static OkHttpClient provideOkHttpClient(HttpLoggingInterceptor httpLoggingInterceptor) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.readTimeout(10, TimeUnit.MINUTES);
        builder.connectTimeout(10, TimeUnit.MINUTES);
        builder.followRedirects(true);
        builder.addInterceptor(httpLoggingInterceptor);

        return builder.build();
    }

    private static HttpLoggingInterceptor provideHttpLoggingInterceptor() {
        HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor();
        httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        return httpLoggingInterceptor;
    }


    private static Gson provideGson() {
        return new GsonBuilder().setPrettyPrinting()
                .create();
    }
}
