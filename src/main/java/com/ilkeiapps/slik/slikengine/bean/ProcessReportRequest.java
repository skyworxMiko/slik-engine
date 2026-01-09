package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

@Setter
@Getter
@FieldNameConstants
@ToString(includeFieldNames = true)
public class ProcessReportRequest {

    private Long idAppDistribute;

    private String statusCode;
}
