package com.ilkeiapps.slik.slikengine.service;

import com.ilkeiapps.slik.slikengine.bean.AppRequest;
import com.ilkeiapps.slik.slikengine.bean.AppRequestPayload;
import com.ilkeiapps.slik.slikengine.bean.ProcessReportBulkRequest;
import com.ilkeiapps.slik.slikengine.bean.SubmitRequestBulk;
import com.ilkeiapps.slik.slikengine.retrofit.IM2M;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchProcessingService {

    private final CaptchaService captchaService;

    private final CommonProcessingService commonProcessingService;

    private final EngineService engineService;

    private final IM2M m2mService;

    private final PlaywrightDriverService webDriverService;

    private final ActivityServices activityService;

    @Value("${cbas.engine.folder}")
    private String engineFolder;

    @Value("${cbas.engine.name}")
    private String robotName;

    @Value("${cbas.slik.process}")
    private boolean processSlik;

    private static final int MAX_CAPTCHA_RETRY = 5;

    public void uploadBatch(AppRequestPayload src) {
        var driver = webDriverService.getDriver();
        log.info("maetheexecutor >>> processing batch");

        if (!isValidBatchPayload(src)) {
            return;
        }

        var ids = new ArrayList<Long>();
        String content = buildBatchFileContent(src, ids);

        log.info("maetheexecutor >>> content of batch: {}", content);

        var pp = createProcessReportBulk(ids);
        var sbp = createSubmitRequestBulk(ids);

        File batchFile = createBatchFile(content);
        if (batchFile == null) {
            // error sudah di-log di createBatchFile
            return;
        }

        if (!startBulkOnM2M(pp)) {
            return;
        }

        var frame = webDriverService.getFrame("main");
        if (!openBatchUploadMenu(driver, frame)) {
            return;
        }

        performFileUploadAndSubmit(src, batchFile, driver, frame, pp, sbp);
    }

    private boolean isValidBatchPayload(AppRequestPayload src) {
        if (src == null || ObjectUtils.isEmpty(src.getBatchCode()) || CollectionUtils.isEmpty(src.getData())) {
            log.error("maetheexecutor >>> processing batch, batchCode or data is null/empty");
            return false;
        }
        return true;
    }

    private String buildBatchFileContent(AppRequestPayload src, List<Long> ids) {
        var sb = new StringBuilder();
        for (AppRequest r : src.getData()) {
            sb.append(r.getNoRefCounter()).append("|");
            sb.append(r.getAppRequestPurpose()).append("|");
            if ("COM".equals(r.getAppRequestCustType())) {
                sb.append("C").append("|");
                sb.append(r.getAppRequestNpwp());
            } else {
                sb.append("I").append("|");
                sb.append(r.getAppRequestKtp());
            }
            sb.append("\n");

            engineService.setCurrentProcess("Batch: " + r.getNoRefCounter());
            ids.add(r.getId());
        }
        return sb.toString();
    }

    private ProcessReportBulkRequest createProcessReportBulk(List<Long> ids) {
        var pp = new ProcessReportBulkRequest();
        pp.setIdAppDistribute(ids);
        return pp;
    }

    private SubmitRequestBulk createSubmitRequestBulk(List<Long> ids) {
        var sbp = new SubmitRequestBulk();
        sbp.setIdAppRequest(ids);
        sbp.setApprovalVariable("REQ");
        return sbp;
    }

    private File createBatchFile(String content) {
        long timestamp = System.currentTimeMillis();
        String dirPath = engineFolder + "/batch/";
        File dir = new File(dirPath);

        // ✅ Result mkdirs() tidak diabaikan
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("maetheexecutor >>> cannot create batch directory: {}", dirPath);
            return null;
        }

        File tf = new File(dir, timestamp + "_" + robotName + ".txt");
        try {
            Files.writeString(tf.toPath(), content);
            log.info("maetheexecutor >>> batch file saved at {}", tf.getAbsolutePath());
            return tf;
        } catch (IOException e) {
            log.error("maetheexecutor >>> cannot save batch file {}: {}", tf.getAbsolutePath(), e.getMessage(), e);
            return null;
        }
    }

    private boolean startBulkOnM2M(ProcessReportBulkRequest pp) {
        try {
            log.info("maetheexecutor >>> start batch process (M2M)");
            var cl = m2mService.processStartBulk(pp);
            cl.execute();
            return true;
        } catch (IOException e) {
            log.error("maetheexecutor >>> error calling processStartBulk: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean openBatchUploadMenu(Page driver, Frame frame) {
        try {
            // STEP 1: klik tab atas "Permintaan Data"
            log.info("maetheexecutor >>> open menu 'Permintaan Data'");
            Locator permintaanDataTop = frame.locator("#top1menu a:has-text(\"Permintaan Data\")");

            if (permintaanDataTop.count() > 0) {
                permintaanDataTop.first().click(new Locator.ClickOptions().setTimeout(5000));
                driver.waitForLoadState();
                driver.waitForTimeout(500);
            } else {
                log.warn("maetheexecutor >>> link 'Permintaan Data' tidak ditemukan, lanjut ke left menu");
            }

            // STEP 2: pastikan #left-menu sudah muncul dulu
            log.info("maetheexecutor >>> tunggu #left-menu muncul");
            Locator leftMenu = frame.locator("#left-menu");
            leftMenu.waitFor(new Locator.WaitForOptions().setTimeout(4000)); // tunggu max 10 detik
            driver.waitForTimeout(200);

            // lalu klik "Permintaan iDeb Batch"
            log.info("maetheexecutor >>> open menu kiri 'Permintaan iDeb Batch'");
            Locator batchMenuLabel = leftMenu
                    .locator("span.nav-label")
                    .filter(new Locator.FilterOptions().setHasText("Permintaan iDeb Batch"));

            // fallback kalau teks sedikit beda (IDeb / kapitalisasi)
            if (batchMenuLabel.count() == 0) {
                batchMenuLabel = leftMenu
                        .locator("span.nav-label")
                        .filter(new Locator.FilterOptions().setHasText("Permintaan IDeb Batch"));
            }

            if (batchMenuLabel.count() == 0) {
                log.error("maetheexecutor >>> label 'Permintaan iDeb Batch' tidak ditemukan di #left-menu bahkan setelah wait");
                return false;
            }

            batchMenuLabel.first().click(new Locator.ClickOptions().setTimeout(5000));
            driver.waitForTimeout(500); // tunggu submenu expand (ul.collapse.in)

            // STEP 3: klik submenu "Upload File Batch"
            log.info("maetheexecutor >>> click submenu 'Upload File Batch'");
            Locator uploadFileBatch = leftMenu
                    .locator("a")
                    .filter(new Locator.FilterOptions().setHasText("Upload File Batch"));

            uploadFileBatch.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            uploadFileBatch.first().click(new Locator.ClickOptions().setTimeout(5000));

            driver.waitForLoadState();
            driver.waitForTimeout(1000);
            return true;

        } catch (TimeoutError e) {
            log.error("maetheexecutor >>> timeout open menu batch: {}", e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("maetheexecutor >>> fail to open menu batch: {}", e.getMessage(), e);
            return false;
        }
    }

    private void performFileUploadAndSubmit(AppRequestPayload src, File batchFile, Page driver, Frame frame, ProcessReportBulkRequest pp, SubmitRequestBulk sbp) {
        AppRequest firstReq = src.getData().get(0);

        // 1) Upload file
        uploadFileToUi(firstReq, batchFile, driver, frame);

        // 2) Captcha + submit (dengan / tanpa proses SLIK)
        driver.waitForLoadState();

        String cap2 = captchaService.fetchCapcha2TensorPlaywright(frame);
        Locator cap = frame.locator("#captcha");
        cap.fill(cap2);

        if (Boolean.TRUE.equals(processSlik)) {
            submitBatchWithUiAndM2M(firstReq, frame, driver, pp, sbp);
        } else {
            submitBulkWithoutUi(firstReq, pp, sbp);
        }
    }

    private void uploadFileToUi(AppRequest req, File batchFile, Page driver, Frame frame) {
        var sLog = activityService.start(this.robotName);
        try {
            log.info("maetheexecutor >>> upload file batch: path={} abs={}", batchFile.toPath(), batchFile.getAbsolutePath());
            Locator upl = frame.locator("div[id^='FILE']");
            var fileChooser = driver.waitForFileChooser(upl::click);
            fileChooser.setFiles(batchFile.toPath());

            sLog.setName("Upload file batch");
            sLog.setAppId(req.getId());
        } finally {
            activityService.stop(sLog);
        }
    }

    private void submitBatchWithUiAndM2M(AppRequest req, Frame frame, Page driver, ProcessReportBulkRequest pp, SubmitRequestBulk sbp) {
        var sLog = activityService.start(this.robotName);
        try {
            int attempt = 1;

            while (true) {
                log.info("maetheexecutor >>> click save button (attempt={})", attempt);
                Locator scr = frame.locator("#save-button");
                scr.click(new Locator.ClickOptions().setTimeout(5000));

                driver.waitForLoadState();

                // ⬇ konfirmasi "apakah anda yakin ingin mengunggah"
                if (commonProcessingService.checkDialogContainText(frame, "apakah anda yakin ingin mengunggah")) {
                    log.info("maetheexecutor >>> konfirmasi unggah ditemukan, klik tombol YA");
                    boolean clickedYes = commonProcessingService.clickYesButtonInFrame(frame);
                    log.info("maetheexecutor >>> clickYesButtonInFrame result={}", clickedYes);
                    driver.waitForLoadState();
                }

                // progress modal pertama setelah klik save
                handleProgressModalIfVisible(frame, "maetheexecutor >>> after save click (1)");

                // ⬇⬇ NEW: kalau captcha salah, klik OK lalu isi ulang captcha dan retry
                if (commonProcessingService.checkDialogContainText(frame, "Teks Captcha tidak sama")) {
                    log.warn("maetheexecutor >>> captcha salah pada attempt {}", attempt);
                    commonProcessingService.clickOkButtonInFrame(frame);

                    if (attempt >= MAX_CAPTCHA_RETRY) {
                        log.error("maetheexecutor >>> captcha salah terus, sudah mencapai MAX_CAPTCHA_RETRY={}", MAX_CAPTCHA_RETRY);
                        sLog.setName("Pengisian Captcha dan Submit (captcha gagal, retry habis)");
                        sLog.setAppId(req.getId());
                        return;
                    }

                    // ambil captcha baru dan isi ulang field #captcha
                    driver.waitForLoadState();
                    String newCaptcha = captchaService.fetchCapcha2TensorPlaywright(frame);
                    Locator capField = frame.locator("#captcha");
                    capField.fill(newCaptcha);

                    attempt++;
                    continue;
                }

                // error: nama file mengandung spasi (logic lama, tetap ada)
                if (commonProcessingService.checkDialogContainText(frame, "nama file menggunakan spasi")) {
                    commonProcessingService.clickOkButtonInFrame(frame);
                    sLog.setName("Pengisian Captcha dan Submit (nama file salah)");
                    sLog.setAppId(req.getId());
                    return;
                }

                driver.waitForLoadState();

                handleProgressModalIfVisible(frame, "maetheexecutor >>> after save click (2)");

                if (!isScreenshotStatusNone()) {
                    log.error("maetheexecutor >>> status after submit is not NONE");
                    sLog.setName("Pengisian Captcha dan Submit (status tidak NONE)");
                    sLog.setAppId(req.getId());
                    return;
                }

                String refCode = readReferenceCode(frame);
                pp.setReffCode(refCode);

                callBulkDoneAndCallback(req, pp, sbp);

                sLog.setName("Selesai");
                sLog.setAppId(req.getId());
                return; // sukses, keluar dari while
            }
        } catch (IOException e) {
            log.error("maetheexecutor >>> error upload file batch (UI+M2M): {}", e.getMessage(), e);
        } finally {
            activityService.stop(sLog);
        }
    }

    private void submitBulkWithoutUi(AppRequest req, ProcessReportBulkRequest pp, SubmitRequestBulk sbp) {
        var sLog = activityService.start(this.robotName);
        try {
            callBulkDoneAndCallback(req, pp, sbp);
            sLog.setName("Selesai tanpa submit UI");
            sLog.setAppId(req.getId());
        } catch (IOException e) {
            log.error("maetheexecutor >>> error upload file batch (M2M only): {}", e.getMessage(), e);
        } finally {
            activityService.stop(sLog);
        }
    }

    private void handleProgressModalIfVisible(Frame frame, String context) {
        if (commonProcessingService.isProgressModalVisible(frame)) {
            log.error("{} >>> progress modal masih tampil, akan dihilangkan secara aplikasi", context);
            commonProcessingService.removeProgressModal(frame);
        }
    }

    private boolean isScreenshotStatusNone() {
        byte[] sc = commonProcessingService.screenshot();
        String stat = commonProcessingService.checkScreenshot(sc);
        log.info("maetheexecutor >>> screenshot status after submit: {}", stat);
        return "NONE".equals(stat);
    }

    private String readReferenceCode(Frame frame) {
        Locator cd = frame.locator("#refCode");
        cd.waitFor();
        return cd.textContent();
    }

    private void callBulkDoneAndCallback(AppRequest req, ProcessReportBulkRequest pp, SubmitRequestBulk sbp) throws IOException {
        log.info("maetheexecutor >>> call processDoneBulk & processCallbackBulk for appId={}", req.getId());
        var cl = m2mService.processDoneBulk(pp);
        cl.execute();
        var cb = m2mService.processCallbackBulk(sbp);
        cb.execute();
    }
}
