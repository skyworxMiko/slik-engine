package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.util.List;

@Setter
@Getter
@FieldNameConstants
public class AppRequestPayload {

    private String mode;

    private Integer count;

    private String robot;

    private String batchCode;

    private List<AppRequest> data;
}
