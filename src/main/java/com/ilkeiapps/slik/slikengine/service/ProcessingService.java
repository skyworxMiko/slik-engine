package com.ilkeiapps.slik.slikengine.service;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.ilkeitool.validator.IlKeiValidator;
import com.ilkeiapps.slik.slikengine.bean.ApiResponse;
import com.ilkeiapps.slik.slikengine.bean.AppRequestPayload;
import com.ilkeiapps.slik.slikengine.bean.AppRequestWrapper;
import com.ilkeiapps.slik.slikengine.entity.EngineConfig;
import com.ilkeiapps.slik.slikengine.entity.QEngineConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingService {

    private final EntityManager em;
    private final CriteriaBuilderFactory configBuilder;
    private final BatchProcessingService batchProcessingService;
    private final ManualProcessingService manualProcessingService;
    private final ApprovalProcessingService approvalProcessingService;
    private final CommonProcessingService commonProcessingService;
    private final DownloadProcessingService downloadProcessingService;
    private final CombinationProcessingService combinationProcessingService;
    private final EngineService engineService;
    private final PlaywrightDriverService webDriverService;

    @Value("${cbas.engine.name}")
    private String robotName;

    private static final String STATUS_IDLE = "IDLE";
    private static final String STATUS_INITIAL = "INITIAL";
    private static final String STATUS_NONE = "NONE";
    private static final String STATUS_CRASH = "CRASH";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String APPV = "APPV";
    private static final String RROLE = "RROLE";

    private static final String MSG_ROBOT_INVALID = "Kode robot tidak valid";
    private static final String MSG_DATA_INVALID = "Data request tidak valid";
    private static final String MSG_ENGINE_CRASH = "Robot crash, stopping processing";
    private static final String MSG_ENGINE_BUSY = "Robot masih mengerjakan tugas lain";

    @FunctionalInterface
    private interface PayloadProcessor {
        void process(AppRequestPayload payload);
    }

    public boolean doLogin() {
        log.info("doLogin >>> start");

        Page pageDriver = webDriverService.getDriver();
        String currentStatus = engineService.getCurrentStatusEngine();

        // 1) Navigate ke SLIK + handle timeout
        if (!navigateToSlik(pageDriver, currentStatus)) {
            markLoginError();
            return false;
        }

        // 2) Kalau status bukan INITIAL / NONE, anggap sudah login
        if (!STATUS_INITIAL.equals(currentStatus) && !STATUS_NONE.equals(currentStatus)) {
            return true;
        }

        engineService.setCurrentStatusEngine(STATUS_PROCESSING);

        // 3) Sudah login tapi engine masih INITIAL/NONE? (misal habis restart engine)
        if (checkAlreadyLoggedIn(pageDriver)) {
            markLoginSuccess();
            return true;
        }

        // 4) Handle halaman non-login (maintenance / banner lain)
        if (handleNonLoginScreenshot()) {
            return true;
        }

        // 5) Ambil role config
        String role = loadRoleConfig();
        if (role == null) {
            markLoginError();
            return false;
        }

        // 6) Perform login
        if (!performLogin(pageDriver, role)) {
            markLoginError();
            return false;
        }

        markLoginSuccess();
        return true;
    }

    private boolean navigateToSlik(Page pageDriver, String currentStatus) {
        try {
            log.info("doLogin >>> navigate to SLIK: https://slik.ojk.go.id");

            pageDriver.navigate("https://slik.ojk.go.id",
                    new Page.NavigateOptions().setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            pageDriver.waitForLoadState(LoadState.NETWORKIDLE);

            log.info("doLogin >>> page loaded / DOM ready, current engine status: {}", currentStatus);
            return true;

        } catch (PlaywrightException e) {
            String msg = e.getMessage();

            if (isTargetClosedMessage(msg)) {
                log.warn("doLogin >>> gagal navigate ke SLIK (TargetClosedError: page/browser sudah closed). detail={}", msg);
                return false;
            }

            if (e instanceof TimeoutError timeoutError) {
                log.error("doLogin >>> gagal navigate ke SLIK (timeout / network error): {}", timeoutError.getMessage(), timeoutError);
            } else {
                log.error("doLogin >>> gagal navigate ke SLIK (Playwright error): {}", msg, e);
            }
            return false;
        }
    }

    private boolean checkAlreadyLoggedIn(Page driver) {
        try {
            log.info("doLogin >>> checking home link");
            Locator el = driver.locator("xpath=//*[@id='top1menu']");
            el.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            log.info("doLogin >>> home link found (already logged in)");
            return true;
        } catch (Exception e) {
            log.warn("doLogin >>> home link not found, need login");
            return false;
        }
    }

    private boolean handleNonLoginScreenshot() {
        byte[] sc = commonProcessingService.screenshot();
        String chk = commonProcessingService.checkScreenshot(sc);

        if (!"LOGIN".equals(chk) && !STATUS_NONE.equals(chk)) {
            log.info("doLogin >>> home not found, input type not found, do refresh, chk is: {}", chk);
            engineService.setCurrentStatusEngine(STATUS_INITIAL);
            webDriverService.refreshLogin();
            return true;
        }
        return false;
    }

    private String loadRoleConfig() {
        QEngineConfig qsg = new QEngineConfig("o");
        var configs = configBuilder.create(em, EngineConfig.class)
                .from(EngineConfig.class, qsg.getMetadata().getName())
                .where(qsg.code.toString()).eq(RROLE)
                .getResultList();

        if (configs == null || configs.isEmpty()) {
            log.error("doLogin >>> RROLE config not found");
            return null;
        }

        return configs.get(0).getValue();
    }

    private boolean performLogin(Page driver, String role) {
        if (driver == null) {
            log.warn("doLogin >>> driver null saat performLogin");
            markLoginError();
            return false;
        }

        if (driver.isClosed()) {
            log.warn("doLogin >>> driver sudah closed saat performLogin");
            markLoginError();
            return false;
        }

        try {
            log.info("doLogin >>> load web");
            driver.navigate("https://slik.ojk.go.id");
            log.info("doLogin >>> load web done");

            int valLogin = APPV.equals(role) ? 2 : 1;

            var login = commonProcessingService.processLogin(valLogin);
            if (!Boolean.TRUE.equals(login.getResult())) {
                log.error("doLogin >>> unable to login");
                markLoginError();
                driver.reload();
                return false;
            }

            return true;

        } catch (PlaywrightException e) {
            String msg = e.getMessage();

            if (isTargetClosedMessage(msg)) {
                log.warn("doLogin >>> unable to login, page/browser sudah closed (TargetClosedError). detail={}", msg);
            } else {
                log.error("doLogin >>> unable to login (Playwright error), message: {}", msg, e);
            }

            markLoginError();
            return false;

        } catch (Exception e) {
            log.error("doLogin >>> unable to login, message: {}", e.getMessage(), e);
            markLoginError();
            return false;
        }
    }

    private void markLoginSuccess() {
        log.info("doLogin >>> success");
        engineService.setCurrentStatusEngine(STATUS_IDLE);
        engineService.setLastPlaywrigthStatus("LOGINSUCCESS");
        engineService.setLastUpdate(LocalDateTime.now());
    }

    private void markLoginError() {
        engineService.setCurrentStatusEngine(STATUS_INITIAL);
        engineService.setLastPlaywrigthStatus("LOGINERROR");
        engineService.setLastUpdate(LocalDateTime.now());
    }

    public ApiResponse<AppRequestWrapper> process(AppRequestWrapper src) {
        log.info("meltheexecutor >>> processing payload");

        ApiResponse<AppRequestWrapper> wrap = new ApiResponse<>();
        wrap.setStatus(false);

        if (src == null) {
            wrap.setMessage("Payload tidak boleh null");
            return wrap;
        }

        String es = engineService.getCurrentStatusEngine();
        if (STATUS_CRASH.equals(es)) {
            log.info("meltheexecutor >>> robot is crash, stopping");
            wrap.setMessage(MSG_ENGINE_CRASH);
            return wrap;
        }

        if (!STATUS_IDLE.equals(es)) {
            log.info("meltheexecutor >>> robot is busy, stopping");
            wrap.setMessage(MSG_ENGINE_BUSY);
            return wrap;
        }

        engineService.setCurrentStatusEngine(STATUS_PROCESSING);

        try {
            // 1) BATCH REQUEST
            if (processSegment(src.getBatch(), "processing batch", "batch", true, wrap, batchProcessingService::uploadBatch)) {
                return wrap;
            }

            // 2) MANUAL INTERAKTIF
            if (processSegment(src.getManual(), "processing manual", "manual", true, wrap, manualProcessingService::manualInteraktif)) {
                return wrap;
            }

            // 3) KOMBINASI
            if (processSegment(src.getCombine(), "processing combination", "combination", true, wrap, combinationProcessingService::manualKombinasi)) {
                return wrap;
            }

            // 4) MANUAL DOWNLOAD
            if (processSegment(src.getManualDownload(), "download ideb", "manual download", false, wrap, downloadProcessingService::manualDownload)) {
                return wrap;
            }

            // 5) BATCH DOWNLOAD
            if (processSegment(src.getBatchDownload(), "download ideb batch", "batch download", false, wrap, downloadProcessingService::batchDownload)) {
                return wrap;
            }

        } catch (RuntimeException e) {
            log.error("meltheexecutor >>> unexpected error saat memproses payload", e);
            wrap.setMessage("Terjadi kesalahan tak terduga saat memproses payload");
            return wrap;
        } finally {
            log.info("process >>> setting status idle");
            engineService.setCurrentStatusEngine(STATUS_IDLE);
            log.info("meltheexecutor >>> set status IDLE");
        }

        wrap.setStatus(true);
        wrap.insertNewData(src);
        return wrap;
    }

    private boolean processSegment(AppRequestPayload payload, String logContext, String segmentName, boolean requireData, ApiResponse<AppRequestWrapper> wrap, PayloadProcessor processor) {
        if (payload == null) {
            return false;
        }

        log.info("meltheexecutor >>> {}", logContext);

        if (!validatePayload(payload, wrap, logContext, requireData)) {
            return true;
        }

        try {
            processor.process(payload);
        } catch (RuntimeException e) {
            log.error("meltheexecutor >>> error saat {}: {}", logContext, e.getMessage(), e);
            wrap.setMessage("Terjadi kesalahan saat " + logContext);
            engineService.setCurrentStatusEngine(STATUS_IDLE);
            return true;
        }

        return handlePostSegment(segmentName);
    }

    private boolean validatePayload(AppRequestPayload payload, ApiResponse<?> wrap, String logContext, boolean requireData) {
        var pr = IlKeiValidator.builder()
                .withPojo(payload)
                .pick(AppRequestPayload.Fields.robot, "Nama Robot").asString().isMandatory().withMinLen(2).pack()
                .pick(AppRequestPayload.Fields.mode, "Mode").asString().isMandatory().withMinLen(2).pack()
                .validate();
        if (Boolean.FALSE.equals(pr.getResult())) {
            String msg = pr.getMessage();
            wrap.setMessage(msg);
            log.error("meltheexecutor >>> {} payload error: {}", logContext, msg);
            engineService.setCurrentStatusEngine(STATUS_IDLE);
            return false;
        }

        if (!robotName.equals(payload.getRobot())) {
            wrap.setMessage(MSG_ROBOT_INVALID);
            log.error("meltheexecutor >>> {} kode robot tidak valid", logContext);
            engineService.setCurrentStatusEngine(STATUS_IDLE);
            return false;
        }

        if (requireData && (payload.getData() == null || payload.getData().isEmpty())) {
            wrap.setMessage(MSG_DATA_INVALID);
            log.error("meltheexecutor >>> {} data request tidak valid", logContext);
            engineService.setCurrentStatusEngine(STATUS_IDLE);
            return false;
        }

        return true;
    }

    private boolean handlePostSegment(String segmentName) {
        String es = engineService.getCurrentStatusEngine();
        commonProcessingService.doRefresh();

        if (STATUS_CRASH.equals(es)) {
            log.error("meltheexecutor >>> processing {} error: {}", segmentName, es);
            return true;
        }

        log.info("meltheexecutor >>> processing {} done", segmentName);
        return false;
    }

    public void approve(AppRequestPayload src) {
        log.info("meltheexecutor >>> processing approval....");

        String es = engineService.getCurrentStatusEngine();

        if (STATUS_CRASH.equals(es)) {
            log.info("meltheexecutor >>> robot is crash, stopping");
            return;
        }

        if (!STATUS_IDLE.equals(es)) {
            log.info("meltheexecutor >>> robot is busy, stopping");
            return;
        }

        engineService.setCurrentStatusEngine(STATUS_PROCESSING);

        approvalProcessingService.approval(src);

        es = engineService.getCurrentStatusEngine();
        commonProcessingService.doRefresh();

        if (STATUS_CRASH.equals(es)) {
            log.error("meltheexecutor >>> approval ended with CRASH state");
            return;
        }

        log.info("approval >>> setting status idle");
        engineService.setCurrentStatusEngine(STATUS_IDLE);
    }

    @SuppressWarnings("resource")
    public void processLogout(Frame frame) throws InterruptedException {
        log.info("logoutV2 >>> start");

        if (frame == null) {
            log.warn("logoutV2 >>> frame null, kemungkinan sudah di login page, skip logout");
            return;
        }

        try {
            Locator logoutLink = frame.locator("a[href='javascript:logoutAll()']");
            logoutLink.waitFor(new Locator.WaitForOptions().setTimeout(5_000));

            if (logoutLink.isVisible()) {
                log.info("logoutV2 >>> clicking logout link");
                logoutLink.click(new Locator.ClickOptions().setTimeout(5_000));
            } else {
                log.warn("logoutV2 >>> logout link tidak visible, skip click");
            }

            Locator loginForm = frame.page().locator("form#form1");
            loginForm.waitFor(new Locator.WaitForOptions().setTimeout(15_000));

            log.info("logoutV2 >>> login form terdeteksi, logout sukses");
        } catch (com.microsoft.playwright.TimeoutError e) {
            log.warn("logoutV2 >>> timeout saat logout / menunggu login page: {}", e.getMessage());
        } catch (Exception e) {
            log.error("logoutV2 >>> error tidak terduga saat logout", e);
        }

        Thread.sleep(2_000);
    }

    public void logout() throws InterruptedException {
        log.info("logout >>> start");

        Thread.sleep(5_000);

        try {
            processLogout(webDriverService.getFrame("main"));
        } finally {
            engineService.setCurrentStatusEngine(STATUS_INITIAL);
            engineService.setLastPlaywrigthStatus(STATUS_NONE);
            engineService.setLastUpdate(LocalDateTime.now());
        }
    }

    public void restartBrowser() {
        log.info("restartBrowser >>> manual restart requested");

        try {
            logout();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("restartBrowser >>> interrupted during logout, canceling restart", ie);
            return;
        } catch (Exception e) {
            log.warn("restartBrowser >>> logout error (non-fatal): {}", e.getMessage(), e);
        }

        webDriverService.restart();

        boolean ok = doLogin();
        log.info("restartBrowser >>> doLogin result = {}", ok);
    }

    private boolean isTargetClosedMessage(String msg) {
        return msg != null
               && msg.contains("Target page, context or browser has been closed");
    }

    public void reloadBrowser() {
        Page page = webDriverService.getDriver();

        if (page == null) {
            log.warn("reloadBrowser >>> driver null, tidak bisa reload halaman");
            throw new IllegalStateException("Driver belum tersedia, tidak bisa reload halaman");
        }

        if (page.isClosed()) {
            log.warn("reloadBrowser >>> driver sudah closed, tidak bisa reload halaman");
            throw new IllegalStateException("Browser sudah closed, tidak bisa reload halaman");
        }

        try {
            log.info("reloadBrowser >>> reload current page");

            page.reload(new Page.ReloadOptions().setTimeout(60_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.NETWORKIDLE);

            log.info("reloadBrowser >>> reload selesai");
        } catch (PlaywrightException e) {
            log.error("reloadBrowser >>> gagal reload halaman (Playwright error): {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("reloadBrowser >>> gagal reload halaman (Exception umum): {}", e.getMessage(), e);
            throw e;
        }
    }

}
