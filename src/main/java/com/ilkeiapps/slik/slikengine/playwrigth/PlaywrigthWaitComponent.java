package com.ilkeiapps.slik.slikengine.playwrigth;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

@Setter
@Getter
@FieldNameConstants
public class PlaywrigthWaitComponent {

    private String waitType;

    private Integer duration;

    private String target;

    private String byType;

    private String name;

    private Long appId;
}
