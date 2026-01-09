package com.ilkeiapps.slik.slikengine.retrofit;

import com.ilkeiapps.slik.slikengine.bean.OcrResult;
import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface IOCR {

    @Multipart
    @POST("ocr/tf")
    Call<OcrResult> fetchCaptcha(@Part MultipartBody.Part partFile);

    @Multipart
    @POST("ocr/tf/w")
    Call<OcrResult> fetchCaptchaWhite(@Part MultipartBody.Part partFile);
}
