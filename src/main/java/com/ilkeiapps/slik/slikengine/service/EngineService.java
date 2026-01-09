package com.ilkeiapps.slik.slikengine.service;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.slik.slikengine.bean.ApiResponse;
import com.ilkeiapps.slik.slikengine.entity.EngineConfig;
import com.ilkeiapps.slik.slikengine.entity.EngineStatus;
import com.ilkeiapps.slik.slikengine.entity.QEngineConfig;
import com.ilkeiapps.slik.slikengine.entity.QEngineStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EngineService {

    private final EntityManager em;

    private final CriteriaBuilderFactory configBuilder;

    public String getCurrentStatusEngine() {
        var qsg = new QEngineStatus("o");
        var csg = configBuilder.create(em, EngineStatus.class).from(EngineStatus.class, qsg.getMetadata().getName())
                .getResultList();
        if (!CollectionUtils.isEmpty(csg)) {
            if (csg.get(0).getStatusEngine() == null) {
                return "NONE";
            }

            return csg.get(0).getStatusEngine();
        }

        return "NONE";
    }

    public void setCurrentStatusEngine(String status) {
        log.info("Current Status Engine : {}", status);
        var qsg = new QEngineStatus("o");
        var csg = configBuilder.create(em, EngineStatus.class).from(EngineStatus.class, qsg.getMetadata().getName())
                .getSingleResult();
        if (csg != null) {
            csg.setStatusEngine(status);
            this.em.merge(csg);
            this.em.flush();
        }
    }

    public String getLastPlaywrigthStatus() {
        var qsg = new QEngineStatus("o");
        var csg = configBuilder.create(em, EngineStatus.class).from(EngineStatus.class, qsg.getMetadata().getName())
                .getResultList();
        if (!CollectionUtils.isEmpty(csg)) {
            if (csg.get(0).getLastPlaywrigthStatus() == null) {
                return "NONE";
            }

            return csg.get(0).getLastPlaywrigthStatus();
        }

        return "NONE";
    }

    public void setLastPlaywrigthStatus(String status) {
        var qsg = new QEngineStatus("o");
        var csg = configBuilder.create(em, EngineStatus.class).from(EngineStatus.class, qsg.getMetadata().getName())
                .getSingleResult();
        if (csg != null) {
            csg.setLastPlaywrigthStatus(status);
            this.em.merge(csg);
            this.em.flush();
        }
    }

    public LocalDateTime getLastUpdate() {
        var qsg = new QEngineStatus("o");
        try {
            EngineStatus csg = configBuilder.create(em, EngineStatus.class)
                    .from(EngineStatus.class, qsg.getMetadata().getName())
                    .getSingleResult();

            return csg.getLastUpdateEngine();
        } catch (NoResultException e) {
            log.warn("getLastUpdate >>> tidak ada record EngineStatus, return null");
            return null;
        }
    }

    public void setLastUpdate(LocalDateTime lastUpdate) {
        var qsg = new QEngineStatus("o");
        var csg = configBuilder.create(em, EngineStatus.class).from(EngineStatus.class, qsg.getMetadata().getName())
                .getSingleResult();
        if (csg != null) {
            csg.setLastUpdateEngine(lastUpdate);
            this.em.merge(csg);
            this.em.flush();
        }
    }

    public ApiResponse<EngineStatus> getEngineService() {
        var qsg = new QEngineStatus("o");
        var csg = configBuilder.create(em, EngineStatus.class).from(EngineStatus.class, qsg.getMetadata().getName())
                .getResultList();

        var wrap = new ApiResponse<EngineStatus>();
        wrap.setStatus(true);
        wrap.setData(csg);
        return wrap;
    }

    public void setCurrentProcess(String status) {
        var qsg = new QEngineStatus("o");
        var csg = configBuilder.create(em, EngineStatus.class).from(EngineStatus.class, qsg.getMetadata().getName())
                .getResultList();
        if (!CollectionUtils.isEmpty(csg)) {
            var obj = csg.get(0);
            obj.setCurrentProcess(status);
            this.em.merge(obj);
            this.em.flush();
        }
    }

    public String getCurrentProcess() {
        var qsg = new QEngineStatus("o");
        var csg = configBuilder.create(em, EngineStatus.class).from(EngineStatus.class, qsg.getMetadata().getName())
                .getResultList();
        if (!CollectionUtils.isEmpty(csg)) {
            if (csg.get(0).getCurrentProcess() == null) {
                return "NONE";
            }

            return csg.get(0).getCurrentProcess();
        }

        return "NONE";
    }

    public String getRole() {
        var qsg = new QEngineConfig("o");
        var csg = configBuilder.create(em, EngineConfig.class).from(EngineConfig.class, qsg.getMetadata().getName())
                .where(qsg.code.toString()).eq("RROLE")
                .getSingleResult();
        if (csg != null) {
            return csg.getValue();
        }

        return "NONE";
    }
}
