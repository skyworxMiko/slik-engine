package com.ilkeiapps.slik.slikengine.bean.scoring;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.util.List;

@Setter
@Getter
@FieldNameConstants
public class GetScoring {
    private Integer minSkor;
    private List<ScoringItem> item;
}
