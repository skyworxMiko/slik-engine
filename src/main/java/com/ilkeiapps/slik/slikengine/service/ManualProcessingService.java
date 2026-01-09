package com.ilkeiapps.slik.slikengine.service;

import com.ilkeiapps.slik.slikengine.bean.AppRequest;
import com.ilkeiapps.slik.slikengine.bean.AppRequestPayload;
import com.ilkeiapps.slik.slikengine.bean.ProcessReportRequest;
import com.ilkeiapps.slik.slikengine.bean.SubmitRequest;
import com.ilkeiapps.slik.slikengine.retrofit.IM2M;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManualProcessingService {
    private static final int SLEEP_BEFORE_SUBMIT_MS = 3_000;
    private static final int SLEEP_AFTER_WRONG_ACTION_MS = 5_000;
    private static final String MI_LOG_PREFIX = "manual interaktif>>> ";
    private static final String MI_ACT_FORM = "Isi Form Permintaan Interaktif";
    private static final String MI_ACT_CAPTCHA = "Pengisian Captcha dan Submit";
    private static final String MI_ACT_GENERATE = "Klik Tombol Generate Ideb";
    private static final String MI_MSG_OUT_OF_HOURS = "Anda tidak memiliki hak akses untuk melakukan permintaan iDeb di luar Jam Operasional SLIK.";
    private static final String MI_MSG_DATA_NOT_FOUND = "Data tidak ditemukan";
    private static final String MI_MSG_WAIT_SUPERVISOR = "menunggu persetujuan supervisor";
    private static final String ENGINE_STATUS_CRASH = "CRASH";
    private static final String USER_REFERENCE_CODE = "#USER_REFERENCE_CODE";
    private static final String MI_MSG_REF_DUPLICATE = "Nomor Referensi terdeteksi duplikat";
    private static final String MI_MSG_DUPLICATE = "duplikat";
    private static final String MI_MSG_NOT_MATCH = "tidak sama";

    private enum CaptchaResult {
        DATA_FOUND,
        DATA_NOT_FOUND,
        STOP_ALL
    }

    private final PlaywrightDriverService webDriverService;
    private final CaptchaService captchaService;
    private final CommonProcessingService commonProcessingService;
    private final EngineService engineService;
    private final ActivityServices activityService;
    private final IM2M m2mService;
    private final DownloadProcessingService downloadProcessingService;

    @Value("${cbas.engine.name}")
    private String robotName;

    public void manualInteraktif(AppRequestPayload src) {
        log.info("{} starting", MI_LOG_PREFIX);

        if (src == null || src.getData() == null || src.getData().isEmpty()) {
            log.warn("{} payload kosong, tidak ada data untuk diproses", MI_LOG_PREFIX);
            return;
        }

        Page driver = webDriverService.getDriver();
        Frame frame = webDriverService.getFrame("main");

        if (!openManualInteractiveMenu(driver, frame)) {
            return;
        }

        String custType = "";
        for (AppRequest req : src.getData()) {
            String newCustType = processSingleManualInteractive(req, driver, custType);
            if (newCustType == null) {
                // null = STOP_ALL (misalnya screenshot error di tahap search/captcha)
                return;
            }
            custType = newCustType;
        }
    }

    private boolean openManualInteractiveMenu(Page driver, Frame frame) {
        if (frame == null) {
            log.error("{} frame is null saat open menu", MI_LOG_PREFIX);
            return false;
        }

        log.info("{} klik menu permintaan data", MI_LOG_PREFIX);
        Locator menuPermintaanData = frame.locator("html > body > div > div:nth-of-type(1) > div > ul > li:nth-of-type(3) > a");
        if (menuPermintaanData == null) {
            log.error("{} menu permintaan tidak ditemukan", MI_LOG_PREFIX);
            return false;
        }

        menuPermintaanData.click(new Locator.ClickOptions().setTimeout(5000));
        driver.waitForLoadState();

        try {
            log.info("{} cek menu user sudah tampil", MI_LOG_PREFIX);
            Locator userRef = frame.locator(USER_REFERENCE_CODE);
            userRef.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            return true;
        } catch (Exception e) {
            log.error("{} cek menu user tidak tampil", MI_LOG_PREFIX, e);
            return false;
        }
    }

    private String processSingleManualInteractive(AppRequest req, Page driver, String lastCustType) {
        var sLog = activityService.start(this.robotName);

        log.info("{} proses no surat: {}", MI_LOG_PREFIX, req.getNoRefCounter());
        engineService.setCurrentProcess("Identitas: " + req.getNoRefCounter());

        var pp = new ProcessReportRequest();
        pp.setIdAppDistribute(req.getId());

        var sr = new SubmitRequest();
        sr.setIdAppRequest(req.getId());
        sr.setApprovalVariable("REQ");

        boolean isIndividual = "IND".equalsIgnoreCase(req.getAppRequestCustType());

        // switch menu IND / COM
        MenuSwitchResult menuResult = switchMenuForManual(req, driver, lastCustType, isIndividual);
        String custType = menuResult.getCustType();
        if (!menuResult.isSuccess()) {
            sLog.setName(MI_ACT_FORM);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
            return custType;
        }

        Frame frame = webDriverService.getFrame("main");
        if (frame == null) {
            log.error("{} frame is null (awal pengisian form)", MI_LOG_PREFIX);
            sLog.setName(MI_ACT_FORM);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
            return custType;
        }

        if (!fillManualForm(req, frame, isIndividual)) {
            sLog.setName(MI_ACT_FORM);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
            return custType;
        }

        // selesai fase isi form
        sLog.setName(MI_ACT_FORM);
        sLog.setAppId(req.getId());
        activityService.stop(sLog);

        // cek jam operasional
        if (isOutOfOperationalHours(frame, req)) {
            return custType;
        }

        // captcha
        String cp = captchaService.fetchCapcha2TensorPlaywright(frame);
        if (cp == null) {
            log.error("{} {} to entry captcha is null", MI_LOG_PREFIX, req.getNoRefCounter());
            return custType;
        }

        safeSleep(SLEEP_BEFORE_SUBMIT_MS, MI_LOG_PREFIX + req.getNoRefCounter() + " sebelum submit captcha");

        CaptchaResult captchaResult = handleManualCaptchaAndSearch(req, driver, frame, cp, pp, sr);
        if (captchaResult == CaptchaResult.STOP_ALL) {
            return null;
        }
        if (captchaResult == CaptchaResult.DATA_NOT_FOUND) {
            return custType;
        }

        handleManualResultSelectionAndGenerate(req, frame, pp, sr);

        return custType;
    }

    private record MenuSwitchResult(boolean success, String custType) {
        static MenuSwitchResult success(String custType) {
            return new MenuSwitchResult(true, custType);
        }

        static MenuSwitchResult failure(String currentType) {
            return new MenuSwitchResult(false, currentType);
        }

        boolean isSuccess() {
            return success;
        }

        String getCustType() {
            return custType;
        }
    }

    private MenuSwitchResult switchMenuForManual(AppRequest req, Page driver, String lastCustType, boolean isIndividual) {
        String custType;

        if (isIndividual) {
            engineService.setLastUpdate(LocalDateTime.now());

            if ("COM".equalsIgnoreCase(lastCustType)) {
                if (this.selectMenu("Individual")) {
                    driver.waitForLoadState();
                } else {
                    log.error("{} {} error select menu Individual", MI_LOG_PREFIX, req.getNoRefCounter());
                    return MenuSwitchResult.failure(lastCustType);
                }
            }

            custType = "IND";
        } else {
            if (lastCustType.isEmpty() || "IND".equalsIgnoreCase(lastCustType)) {
                if (this.selectMenu("Badan Usaha")) {
                    driver.waitForLoadState();
                } else {
                    log.error("{} {} error select menu Badan Usaha", MI_LOG_PREFIX, req.getNoRefCounter());
                    return MenuSwitchResult.failure(lastCustType);
                }
            }

            custType = "COM";
        }

        return MenuSwitchResult.success(custType);
    }

    private boolean fillManualForm(AppRequest req, Frame frame, boolean isIndividual) {
        try {
            log.info("{} {} to entry kode tujuan", MI_LOG_PREFIX, req.getNoRefCounter());
            Locator tujuan = frame.locator("#REPORT_REQUEST_PURPOSE_CODE");
            String tujuanLabel = isIndividual ? "Tujuan Permintaan (IND)" : "Tujuan Permintaan (COM)";
            ensureSelectOptionFilled(tujuan, req.getAppRequestPurpose(), tujuanLabel, req.getNoRefCounter());

            if (!isIndividual) {
                frame.waitForFunction("() => { " +
                                      "const el = document.querySelector('#USER_REFERENCE_CODE'); " +
                                      "return !!el && !el.disabled && !el.readOnly && el.offsetParent !== null; " +
                                      "}");
            }

            Supplier<Locator> kodeRefSupplier = isIndividual
                    ? () -> frame.locator("input[data-validation-label^='Kode']")
                    : () -> frame.locator(USER_REFERENCE_CODE);

            ensureInputFilledWithRetry(kodeRefSupplier, req.getNoRefCounter(), isIndividual ? "Kode Referensi (IND)" : "Kode Referensi (COM)", req.getNoRefCounter());

            String identityValue = resolveIdentityValue(req, isIndividual);
            if (!isIndividual) {
                frame.waitForFunction("() => { " +
                                      "const el = document.querySelector(\"input[data-validation-label^='NPWP'], #APP_REQUEST_NPWP\"); " +
                                      "return !!el && !el.disabled && !el.readOnly && el.offsetParent !== null; " +
                                      "}");
            }

            Supplier<Locator> identitySupplier = isIndividual
                    ? () -> frame.locator("input[data-validation-label^='Nomor']")
                    : () -> frame.locator("input[data-validation-label^='NPWP'], #APP_REQUEST_NPWP");

            ensureInputFilledWithRetry(identitySupplier, identityValue, isIndividual ? "NIK" : "NPWP", req.getNoRefCounter());

            return true;

        } catch (Exception e) {
            log.error("{} {} error proses pengisian form ({})", MI_LOG_PREFIX, req.getNoRefCounter(), isIndividual ? "IND" : "COM", e);
            return false;
        }
    }

    private String resolveIdentityValue(AppRequest req, boolean isIndividual) {
        if (!isIndividual) {
            return req.getAppRequestNpwp();
        }

        Integer counter = req.getCounter();
        if (counter != null && counter == 1) {
            return req.getAppRequestKtp();
        }
        return req.getAppRequestPartnerKtp();
    }

    private boolean isOutOfOperationalHours(Frame frame, AppRequest req) {
        try {
            Locator exipred = frame.locator("#result_info");
            String txt = exipred.textContent();
            if (txt != null && txt.contains(MI_MSG_OUT_OF_HOURS)) {
                log.error("{} {} diluar jam operasional slik", MI_LOG_PREFIX, req.getNoRefCounter());
                return true;
            }
        } catch (Exception e) {
            log.debug("{} {} tidak menemukan pesan jam operasional", MI_LOG_PREFIX, req.getNoRefCounter());
        }
        return false;
    }

    private void safeSleep(long millis, String context) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} interrupted during sleep", context, e);
        }
    }

    private CaptchaResult handleManualCaptchaAndSearch(AppRequest req, Page driver, Frame frame, String captchaValue, ProcessReportRequest pp, SubmitRequest sr) {
        var sLog = activityService.start(this.robotName);

        try {
            Locator captchaBox = frame.locator("#captcha");
            captchaBox.fill(captchaValue);

            log.info("processing >>> {} to click search button", req.getNoRefCounter());
            Locator scr = frame.locator("#search-button");
            scr.click(new Locator.ClickOptions().setTimeout(2000));

            driver.waitForLoadState();

            sLog.setName(MI_ACT_CAPTCHA);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
            sLog = activityService.start(this.robotName);

            if (commonProcessingService.isProgressModalVisible(frame)) {
                log.error("processLogin >>> Gagal cek progress bar");
                commonProcessingService.removeProgressModal(frame);
            }

            if (handleDuplicateOrNotMatchDialogs(frame)) {
                sLog.setName(MI_ACT_CAPTCHA);
                sLog.setAppId(req.getId());
                activityService.stop(sLog);
                return CaptchaResult.DATA_NOT_FOUND;
            }

            sLog.setName(MI_ACT_CAPTCHA);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
            sLog = activityService.start(this.robotName);

            commonProcessingService.clickOkButtonInFrame(frame);

            byte[] sc = commonProcessingService.screenshot();
            String stat = commonProcessingService.checkScreenshot(sc);
            if (!"NONE".equals(stat)) {
                log.error("{} {} status adalah: {}", MI_LOG_PREFIX, req.getNoRefCounter(), stat);
                sLog.setName(MI_ACT_CAPTCHA);
                sLog.setAppId(req.getId());
                activityService.stop(sLog);
                return CaptchaResult.STOP_ALL;
            }

            frame.waitForSelector("#elapsed-time");
            Locator result = frame.locator("#elapsed-time");
            String txt = result.innerHTML();

            sLog.setName(MI_ACT_CAPTCHA);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);

            if (txt != null && txt.contains(MI_MSG_DATA_NOT_FOUND)) {
                log.warn("{} {} {}", MI_LOG_PREFIX, req.getNoRefCounter(), MI_MSG_DATA_NOT_FOUND);
                handleManualDataNotFoundWithPrintAndExport(req, driver, frame, pp, sr);
                return CaptchaResult.DATA_NOT_FOUND;
            }

            return CaptchaResult.DATA_FOUND;

        } catch (Exception e) {
            log.error("{} gagal proses search/captcha : {}", MI_LOG_PREFIX, e.getMessage());
            sLog.setName(MI_ACT_CAPTCHA);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
            return CaptchaResult.DATA_NOT_FOUND;
        }
    }

    private boolean handleDuplicateOrNotMatchDialogs(Frame frame) {
        if (commonProcessingService.checkDialogContainText(frame, MI_MSG_REF_DUPLICATE) || commonProcessingService.checkDialogContainText(frame, "INQ-00001")) {

            log.warn("{} dialog noRef duplikat terdeteksi (INQ-00001)", MI_LOG_PREFIX);
            commonProcessingService.clickOkButtonInFrame(frame);
            return true;
        }

        if (commonProcessingService.checkDialogContainText(frame, MI_MSG_NOT_MATCH) || commonProcessingService.checkDialogContainText(frame, MI_MSG_DATA_NOT_FOUND) || commonProcessingService.checkDialogContainText(frame, MI_MSG_DUPLICATE)) {
            log.warn("{} dialog not-match/duplicate/data-not-found terdeteksi", MI_LOG_PREFIX);
            commonProcessingService.clickOkButtonInFrame(frame);
            return true;
        }
        return false;
    }

    private Frame waitForReportViewerFrame(Page driver, String noRefCounter) {
        for (int i = 0; i < 30; i++) {
            List<Frame> frames = driver.frames();
            log.info("{} {} cek frame viewer attempt={} totalFrame={}", MI_LOG_PREFIX, noRefCounter, i + 1, frames.size());

            for (Frame frame : frames) {
                Frame candidate = findReportViewerFrameCandidate(frame, noRefCounter);
                if (candidate != null) {
                    return candidate;
                }
            }

            safeSleep(1_000L, MI_LOG_PREFIX + noRefCounter + " menunggu frame ReportViewer");
        }

        log.error("{} {} frame ReportViewer tidak ditemukan", MI_LOG_PREFIX, noRefCounter);
        return null;
    }

    private Frame findReportViewerFrameCandidate(Frame frame, String noRefCounter) {
        String name = null;
        String url = null;

        try {
            name = frame.name();
        } catch (Exception ex) {
            log.warn("{} {} gagal ambil name frame: {}", MI_LOG_PREFIX, noRefCounter, ex.toString());
        }

        try {
            url = frame.url();
        } catch (Exception ex) {
            log.warn("{} {} gagal ambil url frame: {}", MI_LOG_PREFIX, noRefCounter, ex.toString());
        }

        try {
            // 1) Nama frame mengandung "tab-iframe"
            if (name != null && name.toLowerCase().contains("tab-iframe")) {
                log.info("{} {} frame ReportViewer ditemukan by name={} url={}", MI_LOG_PREFIX, noRefCounter, name, url);
                return frame;
            }

            // 2) Di dalamnya ada body#reportViewer
            Locator bodyViewer = frame.locator("body#reportViewer");
            if (bodyViewer != null && bodyViewer.count() > 0) {
                log.info("{} {} frame ReportViewer ditemukan by body#reportViewer name={} url={}", MI_LOG_PREFIX, noRefCounter, name, url);
                return frame;
            }

            // 3) Atau ada tombol Export
            Locator exportBtn = frame.locator("button#export");
            if (exportBtn != null && exportBtn.count() > 0) {
                log.info("{} {} frame ReportViewer ditemukan by button#export name={} url={}", MI_LOG_PREFIX, noRefCounter, name, url);
                return frame;
            }

        } catch (Exception ex) {
            log.warn("{} {} gagal inspect frame name={} url={}: {}", MI_LOG_PREFIX, noRefCounter, name, url, ex.toString());
        }

        return null;
    }

    private void handleManualDataNotFoundWithPrintAndExport(AppRequest req, Page driver, Frame frame, ProcessReportRequest pp, SubmitRequest sr) {
        boolean exported = false;
        try {
            // (1) KLIK CETAK (kalau ada & visible)
            Locator cetakButton = frame.locator("#report-button");
            if (cetakButton.count() == 0) cetakButton = driver.locator("#report-button");

            if (cetakButton.count() > 0 && cetakButton.first().isVisible()) {
                log.info("{} {} klik tombol Cetak (#report-button)", MI_LOG_PREFIX, req.getNoRefCounter());
                cetakButton.first().click(new Locator.ClickOptions().setTimeout(5_000));
            } else {
                log.info("{} {} tombol Cetak tidak ada/hidden -> lanjut export tanpa klik Cetak", MI_LOG_PREFIX, req.getNoRefCounter());
            }

            // (2) TUNGGU TAB "Pencetakan" AKTIF
            frame.waitForSelector("#report-tab-anchor[aria-expanded='true']");
            driver.waitForLoadState();

            Frame reportFrame = waitForReportViewerFrame(driver, req.getNoRefCounter());
            if (reportFrame == null) {
                log.error("{} {} frame reportViewer tidak ditemukan", MI_LOG_PREFIX, req.getNoRefCounter());
                return;
            }

            // === TUNGGU TOMBOL EXPORT ENABLED DULU ===
            Locator exportButton = reportFrame.locator("button#export");
            exportButton.waitFor(new Locator.WaitForOptions().setTimeout(30_000));

            long start = System.currentTimeMillis();
            long maxWaitMillis = 60_000L;

            while (!exportButton.isEnabled() && (System.currentTimeMillis() - start) < maxWaitMillis) {
                log.info("{} {} tombol export masih disabled, tunggu 1 detik...", MI_LOG_PREFIX, req.getNoRefCounter());
                safeSleep(1_000L, MI_LOG_PREFIX + req.getNoRefCounter() + " menunggu export enable");
            }

            if (!exportButton.isEnabled()) {
                log.error("{} {} tombol export tetap disabled setelah {} ms, fallback ke proses lama", MI_LOG_PREFIX, req.getNoRefCounter(), maxWaitMillis);
                return;
            }

            // (3) KLIK BUTTON EXPORT DAN (4) PILIH PDF + TUNGGU DOWNLOAD
            Download download = driver.waitForDownload(() -> {
                exportButton.click(new Locator.ClickOptions().setTimeout(10_000));
                Locator pdfItem = reportFrame.locator("text=PDF Document (pdf)");
                if (pdfItem.count() == 0) {
                    pdfItem = reportFrame.locator("text=PDF Document");
                }

                log.info("{} {} kandidat menu PDF: {}", MI_LOG_PREFIX, req.getNoRefCounter(), pdfItem.count());

                pdfItem.first().click(new Locator.ClickOptions().setTimeout(20_000));
            });

            if (download == null) {
                log.error("{} {} gagal mendapatkan objek Download untuk laporan kosong, fallback ke proses lama", MI_LOG_PREFIX, req.getNoRefCounter());
                return;
            }

            downloadProcessingService.processSingleDownloadedFile(req, download, false);
            exported = true;
            log.info("{} {} berhasil trigger download laporan kosong: {}", MI_LOG_PREFIX, req.getNoRefCounter(), download.suggestedFilename());
        } catch (Exception e) {
            log.error("{} {} gagal proses cetak & export saat data tidak ditemukan: {}", MI_LOG_PREFIX, req.getNoRefCounter(), e.getMessage(), e);
        } finally {
            handleManualDataNotFound(req, pp, sr);
            backToManualForm(driver, req);
            log.info("{} {} NOT_FOUND selesai (exported={})", MI_LOG_PREFIX, req.getNoRefCounter(), exported);
        }
    }

    private void handleManualDataNotFound(AppRequest req, ProcessReportRequest pp, SubmitRequest sr) {
        try {
            log.info("{} {} set status to fail request with payload: {}", MI_LOG_PREFIX, req.getNoRefCounter(), pp);
            m2mService.processFail(pp).execute();
            m2mService.processCallback(sr).execute();
        } catch (IOException e) {
            log.error("processing >>> {} error set status to fail request with payload: {}", req.getNoRefCounter(), pp, e);
        }
    }

    private void backToManualForm(Page driver, AppRequest req) {
        String menu = "IND".equalsIgnoreCase(req.getAppRequestCustType()) ? "Individual" : "Badan Usaha";

        try {
            log.info("{} {} kembali ke form manual (klik menu {})", MI_LOG_PREFIX, req.getNoRefCounter(), menu);

            if (selectMenu(menu)) {
                driver.waitForLoadState();
            }

            Frame f = webDriverService.getFrame("main");
            if (f != null) {
                f.locator(USER_REFERENCE_CODE)
                        .waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            }
        } catch (Exception e) {
            log.warn("{} {} gagal balik ke form manual, refresh fallback", MI_LOG_PREFIX, req.getNoRefCounter(), e);
            webDriverService.refresh();
            safeSleep(5_000L, "after refresh");
        }
    }

    private void handleManualResultSelectionAndGenerate(AppRequest req, Frame frame, ProcessReportRequest pp, SubmitRequest sr) {
        selectResultsOnAllPages(req, frame);
        clickGenerateIdi(req, frame);

        if (commonProcessingService.isProgressModalVisible(frame)) {
            log.error("processLogin >>> Gagal cek progress bar");
            commonProcessingService.removeProgressModal(frame);
        }

        clickOkButtonIfAny(req, frame);

        byte[] sc = commonProcessingService.screenshot();
        String stat = commonProcessingService.checkScreenshot(sc);
        if ("NONE".equals(stat)) {
            waitForSaveConfirmationFromOjk(req, frame, pp, sr);
        } else {
            handleWrongActionAfterGenerate(req);
        }
    }

    private void selectResultsOnAllPages(AppRequest req, Frame frame) {
        try {
            var sLog = activityService.start(this.robotName);

            Locator checkboxes = frame.locator("input[type='checkbox'][name='INDIVIDUAL_ID']");
            Locator pages = frame.locator(".dhx_page");

            this.check(checkboxes, req.getId());

            sLog.setName(MI_ACT_CAPTCHA);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);

            int pageCount = pages.count();
            for (int yy = 1; yy < pageCount; yy++) {
                Locator cb = pages.nth(yy);
                cb.click(new Locator.ClickOptions().setTimeout(2000));

                checkboxes = frame.locator("input[type='checkbox'][name='INDIVIDUAL_ID']");
                this.check(checkboxes, req.getId());
            }

        } catch (Exception e) {
            log.error("{} {} gagal proses klik hasil pencarian : {}", MI_LOG_PREFIX, req.getNoRefCounter(), e.getMessage());
        }
    }

    private void clickGenerateIdi(AppRequest req, Frame frame) {
        var sLog = activityService.start(this.robotName);
        try {
            Locator gen = frame.locator("#generate-idi-button");
            gen.click(new Locator.ClickOptions().setTimeout(2000));
        } catch (Exception e) {
            log.error("{} gagal proses klik generate idi : {}", MI_LOG_PREFIX, e.getMessage());
        } finally {
            sLog.setName(MI_ACT_GENERATE);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
        }
    }

    private void clickOkButtonIfAny(AppRequest req, Frame frame) {
        var sLog = activityService.start(this.robotName);
        try {
            if (frame == null) {
                log.warn("{} {} clickOkButtonIfAny >>> frame null, skip", MI_LOG_PREFIX, req.getNoRefCounter());
                return;
            }

            Locator visibleOkButton = frame.locator("button.btn.btn-primary:visible", new Frame.LocatorOptions().setHasText("OK"));
            visibleOkButton.click(new Locator.ClickOptions().setTimeout(5_000));

            log.info("{} {} clickOkButtonIfAny >>> OK button clicked", MI_LOG_PREFIX, req.getNoRefCounter());

        } catch (PlaywrightException e) {
            log.warn("{} {} clickOkButtonIfAny >>> no visible OK button or timeout: {}", MI_LOG_PREFIX, req.getNoRefCounter(), e.getMessage());
        } catch (Exception e) {
            log.error("{} {} clickOkButtonIfAny >>> unexpected error click ok button: {}", MI_LOG_PREFIX, req.getNoRefCounter(), e.getMessage(), e);
        } finally {
            sLog.setName(MI_ACT_GENERATE);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
        }
    }

    private void waitForSaveConfirmationFromOjk(AppRequest req, Frame frame, ProcessReportRequest pp, SubmitRequest sr) {
        var sLog = activityService.start(this.robotName);
        try {
            log.info("processing >>> {} waiting for save confirmation from OJK", req.getNoRefCounter());

            Locator progressDiv = frame.locator("#generate-idi-on-progress");
            progressDiv.waitFor(new Locator.WaitForOptions().setTimeout(15_000));

            if (progressDiv.isVisible() && progressDiv.isEnabled()) {
                String headerText = progressDiv.locator("h3").innerText();
                log.info("processing >>> {} got header text: {}", req.getNoRefCounter(), headerText);

                if (headerText != null && headerText.contains(MI_MSG_WAIT_SUPERVISOR)) {
                    log.info("processing >>> {} set status done with payload: {}", req.getNoRefCounter(), pp);
                    m2mService.processDone(pp).execute();

                    log.info("processing >>> {} send callback with payload: {}", req.getNoRefCounter(), sr);
                    m2mService.processCallback(sr).execute();
                } else {
                    log.error("processing >>> {} result confirmation not found", req.getNoRefCounter());
                }
            } else {
                log.error("processing >>> {} result confirmation not displayed", req.getNoRefCounter());
            }
        } catch (Exception e) {
            log.error("processLogin >>> Fail to check response from OJK slik", e);
        } finally {
            sLog.setName(MI_ACT_GENERATE);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);
        }
    }

    private void handleWrongActionAfterGenerate(AppRequest req) {
        log.info("ProcessSlikManual >>> processing {} wrong action", req.getNoRefCounter());
        webDriverService.refresh();

        safeSleep(SLEEP_AFTER_WRONG_ACTION_MS, "processLogin >>> sleep setelah wrong action");

        byte[] ss = this.commonProcessingService.screenshot();
        String stat = commonProcessingService.checkScreenshot(ss);
        if (!"NONE".equals(stat)) {
            engineService.setCurrentStatusEngine(ENGINE_STATUS_CRASH);
        }
    }

    private void check(Locator checkboxes, Long appId) {
        var sLog = activityService.start(this.robotName);
        try {
            if (checkboxes == null || checkboxes.count() < 1) {
                log.error("Centang nasabah tidak ada");
                return;
            }

            for (int yy = 0; yy < checkboxes.count(); yy++) {
                var cb = checkboxes.nth(yy);
                cb.click(new Locator.ClickOptions().setTimeout(2000));
            }
        } catch (Exception e) {
            log.error("manual >>> gagal proses klik generate idi : {}", e.getMessage());
        }
        sLog.setName(MI_ACT_CAPTCHA);
        sLog.setAppId(appId);
        activityService.stop(sLog);
    }

    private boolean selectMenu(String menu) {
        Frame frame = webDriverService.getFrame("main");
        if (frame == null) {
            log.error("selectMenu >>> frame is null, cannot select menu {}", menu);
            return false;
        }

        try {
            Locator link = frame.locator("#left-menu").getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(menu));
            link.click(new Locator.ClickOptions().setTimeout(2000));
            return true;
        } catch (Exception e) {
            log.error("selectMenu >>> select menu '{}' gagal: {}", menu, e.getMessage());
            return false;
        }
    }

    private void ensureSelectOptionFilled(Locator select, String expectedValue, String fieldName, String noRef) {
        String expected = expectedValue == null ? "" : expectedValue.trim();
        if (expected.isEmpty()) {
            log.warn("manual >>> {} {} expected kosong, skip pengisian", noRef, fieldName);
            return;
        }

        try {
            select.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));

            for (int attempt = 1; attempt <= 3; attempt++) {
                select.selectOption(new SelectOption().setValue(expected));

                String actual = readSelectValue(select);

                if (expected.equals(actual)) {
                    log.info("manual >>> {} {} terisi benar (attempt {}), value={}", noRef, fieldName, attempt, actual);
                    return;
                }

                log.warn(
                        "manual >>> {} {} belum sesuai (attempt {}). expected={}, actual={}",
                        noRef, fieldName, attempt, expected, actual
                );
            }

            log.error("manual >>> {} {} tidak bisa dipastikan terisi setelah 3 attempt", noRef, fieldName);

        } catch (Exception e) {
            log.error("manual >>> {} error saat mengisi {}", noRef, fieldName, e);
        }
    }

    private String readSelectValue(Locator select) {
        try {
            String actual = select.inputValue();
            if (actual == null) {
                actual = "";
            }
            return actual.trim();
        } catch (Exception ex) {
            Object v = select.evaluate("el => el.value");
            String actual = v == null ? "" : v.toString();
            return actual.trim();
        }
    }

    private void ensureInputFilledWithRetry(Supplier<Locator> locatorSupplier, String expectedValue, String fieldName, String noRef) {
        String expected = expectedValue == null ? "" : expectedValue.trim();
        if (expected.isEmpty()) {
            log.warn("manual >>> {} {} expected kosong (required), field tidak diisi", noRef, fieldName);
            return;
        }

        Locator input = locatorSupplier.get();
        if (waitForInputVisible(input, fieldName, noRef)) {
            if (tryFillInputWithRetry(input, locatorSupplier, expected, fieldName, noRef)) {
                return;
            }

            String finalVal = readInputValueSafe(input);
            log.error("manual >>> {} {} gagal dipastikan terisi setelah 3 attempt. expected={}, final={}", noRef, fieldName, expected, finalVal);
        }
    }

    private boolean tryFillInputWithRetry(Locator input, Supplier<Locator> locatorSupplier, String expected, String fieldName, String noRef) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                input.click(new Locator.ClickOptions().setTimeout(5000));
                input.fill("");
                input.fill(expected);
                input.press("Tab");

                String current = readInputValue(input);

                if (expected.equals(current)) {
                    log.info("manual >>> {} {} terisi dengan benar (attempt {}), value={}", noRef, fieldName, attempt, current);
                    return true;
                }

                log.warn("manual >>> {} {} belum sesuai (attempt {}). expected={}, actual={}", noRef, fieldName, attempt, expected, current);

            } catch (com.microsoft.playwright.PlaywrightException ex) {
                if (!isNotAttachedError(ex)) {
                    log.error("manual >>> {} error Playwright saat mengisi {} (attempt {})", noRef, fieldName, attempt, ex);
                    continue;
                }

                log.warn("manual >>> {} {} locator not attached, relokasi (attempt {})", noRef, fieldName, attempt);
                input = locatorSupplier.get();
                if (!waitForInputVisible(input, fieldName, noRef)) {
                    return false;
                }

            } catch (Exception e) {
                log.error("manual >>> {} error saat mengisi {} (attempt {})", noRef, fieldName, attempt, e);
            }
        }
        return false;
    }

    private boolean waitForInputVisible(Locator input, String fieldName, String noRef) {
        try {
            input.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            return true;
        } catch (Exception e) {
            log.error("manual >>> {} {} tidak muncul / tidak visible", noRef, fieldName, e);
            return false;
        }
    }

    private boolean isNotAttachedError(com.microsoft.playwright.PlaywrightException ex) {
        String msg = ex.getMessage();
        return msg != null && msg.contains("not attached");
    }

    private String readInputValue(Locator input) {
        try {
            String current = input.inputValue();
            if (current == null) {
                current = "";
            }
            return current.trim();
        } catch (Exception ex) {
            Object v = input.evaluate("el => el.value");
            String current = v == null ? "" : v.toString();
            return current.trim();
        }
    }

    private String readInputValueSafe(Locator input) {
        try {
            return readInputValue(input);
        } catch (Exception e) {
            return "<error read>";
        }
    }
}
