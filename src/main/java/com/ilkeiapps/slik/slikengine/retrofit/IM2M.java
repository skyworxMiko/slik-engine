package com.ilkeiapps.slik.slikengine.retrofit;

import com.ilkeiapps.slik.slikengine.bean.*;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface IM2M {

    @POST("api/m2m/auth")
    Call<ApiResponse<RobotTokenView>> authAck(@Body AuthAckPayload src);

    @POST("api/m2m/robot/health/heartbeat")
    Call<Void> heartbeat(@Body RobotHealth src);

    @POST("api/m2m/robot/process/start")
    Call<ApiResponse<ProcessReportRequest>> processStart(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/start/bulk")
    Call<Void> processStartBulk(@Body ProcessReportBulkRequest src);

    @POST("api/m2m/robot/process/done")
    Call<ApiResponse<ProcessReportRequest>> processDone(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/done/bulk")
    Call<Void> processDoneBulk(@Body ProcessReportBulkRequest src);

    @POST("api/m2m/robot/process/fail")
    Call<ApiResponse<ProcessReportRequest>> processFail(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/fail/bulk")
    Call<Void> processFailBulk(@Body ProcessReportBulkRequest src);

    @POST("api/m2m/robot/process/approval/start")
    Call<ApiResponse<Long>> approvalStart(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/approval/done")
    Call<ApiResponse<Long>> approvalDone(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/approval/fail")
    Call<Void> approvalFail(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/download/start")
    Call<ApiResponse<Long>> downloadStart(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/download/done")
    Call<ApiResponse<Long>> downloadDone(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/download/fail")
    Call<Void> extractFail(@Body ProcessReportRequest src);

    @POST("api/m2m/robot/process/callback")
    Call<ApiResponse<SubmitRequest>> processCallback(@Body SubmitRequest src);


    @POST("api/m2m/robot/process/callback/bulk")
    Call<ApiResponse<SubmitRequestBulk>> processCallbackBulk(@Body SubmitRequestBulk src);

    @Multipart
    @POST("api/m2m/robot/ideb/upload")
    Call<Void> uploadIdeb(@Part("robotId") RequestBody code, @Part("reffId") RequestBody reffId, @Part MultipartBody.Part file);

    @POST("api/m2m/robot/notfound/code")
    Call<ApiResponse<ProcessReportRequest>> notFoundByCode(@Body ProcessReportRequest src);
}
