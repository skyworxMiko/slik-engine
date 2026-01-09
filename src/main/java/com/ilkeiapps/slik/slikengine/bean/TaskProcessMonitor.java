package com.ilkeiapps.slik.slikengine.bean;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class TaskProcessMonitor {

    private String statusTask;

    private String currentProcess;

    private String pendingProcess;

    private LocalDateTime lastUpdate;

    private String login;

    public TaskProcessMonitor(String statusTask, String currentProcess, String pendingProcess) {
        this.statusTask = statusTask;
        this.currentProcess = currentProcess;
        this.pendingProcess = pendingProcess;
    }

    public void setStatusTask(String status) {
        this.statusTask = status;
        this.lastUpdate = LocalDateTime.now();
    }
}
