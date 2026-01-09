package com.ilkeiapps.slik.slikengine.config;

import com.ilkeiapps.slik.slikengine.service.RobotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulerConfiguration {

    private final RobotService robotService;

    @Scheduled(fixedDelayString = "${cbas.scheduler.rate.heartbeat}")
    public void runHeartBeat() {
        try {
            log.info("run >>> starting heartbeat scheduler");
            robotService.heartBeat();
        } catch (Exception e) {
            log.error("runHeartBeat >>> error", e);
        }
    }

    // Ini sepertinya dipanggil manual dari REST atau proses lain (bukan scheduler)
    public void runPreview() {
        try {
            log.info("run >>> starting preview");
            robotService.previewNew();
        } catch (Exception e) {
            log.error("runPreview >>> error", e);
        }
    }

    @Scheduled(fixedDelayString = "${cbas.scheduler.rate.logincheck}")
    public void checkLogin() {
        try {
            log.info("run >>> starting checklogin scheduler");
            robotService.checkLogin();
        } catch (Exception e) {
            log.error("checkLogin (scheduler) >>> error", e);
        }
    }

    @Scheduled(fixedDelayString = "${cbas.scheduler.rate.idle}")
    public void runIdle() {
        try {
            log.info("run >>> starting idle");
            robotService.checkIdle();
        } catch (Exception e) {
            log.error("runIdle >>> error", e);
        }
    }

    @Scheduled(fixedDelayString = "${cbas.scheduler.rate.crash}")
    public void runCheckCrash() {
        try {
            log.info("run >>> starting checkcrash");
            robotService.checkCrash();
        } catch (Exception e) {
            log.error("runCheckCrash >>> error", e);
        }
    }
}
