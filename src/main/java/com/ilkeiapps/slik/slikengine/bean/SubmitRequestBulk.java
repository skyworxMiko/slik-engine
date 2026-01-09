package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import java.util.List;

@Setter
@Getter
@FieldNameConstants
@ToString(includeFieldNames = true)
public class SubmitRequestBulk {

    private List<Long> idAppRequest;

    private String approvalVariable;

    private String reason;
}
