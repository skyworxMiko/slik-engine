package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class RobotHealth {

    private String code;

    private String status;

    private String upTime;

    private String jvmCpuUsage;

    private String jmvMemoryUsage;

    private String hikariConnection;

    private String hikariConnectionActive;

    private String diskFree;

    private String statusProcessing;

    private String currentProcess;

    private String pendingProcess;

    private LocalDateTime lastUpdate;
}
