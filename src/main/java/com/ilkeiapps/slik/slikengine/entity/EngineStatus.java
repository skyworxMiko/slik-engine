package com.ilkeiapps.slik.slikengine.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "engine_status")
public class EngineStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "status_engine")
    private String statusEngine;

    @Column(name = "last_update_engine")
    private LocalDateTime lastUpdateEngine;

    @Column(name = "current_process")
    private String currentProcess;

    @Column(name = "last_playwrigth_status")
    private String lastPlaywrigthStatus;
}
