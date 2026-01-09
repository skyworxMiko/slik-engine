package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.util.List;

@Setter
@Getter
@FieldNameConstants
public class ProcessReportBulkRequest {

    private List<Long> idAppDistribute;

    private String statusCode;

    private String batchCode;

    private String reffCode;
}
