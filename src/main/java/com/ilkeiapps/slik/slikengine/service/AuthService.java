package com.ilkeiapps.slik.slikengine.service;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.slik.slikengine.bean.AuthAckPayload;
import com.ilkeiapps.slik.slikengine.bean.RobotTokenView;
import com.ilkeiapps.slik.slikengine.entity.EngineConfig;
import com.ilkeiapps.slik.slikengine.entity.EngineStatus;
import com.ilkeiapps.slik.slikengine.entity.QEngineConfig;
import com.ilkeiapps.slik.slikengine.retrofit.IM2M;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final EntityManager em;

    private final CriteriaBuilderFactory configBuilder;

    private final IM2M m2mService;

    @Value("${cbas.engine.name}")
    private String robotName;

    public void initLoad() {
        persistInitialEngineStatus();

        RobotTokenView rbv = fetchRobotTokenView();
        if (rbv == null) {
            return;
        }

        var qsg = new QEngineConfig("o");

        upsertConfig(qsg, "TKN",   "Token",               rbv.getToken());
        upsertConfig(qsg, "SLKU",  "SLIK User Name",      rbv.getSlikUser());
        upsertConfig(qsg, "SLKP",  "SLIK Password",       rbv.getSlikPassword());
        upsertConfig(qsg, "SSLKU", "SPV SLIK User Name",  rbv.getSlikSpvUser());
        upsertConfig(qsg, "SSLKP", "SPV SLIK Password",   rbv.getSlikSpvPassword());
        upsertConfig(qsg, "IDEB",  "IDEB Password",       rbv.getIdebPassword());
        upsertConfig(qsg, "RROLE", "Robot Role",          rbv.getRobotRole());
        upsertConfig(qsg, "RFIND", "Metode Pencarian",    rbv.getRobotFind());
    }

    private void persistInitialEngineStatus() {
        var es = new EngineStatus();
        es.setStatusEngine("INITIAL");
        es.setLastUpdateEngine(LocalDateTime.now());
        this.em.persist(es);
    }

    private RobotTokenView fetchRobotTokenView() {
        try {
            var pay = new AuthAckPayload();
            pay.setRobotName(robotName);

            var call = m2mService.authAck(pay);
            var res = call.execute();

            if (!res.isSuccessful() || res.body() == null) {
                return null;
            }

            if (CollectionUtils.isEmpty(res.body().getData())) {
                return null;
            }

            return res.body().getData().get(0);
        } catch (IOException e) {
            return null;
        }
    }

    private void upsertConfig(QEngineConfig qsg, String code, String name, String value) {
        var csg = configBuilder.create(em, EngineConfig.class)
                .from(EngineConfig.class, qsg.getMetadata().getName())
                .where(qsg.code.toString()).eq(code)
                .getResultList();

        if (CollectionUtils.isEmpty(csg)) {
            var sg = new EngineConfig();
            sg.setName(name);
            sg.setCode(code);
            sg.setValue(value);
            this.em.persist(sg);
        } else {
            var sg = csg.get(0);
            sg.setValue(value);
            this.em.merge(sg);
        }
    }

    public Boolean isValidToken(String token) {
        var qsg = new QEngineConfig("o");
        var csg = configBuilder.create(em, EngineConfig.class).from(EngineConfig.class, qsg.getMetadata().getName())
                .where(qsg.code.toString()).eq("TKN").getResultList();
        if (CollectionUtils.isEmpty(csg)) {
            return false;
        }

        var auth = csg.get(0).getValue();
        return auth.equals(token);
    }
}
