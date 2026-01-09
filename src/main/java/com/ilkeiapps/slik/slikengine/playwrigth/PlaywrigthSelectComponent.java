package com.ilkeiapps.slik.slikengine.playwrigth;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

@Setter
@Getter
@FieldNameConstants
public class PlaywrigthSelectComponent {

    private String byType;

    private String element;

    private String name;

    private String sendValue;

    private Long appId;
}
