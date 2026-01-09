package com.ilkeiapps.slik.slikengine.service;

import com.ilkeiapps.slik.slikengine.bean.ApiResponse;
import com.ilkeiapps.slik.slikengine.bean.PingResponse;
import com.ilkeiapps.slik.slikengine.bean.RobotHealth;
import com.ilkeiapps.slik.slikengine.retrofit.IM2M;
import com.microsoft.playwright.*;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocpsoft.prettytime.PrettyTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RobotService {
    private final EngineService engineService;
    private final PlaywrightDriverService webDriverService;
    private final MeterRegistry meterRegistry;
    private final IM2M m2mService;
    private final CommonProcessingService commonProcessingService;
    private final ProcessingService processingService;

    @Value("${cbas.engine.name}")
    private String robotName;

    @Value("${cbas.engine.core.endpoint}")
    private String robotEndpoint;

    @Value("${cbas.scheduler.rate.logoutduration.inminutes}")
    private Integer logoutDuration;

    private static final String STATUS_CRASH = "CRASH";
    private static final String STATUS_INITIAL = "INITIAL";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_IDLE = "IDLE";
    private static final String STATUS_NONE = "NONE";
    private static final String STATUS_DOWN = "DOWN";
    private static final String STATUS_UP = "UP";
    private static final String STATUS_STOP = "STOP";
    private static final String STATUS_STOPED = "STOPED";

    private static final String SEL_STATUS_LOGIN_ERROR = "LOGINERROR";
    private static final String SEL_STATUS_LOGIN_SUCCESS = "LOGINSUCCESS";

    private static final long PROCESSING_TIMEOUT_MINUTES = 3L;

    private static final List<String> SESSION_SCREEN_CODES = List.of("SESS1", "SESS2", "SITE", "RESET");

    public ApiResponse<PingResponse> ping() {
        var wrap = new ApiResponse<PingResponse>();
        wrap.setStatus(true);

        var obj = new PingResponse();
        obj.setName(robotName);
        obj.setEndpoint(robotEndpoint);

        wrap.insertNewData(obj);
        return wrap;
    }

    public void heartBeat() {
        var obj = new RobotHealth();
        obj.setCode(robotName);
        obj.setStatus(resolveRobotStatus());

        obj.setHikariConnectionActive(this.getMeasurement("hikaricp.connections.active"));
        obj.setUpTime(this.getMeasurement("process.uptime"));
        obj.setHikariConnection(this.getMeasurement("hikaricp.connections"));
        obj.setJmvMemoryUsage(this.getMeasurement("jvm.memory.used"));
        obj.setJvmCpuUsage(this.getMeasurement("process.cpu.usage"));
        obj.setDiskFree(this.getMeasurement("disk.free"));
        obj.setPendingProcess("");
        obj.setCurrentProcess(engineService.getCurrentProcess());
        obj.setStatusProcessing(engineService.getCurrentStatusEngine());
        obj.setLastUpdate(engineService.getLastUpdate());

        var es = engineService.getCurrentStatusEngine();
        var ls = engineService.getLastPlaywrigthStatus();

        log.info("heartBeat >>> engineStatus: {} playwrightStatus: {}", es, ls);

        var call = m2mService.heartbeat(obj);
        try {
            call.execute();
        } catch (IOException e) {
            log.error("heartBeat >>> {}", e.getMessage(), e);
        }
    }

    public ApiResponse<RobotHealth> statusProcess() {
        var wrap = new ApiResponse<RobotHealth>();
        wrap.setStatus(true);

        var obj = new RobotHealth();
        obj.setCode(robotName);
        obj.setStatus(resolveRobotStatus());
        obj.setHikariConnectionActive(this.getMeasurement("hikaricp.connections.active"));
        obj.setUpTime(this.getMeasurement("process.uptime"));
        obj.setHikariConnection(this.getMeasurement("hikaricp.connections"));
        obj.setJmvMemoryUsage(this.getMeasurement("jvm.memory.used"));
        obj.setJvmCpuUsage(this.getMeasurement("process.cpu.usage"));
        obj.setDiskFree(this.getMeasurement("disk.free"));
        obj.setPendingProcess("");
        obj.setCurrentProcess(engineService.getCurrentProcess());
        obj.setStatusProcessing(engineService.getCurrentStatusEngine());
        obj.setLastUpdate(engineService.getLastUpdate());

        wrap.insertNewData(obj);
        return wrap;
    }

    private String resolveRobotStatus() {
        // 1. Kalau Playwright sudah mati → langsung DOWN
        if (!webDriverService.isPlaywrightActive()) {
            log.warn("resolveRobotStatus >>> Playwright tidak aktif, set status DOWN");
            return STATUS_DOWN;
        }

        // 2. Cek status engine
        String engineStatus = engineService.getCurrentStatusEngine();
        if (engineStatus == null || engineStatus.isBlank()) {
            log.warn("resolveRobotStatus >>> engineStatus null/blank, set status DOWN");
            return STATUS_DOWN;
        }

        // Kalau CRASH / STOP / STOPPED / ERROR → DOWN
        if (STATUS_CRASH.equalsIgnoreCase(engineStatus) || STATUS_STOP.equalsIgnoreCase(engineStatus) || STATUS_STOPED.equalsIgnoreCase(engineStatus)) {
            return STATUS_DOWN;
        }

        // Selain itu anggap masih UP
        return STATUS_UP;
    }

    public void sendDownStatus() {
        var obj = new RobotHealth();
        obj.setCode(robotName);
        obj.setStatus(STATUS_DOWN);

        obj.setHikariConnectionActive(null);
        obj.setUpTime(null);
        obj.setHikariConnection(null);
        obj.setJmvMemoryUsage(null);
        obj.setJvmCpuUsage(null);
        obj.setDiskFree(null);
        obj.setPendingProcess("");
        obj.setCurrentProcess(engineService.getCurrentProcess());
        obj.setStatusProcessing(engineService.getCurrentStatusEngine());
        obj.setLastUpdate(engineService.getLastUpdate());

        log.info("sendDownStatus >>> sending DOWN heartbeat before shutdown");

        try {
            var call = m2mService.heartbeat(obj);
            call.execute();
        } catch (Exception e) {
            log.error("sendDownStatus >>> gagal kirim DOWN status: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("RobotService >>> Spring context shutting down (SpringApplicationShutdownHook), tandai robot DOWN");
        sendDownStatus();
    }

    public void previewNew() {
        ProcessBuilder pb = new ProcessBuilder("/home/servervpn/robot/preview.sh");
        Process proc = null;

        try {
            proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                log.error("robot >>> preview.sh exited with code {}", exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("robot >>> preview.sh interrupted", e);
        } catch (IOException e) {
            log.error("robot >>> error running preview.sh", e);
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    public void checkIdle() {
        log.info("checkIdle >>> ");

        String engineStatus = engineService.getCurrentStatusEngine();
        if (shouldSkipIdle(engineStatus)) {
            return;
        }

        Page driver = webDriverService.getDriver();
        if (!isDriverAvailableForIdle(driver)) {
            return;
        }

        handleProgressModalIfAny();

        if (handleSpecialScreenIfAny()) {
            return;
        }

        handleIdleOrProcessingState(engineStatus);
    }

    private boolean shouldSkipIdle(String engineStatus) {
        if (STATUS_CRASH.equals(engineStatus)) {
            log.info("checkIdle >>> CRASH, stopping");
            return true;
        }
        if (STATUS_INITIAL.equals(engineStatus)) {
            log.info("checkIdle >>> INITIAL, stopping");
            return true;
        }
        return false;
    }

    private boolean isDriverAvailableForIdle(Page driver) {
        if (driver == null) {
            log.warn("checkIdle >>> driver null, skip semua pengecekan");
            return false;
        }
        return true;
    }

    private void handleProgressModalIfAny() {
        Frame frame = webDriverService.getFrame("main");
        if (frame == null) {
            log.warn("checkIdle >>> frame is null, skip progress-modal check");
            return;
        }

        try {
            if (commonProcessingService.isProgressModalVisible(frame)) {
                commonProcessingService.removeProgressModal(frame);
            }
        } catch (Exception e) {
            log.error("checkIdle >>> error while checking/removing progress modal", e);
        }
    }

    private boolean handleSpecialScreenIfAny() {
        var screenshot = commonProcessingService.screenshot();
        var screenCode = commonProcessingService.checkScreenshot(screenshot);

        if (!SESSION_SCREEN_CODES.contains(screenCode)) {
            return false;
        }

        log.info("checkIdle >>> got screen: {}", screenCode);
        engineService.setCurrentStatusEngine(STATUS_CRASH);
        engineService.setLastPlaywrigthStatus(STATUS_NONE);
        return true;
    }

    private void handleIdleOrProcessingState(String engineStatus) {
        if (STATUS_IDLE.equals(engineStatus)) {
            handleIdleState();
            return;
        }

        if (STATUS_PROCESSING.equals(engineStatus)) {
            handleProcessingState();
        }
    }

    private void handleIdleState() {
        log.info("checkIdle >>> IDLE");
        var from = engineService.getLastUpdate();
        var to = LocalDateTime.now();

        var duration = Duration.between(from, to);
        log.info("checkIdle >>> IDLE from: {} to: {} duration: {}s", from, to, duration.toSeconds());

        if (duration.toSeconds() > 20) {
            commonProcessingService.doRefresh();
            engineService.setLastUpdate(LocalDateTime.now());
        }
    }

    private void handleProcessingState() {
        log.info("checkIdle >>> PROCESSING");
        var from = engineService.getLastUpdate();
        var to = LocalDateTime.now();

        var duration = Duration.between(from, to);
        log.info("checkIdle >>> PROCESSING from: {} to: {} duration: {}m", from, to, duration.toMinutes());

        if (duration.toMinutes() > 10) {
            commonProcessingService.doRefresh();
            engineService.setCurrentStatusEngine(STATUS_IDLE);
            engineService.setLastUpdate(LocalDateTime.now());
        }
    }

    public void checkLogin() throws Exception {
        log.info("checkLogin >>>");

        String engineStatus = engineService.getCurrentStatusEngine();
        if (isEngineCrashed(engineStatus)) {
            return;
        }

        Page driver = webDriverService.getDriver();
        if (!isDriverAvailable(driver)) {
            return;
        }

        if (handleSessionProblemIfAny()) {
            return;
        }

        handleLoginFlow(engineStatus, driver);
    }

    private boolean isEngineCrashed(String engineStatus) {
        if (STATUS_CRASH.equals(engineStatus)) {
            log.info("checkLogin >>> status: {}, stopping", engineStatus);
            return true;
        }
        return false;
    }

    private boolean isDriverAvailable(Page driver) {
        if (driver == null) {
            log.warn("checkLogin >>> driver null, mungkin belum init / gagal init");
            return false;
        }
        return true;
    }

    private void handleLoginFlow(String engineStatus, Page driver) {
        String lastPlaywrightStatus = engineService.getLastPlaywrigthStatus();
        log.info("checkLogin >>> current engineStatus: {}, lastPlaywrigthStatus: {}",
                engineStatus, lastPlaywrightStatus);

        if (!handleEngineStatus(engineStatus, lastPlaywrightStatus)) {
            return;
        }

        if (!shouldProceedBasedOnPlaywrigthStatus(lastPlaywrightStatus)) {
            return;
        }

        if (isHomeLinkVisible(driver)) {
            markLoginSuccess();
            return;
        }

        handleLoginRetryIfNeeded(lastPlaywrightStatus);
    }

    private void handleLoginRetryIfNeeded(String lastPlaywrightStatus) {
        if (SEL_STATUS_LOGIN_ERROR.equals(lastPlaywrightStatus)) {
            log.info("checkLogin >>> last status LOGINERROR, retry login now");
            processingService.doLogin();
            return;
        }

        if (isLogoutDurationExceeded()) {
            log.info("checkLogin >>> logoutDuration exceeded, do login");
            processingService.doLogin();
        }
    }

    private boolean handleSessionProblemIfAny() {
        byte[] ss = commonProcessingService.screenshot();
        String checkScreen = commonProcessingService.checkScreenshot(ss);

        if (!SESSION_SCREEN_CODES.contains(checkScreen)) {
            return false;
        }

        log.info("checkLogin >>> failed with result: {}", checkScreen);
        engineService.setCurrentStatusEngine(STATUS_IDLE);
        engineService.setLastPlaywrigthStatus(STATUS_NONE);
        engineService.setLastUpdate(LocalDateTime.now());
        webDriverService.restart();
        return true;
    }

    private boolean handleEngineStatus(String engineStatus, String lastPlaywrigthStatus) {
        if (STATUS_INITIAL.equals(engineStatus)) {
            return true;
        }

        if (STATUS_PROCESSING.equals(engineStatus)) {
            LocalDateTime from = engineService.getLastUpdate();
            LocalDateTime to = LocalDateTime.now();
            Duration duration = Duration.between(from, to);

            log.info("checkLogin >>> PROCESSING from: {} to: {} duration: {} minutes", from, to, duration.toMinutes());

            if (duration.toMinutes() > PROCESSING_TIMEOUT_MINUTES) {
                commonProcessingService.doRefresh();
                engineService.setCurrentStatusEngine(STATUS_IDLE);
                engineService.setLastUpdate(LocalDateTime.now());
            }
            return true;
        }

        if (STATUS_IDLE.equals(engineStatus) && (SEL_STATUS_LOGIN_ERROR.equals(lastPlaywrigthStatus) || STATUS_NONE.equals(lastPlaywrigthStatus))) {
            log.info("checkLogin >>> IDLE with status {}, allow login retry", lastPlaywrigthStatus);
            return true;
        }

        log.info("checkLogin >>> es: {}, ls: {} process not initial", engineStatus, lastPlaywrigthStatus);
        return false;
    }

    private boolean shouldProceedBasedOnPlaywrigthStatus(String lastPlaywrigthStatus) {
        if (!SEL_STATUS_LOGIN_ERROR.equals(lastPlaywrigthStatus) && !STATUS_NONE.equals(lastPlaywrigthStatus)) {
            log.info("checkLogin >>> Playwright status is: {}, not LOGINERROR or NONE", lastPlaywrigthStatus);
            return false;
        }
        return true;
    }

    private boolean isHomeLinkVisible(Page driver) {
        if (driver == null) {
            log.warn("checkLogin >>> driver is null, skip cek home link");
            return false;
        }

        if (driver.isClosed()) {
            log.warn("checkLogin >>> driver sudah closed, skip cek home link");
            return false;
        }

        try {
            Locator el = driver.locator("#top1menu");
            if (el != null && el.count() > 0 && el.isVisible()) {
                log.info("checkLogin >>> link home found");
                return true;
            }
        } catch (PlaywrightException e) {
            handleHomeLinkPlaywrightException(e);
        } catch (Exception e) {
            // error lain di luar Playwright, tetap ERROR
            log.error("checkLogin >>> link home not found (Exception umum)", e);
        }

        return false;
    }

    private void handleHomeLinkPlaywrightException(PlaywrightException e) {
        String msg = e.getMessage();

        if (msg != null && msg.contains("Target page, context or browser has been closed")) {
            log.warn("checkLogin >>> gagal cek home link (TargetClosedError), " + "page sudah closed. detail={}", msg);
            return;
        }

        log.error("checkLogin >>> link home not found (PlaywrightException)", e);
    }

    private boolean isLogoutDurationExceeded() {
        LocalDateTime then = engineService.getLastUpdate();
        LocalDateTime now = LocalDateTime.now();
        Duration duration = Duration.between(then, now);

        log.info("checkLogin >>> duration: {} minutes", duration.toMinutes());
        return duration.toMinutes() > logoutDuration;
    }

    private void markLoginSuccess() {
        engineService.setCurrentStatusEngine(STATUS_IDLE);
        engineService.setLastPlaywrigthStatus(SEL_STATUS_LOGIN_SUCCESS);
        engineService.setLastUpdate(LocalDateTime.now());
    }

    public void doRefresh() {
        log.info("doRefresh >>> start");
        var frame = webDriverService.getFrame("main");
        var es = engineService.getCurrentStatusEngine();
        if (es.equals(STATUS_CRASH)) {
            log.info("doRefresh >>> engine crashed, stopping");
        } else {
            var isFound = false;
            try {
                var el = frame.locator("#top1menu");
                if (el != null && el.count() > 0 && el.isVisible()) {
                    log.info("doRefresh >>> link home found");
                    isFound = true;
                }
            } catch (Exception e) {
                log.error("doRefresh >>> " + e.getMessage());
                log.error("doRefresh >>> link home not found");
            }

            if (isFound) {

                log.info("doRefresh >>> link home found, click");
                frame.locator("xpath=/html/body/div/div[1]/div/a[1]").click(new Locator.ClickOptions().setTimeout(5000));
                log.info("doRefresh >>> link home found, click done");

            } else {
                webDriverService.getDriver().reload();
            }
        }
    }

    public void checkCrash() {
        String es = engineService.getCurrentStatusEngine();
        if (STATUS_CRASH.equals(es)) {
            log.info("checkCrash >>> status: {}, restarting", es);
            webDriverService.restart();
        }
    }

    public void restart() {
        log.info("initDriver >>> restarting....");
        var driver = webDriverService.getDriver();

        driver.close();
        engineService.setCurrentStatusEngine(STATUS_INITIAL);
        engineService.setLastPlaywrigthStatus(STATUS_NONE);
        engineService.setLastUpdate(LocalDateTime.now());

        processingService.doLogin();
        log.info("initDriver >>> setup done....");
    }

    private String getMeasurement(String name) {
        Collection<Meter> meters = meterRegistry.find(name).meters();
        if (meters.isEmpty()) {
            return "-";
        }

        Meter meter = meters.iterator().next();
        String unit = meter.getId().getBaseUnit();

        Iterator<Measurement> it = meter.measure().iterator();
        if (!it.hasNext()) {
            return "-";
        }
        Double val = it.next().getValue();

        PrettyTime pt = new PrettyTime(Locale.forLanguageTag("id-ID"));
        DecimalFormat dc = new DecimalFormat("0.00");

        if (unit != null && unit.length() > 2) {
            if ("seconds".equals(unit)) {
                LocalDateTime ut = LocalDateTime.now().minusSeconds(Math.round(val));
                return pt.format(ut);
            } else if ("bytes".equals(unit)) {
                return this.toHumanReadableWithEnum(val.longValue());
            }
        }

        return dc.format(val);
    }

    @Getter
    enum SizeUnitSIPrefixes {
        BYTES(1L),
        KB(BYTES.unitBase * 1_000),
        MB(KB.unitBase * 1_000),
        GB(MB.unitBase * 1_000),
        TB(GB.unitBase * 1_000),
        PB(TB.unitBase * 1_000),
        EB(PB.unitBase * 1_000);

        private final long unitBase;

        SizeUnitSIPrefixes(long unitBase) {
            this.unitBase = unitBase;
        }

        public static List<SizeUnitSIPrefixes> unitsInDescending() {
            List<SizeUnitSIPrefixes> list = new ArrayList<>(Arrays.asList(values()));
            list.sort(Comparator.comparing(SizeUnitSIPrefixes::getUnitBase).reversed());
            return list;
        }
    }

    private String toHumanReadableWithEnum(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("Invalid file size: " + size);
        }

        for (SizeUnitSIPrefixes unit : SizeUnitSIPrefixes.unitsInDescending()) {
            long base = unit.getUnitBase();
            if (size >= base) {
                return formatSize(size, base, unit.name());
            }
        }

        return formatSize(size, SizeUnitSIPrefixes.BYTES.getUnitBase(), SizeUnitSIPrefixes.BYTES.name());
    }

    private String formatSize(long size, long divider, String unitName) {
        DecimalFormat decimalFormat = new DecimalFormat("#.##");
        return decimalFormat.format((double) size / divider) + " " + unitName;
    }

}
