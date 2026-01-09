package com.ilkeiapps.slik.slikengine.bean.scoring;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

@Setter
@Getter
@FieldNameConstants
public class ScoringItem {
    private Long id;
    private Integer aktif;
    private String code;
    private Double minSkor;
    private String nama;
    private Integer value;

    public boolean isActive() {
        return aktif != null && aktif == 1;
    }
}
