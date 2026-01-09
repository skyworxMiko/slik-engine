package com.ilkeiapps.slik.slikengine.retrofit;

import com.ilkeiapps.slik.slikengine.bean.ApiResponse;
import com.ilkeiapps.slik.slikengine.bean.scoring.GetScoring;
import retrofit2.Call;
import retrofit2.http.GET;

public interface ISCORING {
    @GET("api/m2m/scoring")
    Call<ApiResponse<GetScoring>> getScoring();
}
