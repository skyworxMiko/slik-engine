package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AppRequestWrapper {

    private AppRequestPayload batch;

    private  AppRequestPayload manual;

    private  AppRequestPayload combine;

    private  AppRequestPayload manualDownload;

    private  AppRequestPayload batchDownload;
}
