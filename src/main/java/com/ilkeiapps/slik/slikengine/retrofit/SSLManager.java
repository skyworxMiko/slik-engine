package com.ilkeiapps.slik.slikengine.retrofit;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

public class SSLManager {

    public static final String SSL = "SSL";

    public static final String TLS = "TLS";

    public static final X509TrustManager trustManager = new X509TrustManager() {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private SSLManager() {
    }

    public static boolean isVerifiedUrl(String hostname) {
        return true;
    }

    public static List<String> getTrustedUrls() {
        List<String> trustedUrls = new ArrayList<>();

        return trustedUrls;
    }
}
