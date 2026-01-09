package com.ilkeiapps.slik.slikengine.service;

import com.ilkeiapps.slik.slikengine.bean.*;
import com.ilkeiapps.slik.slikengine.bean.scoring.GetScoring;
import com.ilkeiapps.slik.slikengine.bean.scoring.ScoringItem;
import com.ilkeiapps.slik.slikengine.config.ScoringConfig;
import com.ilkeiapps.slik.slikengine.retrofit.IM2M;
import com.ilkeiapps.slik.slikengine.retrofit.ISCORING;
import com.ilkeiapps.slik.slikengine.utility.SimilarityUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CombinationProcessingService {

    private final PlaywrightDriverService webDriverService;
    private final CaptchaService captchaService;
    private final CommonProcessingService commonProcessingService;
    private final IM2M m2mService;
    private final EngineService engineService;
    private final ActivityServices activityService;
    private final DownloadProcessingService downloadProcessingService;
    private final ISCORING scoringApi;

    private ScoringConfig scoringConfig;
    private volatile long scoringConfigLoadedAt;
    private final Object scoringLock = new Object();
    private static final long SCORING_TTL_MILLIS = 5 * 60 * 1000L;

    private static final String MK_LOG_PREFIX = "manual Kombinasi >>> ";
    private static final String MK_SEGMENT_CAPTCHA = "Pengisian Captcha dan Submit";
    private static final String MK_SEGMENT_GENERATE_IDE = "Klik Tombol Generate Ideb";

    private static final String MK_SEL_USER_REF_LABEL = "label[for='USER_REFERENCE_CODE']";
    private static final String MK_SEL_COMBINATION_DIV = "#data-combination-div";

    private static final String MK_TYPE_IND = "IND";
    private static final String MK_TYPE_COM = "COM";

    private static final String MK_MSG_OUT_OF_OPERATION = "Anda tidak memiliki hak akses untuk melakukan permintaan iDeb di luar Jam Operasional SLIK.";
    private static final String MK_MSG_DATA_NOT_FOUND = "Data tidak ditemukan";
    private static final String MK_MSG_DUPLICATE = "duplikat";
    private static final String MK_MSG_NOT_MATCH = "tidak sama";
    private static final String MK_MSG_CAPTCHA_NOT_MATCH = "teks captcha tidak sama";
    private static final int MK_MAX_CAPTCHA_RETRY = 3;
    private static final String MK_MSG_WAIT_SUPERVISOR = "menunggu persetujuan supervisor";
    private static final String MK_MSG_REF_DUPLICATE = "Nomor Referensi terdeteksi duplikat";
    private static final int MK_MENU_TIMEOUT_MS = 8000;
    private static final int MK_MENU_RETRY = 3;

    private static final String ENGINE_STATUS_CRASH = "CRASH";
    private static final String SIMILARITY = "SIMILARITY";
    private static final String KEMIRIPAN = "KEMIRIPAN";
    private static final String FORMAT_DATE = "dd-MM-yyyy";
    private static final int MK_COMBINE_VISIBLE_TIMEOUT_MS = 2000;

    private static final String MK_SEL_MATCH_EXACT = "#exact-matching-radio";
    private static final String MK_SEL_MATCH_SIMILARITY = "#similarity-matching-radio";

    private enum ManualKombinasiResult {
        CONTINUE,
        STOP_ALL
    }

    private enum CaptchaResult {
        DATA_FOUND,
        DATA_NOT_FOUND,
        STOP_ALL
    }

    @Value("${cbas.engine.name}")
    private String robotName;

    public void manualKombinasi(AppRequestPayload src) {
        log.info("{} starting", MK_LOG_PREFIX);

        if (src == null || CollectionUtils.isEmpty(src.getData())) {
            return;
        }

        Page driver = webDriverService.getDriver();
        Frame frame = webDriverService.getFrame("main");
        if (frame == null) {
            log.error("{} frame is null (awal)", MK_LOG_PREFIX);
            return;
        }

        Frame mainFrame = openKombinasiLandingPage(driver, frame);
        if (mainFrame == null) {
            return;
        }

        String currentCustType = "";
        for (AppRequest req : src.getData()) {
            ManualKombinasiResult result = ManualKombinasiResult.CONTINUE;

            try {
                result = processManualKombinasiForRequest(req, driver, currentCustType);
                currentCustType = MK_TYPE_IND.equals(req.getAppRequestCustType()) ? MK_TYPE_IND : MK_TYPE_COM;
            } catch (Exception e) {
                log.error("{} {} unexpected error", MK_LOG_PREFIX, req.getNoRefCounter(), e);
            }

            if (result == ManualKombinasiResult.STOP_ALL) {
                return;
            }
        }
    }

    private ManualKombinasiResult processManualKombinasiForRequest(AppRequest req, Page driver, String lastCustType) {
        var sLog = activityService.start(this.robotName);
        try {
            log.info("{} {}", MK_LOG_PREFIX, req.getNoRefCounter());
            engineService.setCurrentProcess("Kombinasi: " + req.getNoRefCounter());

            driver.waitForLoadState();

            var pp = new ProcessReportRequest();
            pp.setIdAppDistribute(req.getId());

            var sr = new SubmitRequest();
            sr.setIdAppRequest(req.getId());
            sr.setApprovalVariable("REQ");

            if (!startM2mRequest(req, pp)) {
                return ManualKombinasiResult.CONTINUE;
            }

            if (req.getAppRequestCustType() == null) {
                log.error("{} {} tipe nasabah null", MK_LOG_PREFIX, req.getNoRefCounter());
                return ManualKombinasiResult.CONTINUE;
            }

            Frame comboFrame = prepareFrameForType(req, driver, lastCustType);
            if (comboFrame == null) {
                return ManualKombinasiResult.CONTINUE;
            }

            try {
                if (MK_TYPE_IND.equals(req.getAppRequestCustType())) {
                    fillIndividualForm(req, comboFrame);
                } else {
                    fillCompanyForm(req, comboFrame);
                }
            } catch (Exception e) {
                log.error("{} {} error processing {} data", MK_LOG_PREFIX, req.getNoRefCounter(), req.getAppRequestCustType(), e);
                return ManualKombinasiResult.CONTINUE;
            }

            sLog.setName("Isi Form Permintaan Kombinasi");
            sLog.setAppId(req.getId());
            activityService.stop(sLog);

            sLog = activityService.start(this.robotName);

            if (isOutOfOperationalTime(comboFrame, req)) {
                sLog.setName(MK_SEGMENT_CAPTCHA);
                sLog.setAppId(req.getId());
                activityService.stop(sLog);
                return ManualKombinasiResult.CONTINUE;
            }

            String cp = captchaService.fetchCapcha2TensorPlaywright(comboFrame);
            if (cp == null) {
                log.error("{} {} captcha is null", MK_LOG_PREFIX, req.getNoRefCounter());
                sLog.setName(MK_SEGMENT_CAPTCHA);
                sLog.setAppId(req.getId());
                activityService.stop(sLog);
                return ManualKombinasiResult.CONTINUE;
            }

            CaptchaResult capResult = handleCaptchaAndSearch(req, driver, comboFrame, cp, pp, sr);

            sLog.setName(MK_SEGMENT_CAPTCHA);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);

            if (capResult == CaptchaResult.STOP_ALL) {
                return ManualKombinasiResult.STOP_ALL;
            }
            if (capResult == CaptchaResult.DATA_NOT_FOUND) {
                return ManualKombinasiResult.CONTINUE;
            }

            sLog = activityService.start(this.robotName);
            handleResultSelectionAndGenerate(req, comboFrame, pp, sr);
            sLog.setName(MK_SEGMENT_GENERATE_IDE);
            sLog.setAppId(req.getId());
            activityService.stop(sLog);

            return ManualKombinasiResult.CONTINUE;
        } finally {
            try {
                if (sLog != null) {
                    activityService.stop(sLog);
                }
            } catch (Exception ignore) {
                // intentionally ignored
            }
        }
    }

    private CaptchaResult handleCaptchaAndSearch(AppRequest req, Page driver, Frame frame, String captchaValue, ProcessReportRequest pp, SubmitRequest sr) {

        int attempt = 1;
        String currentCaptcha = captchaValue;

        while (attempt <= MK_MAX_CAPTCHA_RETRY) {
            log.info("{} {} to entry captcha (attempt={})", MK_LOG_PREFIX, req.getNoRefCounter(), attempt);

            Locator captchaBox = frame.locator("#captcha");
            captchaBox.fill(currentCaptcha);

            safeSleep(3_000L, MK_LOG_PREFIX + req.getNoRefCounter() + " sebelum klik search");

            try {
                clickSearchButton(req, driver, frame);

                handleProgressModalIfVisible(frame, "processLogin >>> setelah klik search-button");

                // --- CAPTCHA SALAH? ---
                if (commonProcessingService.checkDialogContainText(frame, MK_MSG_CAPTCHA_NOT_MATCH)) {
                    log.warn("{} {} captcha salah saat search (attempt={})", MK_LOG_PREFIX, req.getNoRefCounter(), attempt);
                    commonProcessingService.clickOkButtonInFrame(frame);

                    if (attempt >= MK_MAX_CAPTCHA_RETRY) {
                        log.error("{} {} captcha tetap salah setelah {} attempt, STOP_ALL", MK_LOG_PREFIX, req.getNoRefCounter(), attempt);
                        return CaptchaResult.STOP_ALL;
                    }

                    // ambil captcha baru dan ulangi
                    String newCaptcha = captchaService.fetchCapcha2TensorPlaywright(frame);
                    if (newCaptcha == null) {
                        log.error("{} {} captcha baru null setelah captcha salah, STOP_ALL", MK_LOG_PREFIX, req.getNoRefCounter());
                        return CaptchaResult.STOP_ALL;
                    }

                    currentCaptcha = newCaptcha;
                    attempt++;
                    continue;
                }

                // --- dialog lain: duplikat / data tidak match ---
                if (handleDuplicateOrNotMatchDialogs(frame)) {
                    return CaptchaResult.DATA_NOT_FOUND;
                }

                commonProcessingService.clickOkButtonInFrame(frame);

                if (!isScreenshotCleanAfterSearch(req)) {
                    return CaptchaResult.STOP_ALL;
                }

                return interpretSearchResult(req, driver, frame, pp, sr);
            } catch (Exception e) {
                log.error("{} gagal proses search/captcha : {}", MK_LOG_PREFIX, e.getMessage());
                return CaptchaResult.DATA_NOT_FOUND;
            }
        }

        // fallback, mestinya tidak sampai sini
        return CaptchaResult.DATA_NOT_FOUND;
    }

    private void clickSearchButton(AppRequest req, Page driver, Frame frame) {
        log.info("processing >>> {} to click search button", req.getNoRefCounter());
        Locator scr = frame.locator("#search-button");
        scr.click(new Locator.ClickOptions().setTimeout(2000));
        driver.waitForLoadState();
    }

    private boolean handleDuplicateOrNotMatchDialogs(Frame frame) {
        if (commonProcessingService.checkDialogContainText(frame, MK_MSG_REF_DUPLICATE) || commonProcessingService.checkDialogContainText(frame, "INQ-00001")) {

            log.warn("{} dialog noRef duplikat terdeteksi (INQ-00001)", MK_LOG_PREFIX);
            commonProcessingService.clickOkButtonInFrame(frame);
            return true;
        }

        if (commonProcessingService.checkDialogContainText(frame, MK_MSG_NOT_MATCH) || commonProcessingService.checkDialogContainText(frame, MK_MSG_DATA_NOT_FOUND) || commonProcessingService.checkDialogContainText(frame, MK_MSG_DUPLICATE)) {
            log.warn("{} dialog not-match/duplicate/data-not-found terdeteksi", MK_LOG_PREFIX);
            commonProcessingService.clickOkButtonInFrame(frame);
            return true;
        }
        return false;
    }

    private boolean isScreenshotCleanAfterSearch(AppRequest req) {
        byte[] sc = commonProcessingService.screenshot();
        String stat = commonProcessingService.checkScreenshot(sc);
        log.info("manualKombinasi >>> {} screenshot status setelah search: {}", req.getNoRefCounter(), stat);

        if (!"NONE".equals(stat)) {
            log.error("{} {} status adalah: {}", MK_LOG_PREFIX, req.getNoRefCounter(), stat);
            return false;
        }
        return true;
    }

    private CaptchaResult interpretSearchResult(AppRequest req, Page driver, Frame frame, ProcessReportRequest pp, SubmitRequest sr) {
        frame.waitForSelector("#elapsed-time");
        Locator result = frame.locator("#elapsed-time");
        String txt = result.innerHTML();

        if (txt != null && txt.contains(MK_MSG_DATA_NOT_FOUND)) {
            log.error("{} {} {}", MK_LOG_PREFIX, req.getNoRefCounter(), MK_MSG_DATA_NOT_FOUND);

            handleManualDataNotFoundWithPrintAndExport(req, driver, frame, pp, sr);
            return CaptchaResult.DATA_NOT_FOUND;
        }

        return CaptchaResult.DATA_FOUND;
    }

    private void handleManualDataNotFoundWithPrintAndExport(AppRequest req, Page driver, Frame frame, ProcessReportRequest pp, SubmitRequest sr) {
        boolean exported = false;
        try {
            // (1) KLIK CETAK (kalau ada & visible)
            Locator cetakButton = frame.locator("#report-button");
            if (cetakButton.count() == 0) cetakButton = driver.locator("#report-button");

            if (cetakButton.count() > 0 && cetakButton.first().isVisible()) {
                log.info("{} {} klik tombol Cetak (#report-button)", MK_LOG_PREFIX, req.getNoRefCounter());
                cetakButton.first().click(new Locator.ClickOptions().setTimeout(5_000));
            } else {
                log.info("{} {} tombol Cetak tidak ada/hidden -> lanjut export tanpa klik Cetak", MK_LOG_PREFIX, req.getNoRefCounter());
            }

            // (2) TUNGGU TAB "Pencetakan" AKTIF
            frame.waitForSelector("#report-tab-anchor[aria-expanded='true']");
            driver.waitForLoadState();

            Frame reportFrame = waitForReportViewerFrame(driver, req.getNoRefCounter());
            if (reportFrame == null) {
                log.error("{} {} frame reportViewer tidak ditemukan", MK_LOG_PREFIX, req.getNoRefCounter());
                return;
            }

            // === TUNGGU TOMBOL EXPORT ENABLED DULU ===
            Locator exportButton = reportFrame.locator("button#export");
            exportButton.waitFor(new Locator.WaitForOptions().setTimeout(30_000));

            long start = System.currentTimeMillis();
            long maxWaitMillis = 60_000L;

            while (!exportButton.isEnabled() && (System.currentTimeMillis() - start) < maxWaitMillis) {
                log.info("{} {} tombol export masih disabled, tunggu 1 detik...", MK_LOG_PREFIX, req.getNoRefCounter());
                safeSleep(1_000L, MK_LOG_PREFIX + req.getNoRefCounter() + " menunggu export enable");
            }

            if (!exportButton.isEnabled()) {
                log.error("{} {} tombol export tetap disabled setelah {} ms, fallback ke proses lama", MK_LOG_PREFIX, req.getNoRefCounter(), maxWaitMillis);
                return;
            }

            // (3) KLIK BUTTON EXPORT DAN (4) PILIH PDF + TUNGGU DOWNLOAD
            Download download = driver.waitForDownload(() -> {
                exportButton.click(new Locator.ClickOptions().setTimeout(10_000));
                Locator pdfItem = reportFrame.locator("text=PDF Document (pdf)");
                if (pdfItem.count() == 0) {
                    pdfItem = reportFrame.locator("text=PDF Document");
                }

                log.info("{} {} kandidat menu PDF: {}", MK_LOG_PREFIX, req.getNoRefCounter(), pdfItem.count());

                pdfItem.first().click(new Locator.ClickOptions().setTimeout(20_000));
            });

            if (download == null) {
                log.error("{} {} gagal mendapatkan objek Download untuk laporan kosong, fallback ke proses lama", MK_LOG_PREFIX, req.getNoRefCounter());
                return;
            }

            downloadProcessingService.processSingleDownloadedFile(req, download, false);
            exported = true;
            log.info("{} {} berhasil trigger download laporan kosong: {}", MK_LOG_PREFIX, req.getNoRefCounter(), download.suggestedFilename());
        } catch (Exception e) {
            log.error("{} {} gagal proses cetak & export saat data tidak ditemukan: {}", MK_LOG_PREFIX, req.getNoRefCounter(), e.getMessage(), e);
        } finally {
            updateFailStatus(req, pp, sr);
            backToKombinasiForm(driver, req);
            log.info("{} {} NOT_FOUND selesai (exported={})", MK_LOG_PREFIX, req.getNoRefCounter(), exported);
        }
    }

    private Frame waitForReportViewerFrame(Page driver, String noRefCounter) {
        for (int i = 0; i < 30; i++) {
            List<Frame> frames = driver.frames();
            log.info("{} {} cek frame viewer attempt={} totalFrame={}", MK_LOG_PREFIX, noRefCounter, i + 1, frames.size());

            for (Frame f : frames) {
                Frame candidate = findReportViewerFrameCandidate(f, noRefCounter);
                if (candidate != null) return candidate;
            }

            safeSleep(1_000L, MK_LOG_PREFIX + noRefCounter + " menunggu frame ReportViewer");
        }

        log.error("{} {} frame ReportViewer tidak ditemukan", MK_LOG_PREFIX, noRefCounter);
        return null;
    }

    private Frame findReportViewerFrameCandidate(Frame frame, String noRefCounter) {
        String name = null;
        String url = null;

        try {
            name = frame.name();
        } catch (Exception ex) {
            log.warn("{} {} gagal ambil name frame: {}", MK_LOG_PREFIX, noRefCounter, ex.toString());
        }

        try {
            url = frame.url();
        } catch (Exception ex) {
            log.warn("{} {} gagal ambil url frame: {}", MK_LOG_PREFIX, noRefCounter, ex.toString());
        }

        try {
            // 1) Nama frame mengandung "tab-iframe"
            if (name != null && name.toLowerCase().contains("tab-iframe")) {
                log.info("{} {} frame ReportViewer ditemukan by name={} url={}", MK_LOG_PREFIX, noRefCounter, name, url);
                return frame;
            }

            // 2) Di dalamnya ada body#reportViewer
            Locator bodyViewer = frame.locator("body#reportViewer");
            if (bodyViewer != null && bodyViewer.count() > 0) {
                log.info("{} {} frame ReportViewer ditemukan by body#reportViewer name={} url={}", MK_LOG_PREFIX, noRefCounter, name, url);
                return frame;
            }

            // 3) Atau ada tombol Export
            Locator exportBtn = frame.locator("button#export");
            if (exportBtn != null && exportBtn.count() > 0) {
                log.info("{} {} frame ReportViewer ditemukan by button#export name={} url={}", MK_LOG_PREFIX, noRefCounter, name, url);
                return frame;
            }

        } catch (Exception ex) {
            log.warn("{} {} gagal inspect frame name={} url={}: {}", MK_LOG_PREFIX, noRefCounter, name, url, ex.toString());
        }

        return null;
    }

    private void updateFailStatus(AppRequest req, ProcessReportRequest pp, SubmitRequest sr) {
        try {
            log.info("{} {} set status to fail request with payload: {}", MK_LOG_PREFIX, req.getNoRefCounter(), pp);
            m2mService.processFail(pp).execute();
            m2mService.processCallback(sr).execute();
        } catch (IOException e) {
            log.error("processing >>> {} error set status to fail request with payload: {}", req.getNoRefCounter(), pp, e);
        }
    }

    private void backToKombinasiForm(Page driver, AppRequest req) {
        String menu = MK_TYPE_IND.equals(req.getAppRequestCustType()) ? "Individual" : "Badan Usaha";

        try {
            log.info("{} {} kembali ke form kombinasi (klik menu {})", MK_LOG_PREFIX, req.getNoRefCounter(), menu);

            if (selectMenu(driver, menu)) {
                driver.waitForLoadState();
            }

            Frame f = webDriverService.getFrame("main");
            if (f != null) {
                f.locator(MK_SEL_COMBINATION_DIV).waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            }
        } catch (Exception e) {
            log.warn("{} {} gagal balik ke form, refresh sebagai fallback", MK_LOG_PREFIX, req.getNoRefCounter(), e);
            webDriverService.refresh();
            safeSleep(5_000L, "after refresh");
        }
    }

    private void handleResultSelectionAndGenerate(AppRequest req, Frame frame, ProcessReportRequest pp, SubmitRequest sr) {
        if (!selectAllResults(req, frame)) {
            log.warn("{} tidak ada hasil yang bisa dipilih, skip generate idi", MK_LOG_PREFIX);
            return;
        }

        if (!clickGenerateIdiButton(frame)) {
            if (commonProcessingService.checkDialogContainText(frame, MK_MSG_CAPTCHA_NOT_MATCH)) {
                log.warn("{} generate idi gagal karena captcha salah", MK_LOG_PREFIX);
            } else if (commonProcessingService.checkDialogContainText(frame, "tidak ada data")) {
                log.warn("{} generate idi gagal karena data tidak ditemukan", MK_LOG_PREFIX);
            } else {
                log.error("{} generate idi gagal: tombol tidak pernah enabled tanpa popup yang jelas", MK_LOG_PREFIX);
            }
            return;
        }

        handleProgressModalIfVisible(frame, "manualKombinasi >>> setelah klik generate idi");

        clickOkPopupIfAny(frame, req);

        if (isScreenshotStatusNoneAfterGenerate(req)) {
            processOjkSaveConfirmation(req, frame, pp, sr);
        } else {
            handleWrongActionAfterGenerate(req);
        }
    }

    private void handleProgressModalIfVisible(Frame frame, String context) {
        if (commonProcessingService.isProgressModalVisible(frame)) {
            log.error("{} >>> progress modal masih tampil, akan dihilangkan secara aplikasi", context);
            commonProcessingService.removeProgressModal(frame);
        }
    }

    private void clickOkPopupIfAny(Frame frame, AppRequest app) {
        String[] selectors = new String[]{
                "button.btn.btn-primary:has-text('OK')",
                "button.btn.btn-primary:has-text('Ok')",
                "button.btn.btn-primary:has-text('Oke')",
                "button:has-text('OK')",
                "button:has-text('Ok')",
                "button:has-text('Oke')",
                "button:has-text('Ya')",
                "button:has-text('Yes')",
                "button.swal2-confirm",
                ".swal2-popup button.swal2-confirm",
                ".modal.show .modal-footer button:has-text('OK')",
                ".modal.show .modal-footer button:has-text('Ok')",
                "input.btn.btn-primary[value='OK']",
                "input.btn.btn-primary[value='Ok']"
        };

        try {
            // jangan tunggu lama kalau popup nggak ada
            for (String sel : selectors) {
                Locator btn = frame.locator(sel).first();
                if (btn.count() == 0) continue;

                btn.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(600));

                btn.click(new Locator.ClickOptions().setTimeout(2000));
                frame.waitForTimeout(200);
                log.info("{} {} clickOkPopupIfAny >>> clicked selector={}", MK_LOG_PREFIX, app.getNoRefCounter(), sel);
                return;
            }

            log.debug("{} {} clickOkPopupIfAny >>> no OK button visible", MK_LOG_PREFIX, app.getNoRefCounter());

        } catch (PlaywrightException e) {
            log.debug("{} {} clickOkPopupIfAny >>> ignore: {}", MK_LOG_PREFIX, app.getNoRefCounter(), e.getMessage());
        }
    }

    private boolean selectAllResults(AppRequest req, Frame frame) {
        try {
            log.info("{} {} mulai pilih baris hasil (Jaro + semua halaman)", MK_LOG_PREFIX, req.getNoRefCounter());
            clickAll(frame, req);
            return true;
        } catch (Exception e) {
            log.error("{} {} gagal proses pilih hasil pencarian : {}", MK_LOG_PREFIX, req.getNoRefCounter(), e.getMessage());
            return false;
        }
    }

    private boolean clickGenerateIdiButton(Frame frame) {
        log.info("{} clickGenerateIdiButton >>> start", MK_LOG_PREFIX);
        try {
            Locator gen = frame.locator("#generate-idi-button");
            gen.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(10000));

            // 2. Tunggu sampai tombolnya TIDAK disabled dan visible
            frame.waitForSelector("#generate-idi-button:not([disabled])",
                    new Frame.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(15000)
            );

            // 3. Klik dengan timeout yang sedikit lebih longgar
            gen.click(new Locator.ClickOptions().setTimeout(5000));

            log.info("{} clickGenerateIdiButton >>> clicked", MK_LOG_PREFIX);
            return true;

        } catch (TimeoutError e) {
            log.error("{} clickGenerateIdiButton >>> timeout, button never enabled/visible", MK_LOG_PREFIX, e);
            return false;
        } catch (Exception e) {
            log.error("{} clickGenerateIdiButton >>> unexpected error klik generate idi : {}", MK_LOG_PREFIX, e.getMessage(), e);
            return false;
        }
    }

    private boolean isScreenshotStatusNoneAfterGenerate(AppRequest req) {
        byte[] sc2 = commonProcessingService.screenshot();
        String stat2 = commonProcessingService.checkScreenshot(sc2);
        log.info("manualKombinasi >>> {} screenshot status after generate: {}", req.getNoRefCounter(), stat2);
        return "NONE".equals(stat2);
    }

    private void processOjkSaveConfirmation(AppRequest req, Frame frame, ProcessReportRequest pp, SubmitRequest sr) {
        try {
            log.info("processing >>> {} waiting for save confirmation from OJK", req.getNoRefCounter());

            Locator progressDiv = frame.locator("#generate-idi-on-progress");
            progressDiv.waitFor(new Locator.WaitForOptions().setTimeout(15_000));

            if (progressDiv.isVisible() && progressDiv.isEnabled()) {
                String headerText = progressDiv.locator("h3").innerText();
                log.info("processing >>> {} got header text: {}", req.getNoRefCounter(), headerText);

                if (headerText != null && headerText.contains(MK_MSG_WAIT_SUPERVISOR)) {
                    updateStatusDoneAndCallback(req, pp, sr);
                } else {
                    log.error("processing >>> {} result confirmation not found", req.getNoRefCounter());
                }
            } else {
                log.error("processing >>> {} result confirmation not displayed", req.getNoRefCounter());
            }
        } catch (Exception e) {
            log.error("processLogin >>> Fail to check response from OJK slik", e);
        }
    }

    private void updateStatusDoneAndCallback(AppRequest req, ProcessReportRequest pp, SubmitRequest sr) throws IOException {
        log.info("processing >>> {} set status done with payload: {}", req.getNoRefCounter(), pp);
        m2mService.processDone(pp).execute();

        log.info("processing >>> {} send callback with payload: {}", req.getNoRefCounter(), sr);
        m2mService.processCallback(sr).execute();
    }

    private void handleWrongActionAfterGenerate(AppRequest req) {
        log.info("ProcessSlikManualKombinasi >>> processing {} wrong action", req.getNoRefCounter());
        webDriverService.refresh();

        safeSleep(5_000L, "processLogin >>> sleep setelah refresh");

        byte[] ss = this.commonProcessingService.screenshot();
        String stat2 = commonProcessingService.checkScreenshot(ss);
        if (!"NONE".equals(stat2)) {
            engineService.setCurrentStatusEngine(ENGINE_STATUS_CRASH);
        }
    }

    private boolean isOutOfOperationalTime(Frame frame, AppRequest req) {
        try {
            Locator exipred = frame.locator("#result_info");
            String txt = exipred.textContent();
            if (txt != null && txt.contains(MK_MSG_OUT_OF_OPERATION)) {
                log.error("{} {} di luar jam operasional slik", MK_LOG_PREFIX, req.getNoRefCounter());
                return true;
            }
        } catch (Exception e) {
            log.debug("{} {} tidak menemukan pesan jam operasional", MK_LOG_PREFIX, req.getNoRefCounter());
        }
        return false;
    }

    private void fillIndividualForm(AppRequest req, Frame frame) {
        final Frame currentFrame = frame;

        // 1) Tujuan Permintaan
        log.info("{} {} to entry kode tujuan (IND)", MK_LOG_PREFIX, req.getNoRefCounter());
        Locator tujuan = currentFrame.locator("#REPORT_REQUEST_PURPOSE_CODE");
        ensureSelectOptionFilled(tujuan, req.getAppRequestPurpose(), "Tujuan Permintaan (IND)", req.getNoRefCounter());

        // 2) Kode Ref
        log.info("{} {} to entry kode ref (IND)", MK_LOG_PREFIX, req.getNoRefCounter());
        Supplier<Locator> kodeRefSupplier = () -> currentFrame.locator("input[data-validation-label^='Kode']");
        ensureInputFilledWithRetry(kodeRefSupplier, req.getNoRefCounter(), "Kode Referensi (IND)", req.getNoRefCounter(), true);

        // 3) Nama Debitur
        log.info("{} {} to entry nama debitur (IND)", MK_LOG_PREFIX, req.getNoRefCounter());
        Supplier<Locator> fullnameSupplier = () -> currentFrame.locator("#FULLNAME");
        ensureInputFilledWithRetry(fullnameSupplier, req.getAppRequestCustName(), "Nama Debitur (IND)", req.getNoRefCounter(), true);

        // 4) Mode Pencocokan
        selectMatchMode(currentFrame, req);

        // 5) Tempat Lahir (opsional)
        if (req.getAppRequestPob() != null) {
            log.info("{} {} to entry tempat lahir", MK_LOG_PREFIX, req.getNoRefCounter());
            Supplier<Locator> pobSupplier = () -> currentFrame.locator("#BIRTH_PLACE");
            ensureInputFilledWithRetry(pobSupplier, req.getAppRequestPob(), "Tempat Lahir", req.getNoRefCounter(), false);
        }

        // 6) Tanggal Lahir (opsional)
        if (req.getAppRequestDob() != null) {
            log.info("{} {} to entry tanggal lahir", MK_LOG_PREFIX, req.getNoRefCounter());
            var dob = req.getAppRequestDob();
            var fmt = DateTimeFormatter.ofPattern(FORMAT_DATE);
            var sdob = dob.format(fmt);

            Supplier<Locator> dobSupplier = () -> currentFrame.locator("#BIRTH_DATE");
            ensureInputFilledWithRetry(dobSupplier, sdob, "Tanggal Lahir", req.getNoRefCounter(), false);
        }

        // 7) Jenis Kelamin (opsional)
        if (req.getAppRequestGender() != null) {
            log.info("{} {} to entry jenis kelamin {}", MK_LOG_PREFIX,
                    req.getNoRefCounter(), req.getAppRequestGender());
            Locator genderSelect = currentFrame.locator("#GENDER_CODE");
            ensureSelectOptionFilled(genderSelect, req.getAppRequestGender(), "Jenis Kelamin", req.getNoRefCounter());
        }
    }

    private void fillCompanyForm(AppRequest req, Frame frame) {
        final Frame currentFrame = frame;

        // 1) Tujuan Permintaan
        log.info("{} {} to entry kode tujuan (COM)", MK_LOG_PREFIX, req.getNoRefCounter());
        Locator tujuan = currentFrame.locator("#REPORT_REQUEST_PURPOSE_CODE");
        ensureSelectOptionFilled(tujuan, req.getAppRequestPurpose(), "Tujuan Permintaan (COM)", req.getNoRefCounter());

        // 2) Kode Ref
        log.info("{} {} to entry kode ref (COM)", MK_LOG_PREFIX, req.getNoRefCounter());
        Supplier<Locator> kodeRefSupplier = () -> currentFrame.locator("input[data-validation-label^='Kode']");
        ensureInputFilledWithRetry(kodeRefSupplier, req.getNoRefCounter(), "Kode Referensi (COM)", req.getNoRefCounter(), true);

        // 3) Nama Badan Usaha
        log.info("{} {} to entry nama badan usaha (COM)", MK_LOG_PREFIX, req.getNoRefCounter());
        Supplier<Locator> fullnameSupplier = () -> currentFrame.locator("#FULLNAME");
        ensureInputFilledWithRetry(fullnameSupplier, req.getAppRequestCustName(), "Nama Badan Usaha", req.getNoRefCounter(), true);

        // 4) Tempat Pendirian (opsional)
        if (req.getAppRequestPob() != null) {
            log.info("{} {} to entry tempat pendirian", MK_LOG_PREFIX, req.getNoRefCounter());
            Supplier<Locator> pobSupplier = () -> currentFrame.locator("#EST_PLACE");
            ensureInputFilledWithRetry(pobSupplier, req.getAppRequestPob(), "Tempat Pendirian", req.getNoRefCounter(), false);
        }

        // 5) Tanggal Pendirian (opsional)
        if (req.getAppRequestDob() != null) {
            log.info("{} {} to entry tanggal pendirian", MK_LOG_PREFIX, req.getNoRefCounter());
            var dob = req.getAppRequestDob();
            var fmt = DateTimeFormatter.ofPattern(FORMAT_DATE);
            var sdob = dob.format(fmt);

            Supplier<Locator> estDateSupplier = () -> currentFrame.locator("#EST_CERT_DATE");
            ensureInputFilledWithRetry(estDateSupplier, sdob, "Tanggal Pendirian", req.getNoRefCounter(), false);
        }
    }

    private void selectMatchMode(Frame frame, AppRequest req) {
        if (!"IND".equalsIgnoreCase(req.getAppRequestCustType())) {
            return;
        }

        // 2) kalau radio memang tidak ada, skip
        Locator exact = frame.locator(MK_SEL_MATCH_EXACT);
        Locator sim = frame.locator(MK_SEL_MATCH_SIMILARITY);
        if (exact.count() == 0 || sim.count() == 0) {
            return;
        }

        // 3) default: Kesamaan kalau tidak dikirim
        String raw = req.getAppRequestMatchMode();
        String mode = raw == null ? "" : raw.trim().toUpperCase();

        boolean similarity = mode.equals(SIMILARITY) || mode.equals(KEMIRIPAN);
        String labelSel = similarity ? "label[for='similarity-matching-radio']" : "label[for='exact-matching-radio']";

        Locator label = frame.locator(labelSel);
        if (label.count() > 0) {
            label.first().click(new Locator.ClickOptions().setTimeout(3_000));
        }
    }

    private Frame openKombinasiLandingPage(Page driver, Frame frame) {
        log.info("{} klik menu permintaan data", MK_LOG_PREFIX);

        Locator menuPermintaanData =
                frame.locator("html > body > div > div:nth-of-type(1) > div > ul > li:nth-of-type(3) > a");
        if (menuPermintaanData.count() == 0) {
            log.error("{} menu permintaan tidak ditemukan", MK_LOG_PREFIX);
            return null;
        }

        menuPermintaanData.click(new Locator.ClickOptions().setTimeout(5000));
        driver.waitForLoadState();

        Frame newFrame = webDriverService.getFrame("main");
        if (newFrame == null) {
            log.error("{} frame null setelah klik menu permintaan", MK_LOG_PREFIX);
            return null;
        }

        try {
            log.info("{} cek label user reference code", MK_LOG_PREFIX);
            Locator waitUserRefCode = newFrame.locator(MK_SEL_USER_REF_LABEL);
            waitUserRefCode.waitFor(new Locator.WaitForOptions().setTimeout(45_000));
        } catch (Exception e) {
            log.error("{} gagal cek user reference", MK_LOG_PREFIX, e);
            return null;
        }

        return newFrame;
    }

    private Frame prepareFrameForType(AppRequest req, Page driver, String lastCustType) {
        String reqType = req.getAppRequestCustType();
        if (reqType == null) {
            log.error("{} {} tipe nasabah null", MK_LOG_PREFIX, req.getNoRefCounter());
            return null;
        }

        Frame frame = getCurrentFrameOrLog();
        if (frame == null) {
            return null;
        }

        frame = switchMenuIfNeeded(req, driver, lastCustType, reqType, frame);
        if (frame == null) {
            return null;
        }

        frame = findCombinationFrameForRequest(req, driver, frame, reqType);
        if (frame == null) {
            return null;
        }

        Locator combine = frame.locator(MK_SEL_COMBINATION_DIV);
        if (!waitCombinationVisibleOrLog(req, reqType, combine)) {
            return null;
        }

        safeBringCombinationPanelIntoView(frame, req, reqType);

        return frame;
    }

    private void safeBringCombinationPanelIntoView(Frame frame, AppRequest req, String reqType) {
        final String noRef = req.getNoRefCounter();

        for (int attempt = 1; attempt <= MK_CTX_RETRY; attempt++) {
            Locator combine = frame.locator(MK_SEL_COMBINATION_DIV);
            safeWaitVisible(combine, noRef);

            try {
                safeClickCombinationDiv(frame, combine, req, reqType);
                return;

            } catch (PlaywrightException ex) {
                if (isNotAttachedError(ex) || isCtxDestroyed(ex)) {
                    log.warn("{} {} panel kombinasi detached/context destroyed (attempt {}/{}), retry...",
                            MK_LOG_PREFIX, noRef, attempt, MK_CTX_RETRY);
                    safeSleep(200, MK_LOG_PREFIX + noRef + " retry bring combination panel");
                    continue;
                }
                throw ex;

            } catch (RuntimeException ex) {
                log.warn("{} {} gagal bring panel kombinasi (attempt {}/{}): {}",
                        MK_LOG_PREFIX, noRef, attempt, MK_CTX_RETRY, ex.getMessage());
                safeSleep(200, MK_LOG_PREFIX + noRef + " retry bring combination panel");
            }
        }

        bestEffortClickCombinationPanel(frame, req);
    }

    private void safeWaitVisible(Locator loc, String noRef) {
        try {
            loc.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(MK_COMBINE_VISIBLE_TIMEOUT_MS));
        } catch (PlaywrightException e) {
            if (log.isDebugEnabled()) {
                log.debug("{} {} wait visible skip/timeout: {}", MK_LOG_PREFIX, noRef, e.getMessage());
            }
        }
    }

    private void bestEffortClickCombinationPanel(Frame frame, AppRequest req) {
        final String noRef = req.getNoRefCounter();
        try {
            frame.evaluate("sel => { const el = document.querySelector(sel); if (el) el.click(); }",
                    MK_SEL_COMBINATION_DIV);
        } catch (Exception e) {
            log.warn("{} {} fallback click panel kombinasi gagal: {}", MK_LOG_PREFIX, noRef, e.getMessage());
        }
    }

    private Frame getCurrentFrameOrLog() {
        Frame frame = webDriverService.getFrame("main");
        if (frame == null) {
            log.error("{} frame is null (awal iterasi)", MK_LOG_PREFIX);
        }
        return frame;
    }

    private Frame switchMenuIfNeeded(AppRequest req, Page driver, String lastCustType, String reqType, Frame frame) {
        boolean needIndividual = MK_TYPE_IND.equals(reqType) && MK_TYPE_COM.equals(lastCustType);
        boolean needCompany = MK_TYPE_COM.equals(reqType) && (MK_TYPE_IND.equals(lastCustType) || "".equals(lastCustType));

        if (!needIndividual && !needCompany) {
            return frame;
        }

        String menuName = needIndividual ? "Individual" : "Badan Usaha";

        if (this.selectMenu(driver, menuName)) {
            driver.waitForLoadState();
            Frame newFrame = webDriverService.getFrame("main");
            if (newFrame == null) {
                log.error("{} frame is null setelah select menu {}", MK_LOG_PREFIX, menuName);
            }
            return newFrame;
        }

        log.error("{} {} error select menu {}", MK_LOG_PREFIX, req.getNoRefCounter(), menuName);
        return null;
    }

    private Frame findCombinationFrameForRequest(AppRequest req, Page driver, Frame frame, String reqType) {
        try {
            frame.waitForSelector(".loading-overlay", new Frame.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.DETACHED)
                    .setTimeout(1500)
            );
        } catch (Exception e) {
            log.debug("{} overlay tidak ditemukan ({}), lanjut", MK_LOG_PREFIX, reqType);
        }

        Frame comboFrame = findCombinationFrame(driver, frame, req.getNoRefCounter());
        if (comboFrame == null) {
            log.warn("{} {} panel kombinasi tidak ditemukan ({}), skip request ini", MK_LOG_PREFIX, req.getNoRefCounter(), reqType);
        }
        return comboFrame;
    }

    private boolean waitCombinationVisibleOrLog(AppRequest req, String reqType, Locator combine) {
        try {
            combine.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(2000));
            return true;
        } catch (TimeoutError te) {
            log.warn("{} {} panel kombinasi tidak muncul dalam 2000ms ({}), skip. Msg: {}", MK_LOG_PREFIX, req.getNoRefCounter(), reqType, te.getMessage());
            return false;
        }
    }

    private void safeClickCombinationDiv(Frame frame, Locator combine, AppRequest req, String reqType) {
        try {
            combine.click(new Locator.ClickOptions().setTimeout(2000));
        } catch (Exception e1) {
            frame.evaluate("() => { const el = document.querySelector('" + MK_SEL_COMBINATION_DIV + "'); if (el) el.click(); }");
            combine.click(new Locator.ClickOptions().setTimeout(2000).setForce(true));
            log.debug("{} {} force click panel kombinasi ({})",
                    MK_LOG_PREFIX, req.getNoRefCounter(), reqType);
        }
    }

    private boolean startM2mRequest(AppRequest req, ProcessReportRequest pp) {
        try {
            log.info("{} {} set status to start request with payload: {}",
                    MK_LOG_PREFIX, req.getNoRefCounter(), pp);
            m2mService.processStart(pp).execute();
            return true;
        } catch (IOException e) {
            log.error("{} {} error set status to start request dengan payload: {}",
                    MK_LOG_PREFIX, req.getNoRefCounter(), pp, e);
            return false;
        }
    }

    private void safeSleep(long millis, String context) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} interrupted during sleep: {}", context, e.getMessage());
        }
    }

    private void clickAll(Frame frame, AppRequest app) {
        int currentPage = 1;

        while (true) {
            waitListViewReady(frame, app);

            // baca + centang hasil pada page ini (sudah retry internal)
            readTable(frame, app);

            Locator dhxPages = frame.locator("#listview1 .dhx_page");
            int pageCount = safeCount(dhxPages, frame, app, "count pages");

            if (pageCount <= 0) return;

            boolean hasNext = moveToNextPageIfAny(frame, dhxPages, currentPage, app);
            if (!hasNext) return;

            currentPage++;
        }
    }

    private boolean moveToNextPageIfAny(Frame frame, Locator dhxPages, int currentPage, AppRequest app) {
        int pageCount = safeCount(dhxPages, frame, app, "count pages");
        if (currentPage >= pageCount) return false;

        for (int i = 0; i < pageCount; i++) {
            Locator inDiv = dhxPages.nth(i).locator("div").first();

            String txt;
            try {
                txt = inDiv.textContent();
            } catch (Exception ignore) {
                continue;
            }

            int cp = parsePageNumber(txt);
            if (cp > currentPage) {
                try {
                    inDiv.click(new Locator.ClickOptions().setTimeout(5_000));
                    waitAfterPossibleNavigation(frame, app);
                    return true;
                } catch (PlaywrightException e) {
                    if (isCtxDestroyed(e)) {
                        waitAfterPossibleNavigation(frame, app);
                        return true;
                    }
                    log.error("clickAll >>> error when switching to page {}", cp, e);
                    return false;
                }
            }
        }
        return false;
    }

    private int parsePageNumber(String txt) {
        if (txt == null) {
            return -1;
        }
        try {
            return Integer.parseInt(txt.trim());
        } catch (NumberFormatException e) {
            log.debug("clickAll >>> Could not parse page number: {}", txt);
            return -1;
        }
    }

    private void readTable(Frame frame, AppRequest app) {
        for (int attempt = 1; attempt <= MK_CTX_RETRY; attempt++) {
            try {
                readTableOnce(frame, app);
                return;
            } catch (PlaywrightException e) {
                if (isCtxDestroyed(e)) {
                    log.warn("{} {} readTable context destroyed (try {}/{}), wait...", MK_LOG_PREFIX, app.getNoRefCounter(), attempt, MK_CTX_RETRY);
                    waitAfterPossibleNavigation(frame, app);
                    continue;
                }
                throw e;
            } catch (Exception e) {
                log.error("readTable >>> error", e);
                return;
            }
        }
    }

    private static final String MK_SEL_RCB_BLOCK_LAYER = "#rcbBlockLayer_slik-ui";

    private void readTableOnce(Frame frame, AppRequest app) {
        waitListViewReady(frame, app);

        Locator rowDiv = frame.locator("#listview1 > div > div");
        int rows = safeCount(rowDiv, frame, app, "count rows");
        if (rows <= 0) {
            return;
        }

        boolean selected = false;

        for (int x = 0; x < rows && !selected; x++) {
            Locator el = rowDiv.nth(x);

            Locator tbls = el.locator("table");
            int tcount = safeCount(tbls, frame, app, "count tables row " + x);

            Locator cb = el.locator("input[type='checkbox']").first();
            int cbCount = safeCount(cb, frame, app, "count checkbox row " + x);

            boolean matched = false;
            if (tcount > 1 && cbCount > 0) {
                try {
                    matched = this.doJaro(tbls.nth(0), tbls.nth(1), app);
                } catch (Exception e) {
                    log.warn("{} {} readTableOnce >>> doJaro error row {}: {}", MK_LOG_PREFIX, app.getNoRefCounter(), x, e.getMessage());
                }
            }

            if (tcount > 1 && cbCount > 0 && matched) {
                clickCheckboxSafe(frame, cb, app, "row " + x);
                frame.waitForTimeout(150);
                selected = true;
            }
        }
    }

    private void clickCheckboxSafe(Frame frame, Locator cb, AppRequest app, String ctx) {
        waitRcbBlockLayerGone(frame, app, "before scroll checkbox " + ctx);
        cb.scrollIntoViewIfNeeded();
        waitRcbBlockLayerGone(frame, app, "after scroll checkbox " + ctx);

        boolean clicked = false;

        // retry click max 2x tanpa continue/break
        for (int attempt = 1; attempt <= 2 && !clicked; attempt++) {
            try {
                cb.click(new Locator.ClickOptions().setTimeout(8_000));
                clicked = true;
            } catch (TimeoutError te) {
                if (isBlockedByRcbLayer(te)) {
                    log.warn("{} {} checkbox {} ke-block overlay (attempt={}), tunggu blocker lalu retry", MK_LOG_PREFIX, app.getNoRefCounter(), ctx, attempt);
                    waitRcbBlockLayerGone(frame, app, "retry click checkbox " + ctx);
                } else {
                    throw te;
                }
            }
        }

        if (!clicked) {
            log.warn("{} {} checkbox {} masih ke-block, fallback JS-check", MK_LOG_PREFIX, app.getNoRefCounter(), ctx);
            jsCheck(cb);
            frame.waitForTimeout(100);
            return;
        }

        ensureCheckedOrJs(cb, app, ctx);
    }

    private void ensureCheckedOrJs(Locator cb, AppRequest app, String ctx) {
        try {
            Object v = cb.evaluate("el => (el && el.checked) === true");
            boolean checked = Boolean.TRUE.equals(v);

            if (!checked) {
                log.warn("{} {} checkbox {} sudah diklik tapi belum checked, fallback JS-check", MK_LOG_PREFIX, app.getNoRefCounter(), ctx);
                jsCheck(cb);
            }
        } catch (Exception e) {
            log.debug("{} {} ensureCheckedOrJs {} >>> evaluate failed: {}", MK_LOG_PREFIX, app.getNoRefCounter(), ctx, e.getMessage());
            jsCheck(cb);
        }
    }

    private void jsCheck(Locator cb) {
        cb.evaluate("""
                    el => {
                      el.checked = true;
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                """);
    }

    private void waitRcbBlockLayerGone(Frame frame, AppRequest app, String ctx) {
        try {
            frame.waitForSelector(MK_SEL_RCB_BLOCK_LAYER,
                    new Frame.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.HIDDEN)
                            .setTimeout(8_000));
        } catch (TimeoutError te) {
            log.warn("{} {} {} >>> blocker masih tampil: {}", MK_LOG_PREFIX, app.getNoRefCounter(), ctx, te.getMessage());
        } catch (Exception e) {
            log.debug("{} {} {} >>> ignore wait blocker: {}", MK_LOG_PREFIX, app.getNoRefCounter(), ctx, e.getMessage());
        }
    }

    private boolean isBlockedByRcbLayer(Throwable e) {
        String m = e.getMessage();
        return m != null && (m.contains("rcbBlockLayer_slik-ui") || m.contains("intercepts pointer events"));
    }

    private boolean doJaro(Locator tblData, Locator tblAlamat, AppRequest app) {
        if (app.getAppRequestCustType().equals("IND")) {
            return this.doJaroIndividual(tblData, tblAlamat, app);
        } else {
            return this.doJaroCompany(tblData, tblAlamat, app);
        }
    }

    private boolean doJaroIndividual(Locator tblData, Locator tblAlamat, AppRequest app) {
        String nik = "";
        String nama = "";
        String mother = "";
        String pob = "";
        String dob = "";
        String addr = "";

        // ===== 1. Baca data dari tabel hasil SLIK =====
        try {
            var tbody = tblData.locator("tbody");
            if (tbody.count() == 0) return false;

            var trData = tbody.locator("tr");
            if (trData.count() > 0) {
                var trd = trData.first();
                var tds = trd.locator("td");
                if (tds.count() > 7) {
                    nik = safeText(tds.nth(1).textContent());
                    nama = safeText(tds.nth(2).textContent());
                    mother = safeText(tds.nth(3).textContent());
                    pob = safeText(tds.nth(5).textContent());
                    dob = safeText(tds.nth(6).textContent());
                }
            }
        } catch (Exception e) {
            log.error("doJaroIndividual >>> error reading data table", e);
            return false;
        }

        // alamat
        try {
            var trAddr = tblAlamat.locator("tr");
            if (trAddr.count() > 0) {
                var trd = trAddr.first();
                var tds = trd.locator("td");
                if (tds.count() > 0) {
                    addr = safeText(tds.nth(0).textContent());
                }
            }
        } catch (Exception e) {
            log.error("doJaroIndividual >>> error reading address table", e);
        }

        // ===== 2. Ambil konfigurasi dari BE =====
        ScoringConfig cfg = getScoringConfig();
        if (cfg == null) {
            log.error("doJaroIndividual >>> scoringConfig NULL (gagal load dari BE), anggap tidak match");
            return false;
        }

        SimilarityUtil sim = new SimilarityUtil();
        int scr = 0;

        // DOB dari AppRequest ke string (format sama dengan SLIK)
        String appDobStr = "";
        if (app.getAppRequestDob() != null) {
            var fmt = DateTimeFormatter.ofPattern(FORMAT_DATE);
            appDobStr = app.getAppRequestDob().format(fmt);
        }

        // ===== 3. Hitung skor per field berdasarkan config BE =====
        scr += computeScoreForField(cfg.getItem("NIK"), nik, app.getAppRequestKtp(), sim, true, "NIK");
        scr += computeScoreForField(cfg.getItem("NAMA"), nama, app.getAppRequestCustName(), sim, false, "NAMA");
        scr += computeScoreForField(cfg.getItem("MDN"), mother, app.getAppRequestMotherName(), sim, false, "MDN");
        scr += computeScoreForField(cfg.getItem("DOB"), dob, appDobStr, sim, true, "DOB");
        scr += computeScoreForField(cfg.getItem("POB"), pob, app.getAppRequestPob(), sim, false, "POB");
        scr += computeScoreForField(cfg.getItem("ADDR"), addr, app.getAppRequestHomeAddress(), sim, false, "ADDR");

        log.info("doJaroIndividual >>> app={} totalScore={} (minNeed={})", app.getNoRefCounter(), scr, cfg.getGlobalMinScore());

        return scr >= cfg.getGlobalMinScore();
    }

    private boolean doJaroCompany(Locator tblData, Locator tblAlamat, AppRequest app) {
        String npwp = "";
        String nama = "";
        String pob = "";
        String dob = "";
        String addr = "";

        // ===== 1. Baca data dari tabel hasil SLIK =====
        try {
            var tbody = tblData.locator("tbody");
            if (tbody.count() == 0) return false;

            var trData = tbody.locator("tr");
            if (trData.count() > 0) {
                var trd = trData.first();
                var tds = trd.locator("td");
                if (tds.count() > 4) {
                    npwp = safeText(tds.nth(1).textContent());
                    nama = safeText(tds.nth(2).textContent());
                    pob = safeText(tds.nth(3).textContent());
                    dob = safeText(tds.nth(4).textContent());
                }
            }
        } catch (Exception e) {
            log.error("doJaroCompany >>> error reading data table", e);
            return false;
        }

        // alamat
        try {
            var trAddr = tblAlamat.locator("tr");
            if (trAddr.count() > 0) {
                var trd = trAddr.first();
                var tds = trd.locator("td");
                if (tds.count() > 0) {
                    addr = safeText(tds.nth(0).textContent());
                }
            }
        } catch (Exception e) {
            log.error("doJaroCompany >>> error reading address table", e);
        }

        // ===== 2. Ambil konfigurasi dari BE =====
        ScoringConfig cfg = getScoringConfig();
        if (cfg == null) {
            log.error("doJaroCompany >>> scoringConfig NULL (gagal load dari BE), anggap tidak match");
            return false;
        }

        SimilarityUtil sim = new SimilarityUtil();
        int scr = 0;

        String appDobStr = "";
        if (app.getAppRequestDob() != null) {
            var fmt = DateTimeFormatter.ofPattern(FORMAT_DATE);
            appDobStr = app.getAppRequestDob().format(fmt);
        }

        // Di BE kamu tetap pakai code: NIK (maknai sebagai NPWP), NAMA, DOB, POB, ADDR
        scr += computeScoreForField(cfg.getItem("NIK"), npwp, app.getAppRequestNpwp(), sim, true, "NIK");
        scr += computeScoreForField(cfg.getItem("NAMA"), nama, app.getAppRequestCustName(), sim, false, "NAMA");
        scr += computeScoreForField(cfg.getItem("DOB"), dob, appDobStr, sim, true, "DOB");
        scr += computeScoreForField(cfg.getItem("POB"), pob, app.getAppRequestPob(), sim, false, "POB");
        scr += computeScoreForField(cfg.getItem("ADDR"), addr, app.getAppRequestHomeAddress(), sim, false, "ADDR");

        log.info("doJaroCompany >>> app={} totalScore={} (minNeed={})",
                app.getNoRefCounter(), scr, cfg.getGlobalMinScore());

        return scr >= cfg.getGlobalMinScore();
    }

    private boolean selectMenu(Page driver, String menu) {
        Pattern menuPattern = buildMenuPattern(menu);

        for (int attempt = 1; attempt <= MK_MENU_RETRY; attempt++) {
            Frame main = webDriverService.getFrame("main");
            try {
                if (main != null) handleProgressModalIfVisible(main, "selectMenu >>> before click menu");
            } catch (Exception ignore) { /* ignore */ }

            if (tryClickMenuInFrame(main, menu, menuPattern)) return true;
            if (tryClickMenuInPage(driver, menu, menuPattern)) return true;
            if (tryClickMenuInAnyFrame(driver, menu, menuPattern)) return true;

            safeSleep(200, "selectMenu retry " + menu);
        }

        log.error("selectMenu >>> gagal pilih menu '{}' setelah {} attempt", menu, MK_MENU_RETRY);
        return false;
    }

    private static String escapeForPlaywrightRegex(String s) {
        if (s == null) return "";
        return s.replaceAll("([\\\\.^$|?*+()\\[\\]{}])", "\\\\$1");
    }

    private Pattern buildMenuPattern(String menu) {
        String[] parts = (menu == null ? "" : menu.trim()).split("\\s+");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("(?:[\\s\\u00A0])+");
            sb.append(escapeForPlaywrightRegex(parts[i]));
        }

        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private boolean tryClickMenuInFrame(Frame frame, String menu, Pattern menuPattern) {
        if (frame == null) return false;

        try {
            Locator leftMenu = frame.locator("#left-menu");
            if (leftMenu.count() == 0) return false;

            leftMenu.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(MK_MENU_TIMEOUT_MS));

            Locator link = leftMenu.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(menuPattern));
            if (link.count() == 0) {
                link = leftMenu.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(menuPattern));
            }
            if (link.count() == 0) {
                link = leftMenu.getByText(menuPattern);
            }
            if (link.count() == 0) return false;

            link.first().scrollIntoViewIfNeeded();
            link.first().click(new Locator.ClickOptions().setTimeout(MK_MENU_TIMEOUT_MS));
            log.info("selectMenu >>> menu '{}' berhasil diklik (frame)", menu);
            return true;

        } catch (PlaywrightException e) {
            log.warn("selectMenu >>> gagal klik menu '{}' di frame: {}", menu, e.getMessage());
            return false;
        }
    }

    private boolean tryClickMenuInPage(Page driver, String menu, Pattern menuPattern) {
        if (driver == null) return false;

        try {
            Locator leftMenu = driver.locator("#left-menu");
            if (leftMenu.count() == 0) return false;

            leftMenu.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(MK_MENU_TIMEOUT_MS));

            Locator link = leftMenu.getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(menuPattern));
            if (link.count() == 0) {
                link = leftMenu.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(menuPattern));
            }
            if (link.count() == 0) {
                link = leftMenu.getByText(menuPattern);
            }
            if (link.count() == 0) return false;

            link.first().click(new Locator.ClickOptions().setTimeout(MK_MENU_TIMEOUT_MS));
            log.info("selectMenu >>> menu '{}' berhasil diklik (page)", menu);
            return true;

        } catch (PlaywrightException e) {
            log.warn("selectMenu >>> gagal klik menu '{}' di page: {}", menu, e.getMessage());
            return false;
        }
    }

    private boolean tryClickMenuInAnyFrame(Page driver, String menu, Pattern menuPattern) {
        if (driver == null) return false;

        for (Frame f : driver.frames()) {
            if (tryClickMenuInFrame(f, menu, menuPattern)) return true;
        }
        return false;
    }

    private void ensureSelectOptionFilled(Locator select, String expectedValue, String fieldName, String noRef) {
        String expected = normalizeExpectedSelectValue(expectedValue, fieldName, noRef);
        if (expected == null) {
            return;
        }

        if (!waitForSelectVisible(select, fieldName, noRef)) {
            return;
        }

        enableSelect(select, fieldName, noRef);

        for (int attempt = 1; attempt <= 3; attempt++) {
            if (fillSelectOnce(select, expected, fieldName, noRef, attempt)) {
                return;
            }
        }

        log.error("manualKombinasi >>> {} {} tidak bisa dipastikan terisi setelah 3 attempt", noRef, fieldName);
    }

    private String normalizeExpectedSelectValue(String expectedValue, String fieldName, String noRef) {
        String expected = expectedValue == null ? "" : expectedValue.trim();
        if (expected.isEmpty()) {
            log.warn("manualKombinasi >>> {} {} expected kosong, skip pengisian", noRef, fieldName);
            return null;
        }
        return expected;
    }

    private boolean waitForSelectVisible(Locator select, String fieldName, String noRef) {
        try {
            select.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
            return true;
        } catch (Exception e) {
            log.error("manualKombinasi >>> {} {} tidak muncul / tidak visible", noRef, fieldName, e);
            return false;
        }
    }

    private void enableSelect(Locator select, String fieldName, String noRef) {
        try {
            select.evaluate("el => { el.removeAttribute('disabled'); el.removeAttribute('readonly'); }");
        } catch (Exception e) {
            log.debug("manualKombinasi >>> {} {} gagal meng-enable select (bisa diabaikan): {}", noRef, fieldName, e.getMessage());
        }
    }

    private boolean fillSelectOnce(Locator select, String expected, String fieldName, String noRef, int attempt) {
        try {
            select.selectOption(new SelectOption().setValue(expected));

            String actual = readSelectValue(select);

            if (expected.equals(actual)) {
                log.info("manualKombinasi >>> {} {} terisi benar (attempt {}), value={}", noRef, fieldName, attempt, actual);
                return true;
            }

            log.warn("manualKombinasi >>> {} {} belum sesuai (attempt {}). expected={}, actual={}", noRef, fieldName, attempt, expected, actual);
            return false;

        } catch (Exception e) {
            log.error("manualKombinasi >>> {} error saat mengisi {} (attempt {})", noRef, fieldName, attempt, e);
            return false;
        }
    }

    private String readSelectValue(Locator select) {
        try {
            String actual = select.inputValue();
            return actual == null ? "" : actual.trim();
        } catch (Exception e) {
            try {
                Object v = select.evaluate("el => el.value");
                return v == null ? "" : v.toString().trim();
            } catch (Exception ex) {
                log.debug("manualKombinasi >>> gagal membaca nilai select: {}", ex.getMessage());
                return "<error read>";
            }
        }
    }

    private void ensureInputFilledWithRetry(Supplier<Locator> locatorSupplier, String expectedValue, String fieldName, String noRef, boolean required) {
        String expected = normalizeExpectedValue(expectedValue, fieldName, noRef, required);
        if (expected == null) {
            return;
        }

        Locator input = locatorSupplier.get();
        if (!waitForInputVisible(input, fieldName, noRef)) {
            return;
        }

        enableAndFocusInput(input, fieldName, noRef);

        boolean success = false;
        for (int attempt = 1; attempt <= 3 && !success; attempt++) {
            success = fillAndVerifyInputOnce(locatorSupplier, expected, fieldName, noRef, attempt);
        }

        if (!success) {
            Locator lastInput = locatorSupplier.get();
            String finalVal = readInputValue(lastInput);
            log.error("manualKombinasi >>> {} {} gagal dipastikan terisi setelah 3 attempt. expected={}, final={}", noRef, fieldName, expected, finalVal);
        }
    }

    private String normalizeExpectedValue(String expectedValue, String fieldName, String noRef, boolean required) {
        String expected = expectedValue == null ? "" : expectedValue.trim();
        if (expected.isEmpty()) {
            if (required) {
                log.warn("manualKombinasi >>> {} {} expected kosong (required), field tidak diisi", noRef, fieldName);
            } else {
                log.info("manualKombinasi >>> {} {} expected kosong (optional), skip", noRef, fieldName);
            }
            return null;
        }
        return expected;
    }

    private boolean waitForInputVisible(Locator input, String fieldName, String noRef) {
        try {
            input.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
            return true;
        } catch (Exception e) {
            log.error("manualKombinasi >>> {} {} tidak muncul / tidak visible", noRef, fieldName, e);
            return false;
        }
    }

    private void enableAndFocusInput(Locator input, String fieldName, String noRef) {
        try {
            input.evaluate("el => { el.removeAttribute('disabled'); el.removeAttribute('readonly'); el.focus(); }");
        } catch (Exception e) {
            log.debug("manualKombinasi >>> {} {} gagal meng-enable input (bisa diabaikan): {}", noRef, fieldName, e.getMessage());
        }
    }

    private boolean fillAndVerifyInputOnce(Supplier<Locator> locatorSupplier, String expected, String fieldName, String noRef, int attempt) {
        Locator input = locatorSupplier.get();
        try {
            input.click(new Locator.ClickOptions().setTimeout(5_000));
            input.fill("");
            input.fill(expected);
            input.press("Tab");

            String current = readInputValue(input);

            if (expected.equals(current)) {
                log.info("manualKombinasi >>> {} {} terisi dengan benar (attempt {}), value={}", noRef, fieldName, attempt, current);
                return true;
            }

            log.warn("manualKombinasi >>> {} {} belum sesuai (attempt {}). expected={}, actual={}", noRef, fieldName, attempt, expected, current);
            return false;

        } catch (PlaywrightException ex) {
            if (isNotAttachedError(ex)) {
                log.warn("manualKombinasi >>> {} {} locator not attached, relokasi (attempt {})", noRef, fieldName, attempt);
                return false;
            }

            log.error("manualKombinasi >>> {} error Playwright saat mengisi {} (attempt {})", noRef, fieldName, attempt, ex);
            return false;
        } catch (Exception e) {
            log.error("manualKombinasi >>> {} error saat mengisi {} (attempt {})", noRef, fieldName, attempt, e);
            return false;
        }
    }

    private boolean isNotAttachedError(PlaywrightException ex) {
        String msg = ex.getMessage();
        return msg != null && msg.contains("not attached");
    }

    private String readInputValue(Locator input) {
        try {
            String current = input.inputValue();
            return current == null ? "" : current.trim();
        } catch (Exception e) {
            try {
                Object v = input.evaluate("el => el.value");
                return v == null ? "" : v.toString().trim();
            } catch (Exception ex) {
                log.debug("manualKombinasi >>> gagal membaca nilai input secara evaluate: {}",
                        ex.getMessage());
                return "<error read>";
            }
        }
    }

    private ScoringConfig getScoringConfig() {
        long now = System.currentTimeMillis();
        ScoringConfig cfg = scoringConfig;

        boolean needReload = (cfg == null) || (now - scoringConfigLoadedAt > SCORING_TTL_MILLIS);
        if (!needReload) return cfg;

        synchronized (scoringLock) {
            cfg = scoringConfig;
            now = System.currentTimeMillis();
            needReload = (cfg == null) || (now - scoringConfigLoadedAt > SCORING_TTL_MILLIS);
            if (!needReload) return cfg;

            log.info("ScoringConfig >>> refresh config dari backend");

            ScoringConfig newCfg = fetchScoringConfigFromBackend();
            if (newCfg != null && newCfg.getItemsByCode() != null && !newCfg.getItemsByCode().isEmpty()) {
                scoringConfig = newCfg;
                scoringConfigLoadedAt = now;
                log.info("ScoringConfig >>> updated, minScore={}, codes={}",
                        newCfg.getGlobalMinScore(), newCfg.getItemsByCode().keySet());
                return newCfg;
            } else {
                if (cfg != null) {
                    log.warn("ScoringConfig >>> gagal refresh, pakai config lama");
                    return cfg;
                } else {
                    log.error("ScoringConfig >>> tidak ada config sama sekali (BE gagal & no cache)");
                    return null;
                }
            }
        }
    }

    private ScoringConfig fetchScoringConfigFromBackend() {
        try {
            Call<ApiResponse<GetScoring>> call = scoringApi.getScoring();
            Response<ApiResponse<GetScoring>> resp = call.execute();

            if (!resp.isSuccessful()) {
                log.error("ScoringConfig >>> HTTP {} saat GET /api/scorring/detail", resp.code());
                return null;
            }

            ApiResponse<GetScoring> body = resp.body();
            if (!isValidScoringResponse(body)) {
                log.error("ScoringConfig >>> response invalid: {}", body != null ? body.getMessage() : "null body");
                return null;
            }

            GetScoring dto = body.getData().get(0);
            ScoringConfig cfg = new ScoringConfig();
            cfg.setGlobalMinScore(dto.getMinSkor() == null ? 0 : dto.getMinSkor());

            Map<String, ScoringItem> map = new HashMap<>();
            if (dto.getItem() != null) {
                for (ScoringItem it : dto.getItem()) {
                    if (it.getCode() != null) {
                        map.put(it.getCode().toUpperCase(), it);
                    }
                }
            }
            cfg.setItemsByCode(map);
            return cfg;

        } catch (Exception e) {
            log.error("ScoringConfig >>> exception saat load dari backend", e);
            return null;
        }
    }

    private boolean isValidScoringResponse(ApiResponse<GetScoring> body) {
        if (body == null) {
            return false;
        }

        if (!Boolean.TRUE.equals(body.getStatus())) {
            return false;
        }

        var data = body.getData();
        return data != null && !data.isEmpty();
    }

    private String safeText(String s) {
        return s == null ? "" : s.trim();
    }

    private int computeScoreForField(ScoringItem item, String source, String target, SimilarityUtil sim, boolean caseSensitive, String codeLog) {
        if (item == null || !item.isActive()) {
            return 0;
        }

        String s = safeText(source);
        String t = safeText(target);
        if (s.length() <= 2 || t.length() <= 2) {
            return 0;
        }

        if (!caseSensitive) {
            s = s.toUpperCase();
            t = t.toUpperCase();
        }

        double dist = sim.countDistant(s, t);
        double minThreshold = item.getMinSkor() == null ? 0.0 : item.getMinSkor();

        if (dist >= minThreshold) {
            int v = item.getValue() == null ? 0 : item.getValue();
            log.debug("computeScore[{}] >>> dist={} >= {} → +{}", codeLog, dist, minThreshold, v);
            return v;
        }

        log.debug("computeScore[{}] >>> dist={} < {} → +0", codeLog, dist, minThreshold);
        return 0;
    }

    private Frame findCombinationFrame(Page driver, Frame currentFrame, String noRef) {
        if (currentFrame != null && currentFrame.locator(MK_SEL_COMBINATION_DIV).count() > 0) {
            return currentFrame;
        }

        for (Frame f : driver.frames()) {
            if (f.locator(MK_SEL_COMBINATION_DIV).count() > 0) {
                log.info("manualKombinasi >>> {} switch frame ke panel kombinasi", noRef);
                return f;
            }
        }

        log.warn("manualKombinasi >>> {} tidak menemukan frame dengan #data-combination-div", noRef);
        return null;
    }

    private static final int MK_CTX_RETRY = 3;

    private boolean isCtxDestroyed(Throwable e) {
        String m = e.getMessage();
        if (m == null) return false;
        return m.contains("Execution context was destroyed")
               || m.contains("most likely because of a navigation")
               || m.contains("Target closed")
               || m.contains("Frame was detached");
    }

    private void waitListViewReady(Frame frame, AppRequest app) {
        try {
            frame.waitForSelector("#listview1",
                    new Frame.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(15_000)
            );
            frame.locator("#listview1 > div").first().waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        } catch (Exception e) {
            log.warn("{} {} listview belum ready: {}", MK_LOG_PREFIX, app.getNoRefCounter(), e.getMessage());
        }
    }

    private void waitAfterPossibleNavigation(Frame frame, AppRequest app) {
        webDriverService.getDriver().waitForLoadState();
        frame.waitForLoadState();
        waitListViewReady(frame, app);
    }

    private int safeCount(Locator loc, Frame frame, AppRequest app, String ctx) {
        for (int i = 1; i <= MK_CTX_RETRY; i++) {
            try {
                return loc.count();
            } catch (PlaywrightException e) {
                if (isCtxDestroyed(e)) {
                    log.warn("{} {} {} context destroyed (try {}/{}), wait...", MK_LOG_PREFIX,
                            app.getNoRefCounter(), ctx, i, MK_CTX_RETRY);
                    waitAfterPossibleNavigation(frame, app);
                    continue;
                }
                throw e;
            }
        }
        return 0;
    }

}