package com.ilkeiapps.slik.slikengine.config;

import com.ilkeiapps.slik.slikengine.bean.scoring.ScoringItem;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Setter
@Getter
public class ScoringConfig {
    private int globalMinScore;
    private Map<String, ScoringItem> itemsByCode;

    public ScoringItem getItem(String code) {
        if (itemsByCode == null || code == null) return null;
        return itemsByCode.get(code.toUpperCase());
    }
}
