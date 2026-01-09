package com.ilkeiapps.slik.slikengine.retrofit;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface IM2MImage {

    @Multipart
    @POST("api/m2m/robot/health/preview")
    Call<Void> preview(@Part MultipartBody.Part partFile,  @Part("code") RequestBody code);

    @Multipart
    @POST("api/m2m/robot/process/log")
    Call<Void> inserLog(@Part("code") RequestBody code,  @Part("name") RequestBody name, @Part("command") RequestBody command,
                        @Part("type") RequestBody type, @Part("element") RequestBody element, @Part("start") RequestBody start,
                        @Part("end") RequestBody end, @Part("appId") RequestBody appId,  @Part MultipartBody.Part docStart, @Part MultipartBody.Part docEnd);

    @Multipart
    @POST("api/m2m/robot/process/incident")
    Call<Void> insertIncident(@Part("code") RequestBody code,  @Part("title") RequestBody title, @Part("description") RequestBody description,
                        @Part("start") RequestBody start, @Part("appId") RequestBody appId,  @Part MultipartBody.Part dosc);

    @Multipart
    @POST("api/m2m/robot/ideb/upload")
    Call<Void> uploadIdeb(@Part("robotId") RequestBody code, @Part("reffId") RequestBody reffId, @Part MultipartBody.Part file);


}
