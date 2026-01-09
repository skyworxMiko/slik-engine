package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class ActivityServicesBean {

    private String robotName;
    private String code;
    private String name;
    private String command;
    private String type;
    private String element;
    private LocalDateTime start;
    private LocalDateTime end;
    private Long appId;
    private byte[] prevStart;
    private byte[] prevEnd;


}
