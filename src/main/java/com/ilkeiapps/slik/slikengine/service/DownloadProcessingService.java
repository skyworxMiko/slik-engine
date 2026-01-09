package com.ilkeiapps.slik.slikengine.service;

import com.ilkeiapps.slik.slikengine.bean.*;
import com.ilkeiapps.slik.slikengine.retrofit.IM2M;
import com.ilkeiapps.slik.slikengine.retrofit.IM2MImage;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadProcessingService {

    private final PlaywrightDriverService webDriverService;
    private final CaptchaService captchaService;
    private final CommonProcessingService commonProcessingService;
    private final EngineService engineService;
    private final IM2M m2mService;
    private final IM2MImage m2mImageService;
    private final ActivityServices activityServices;

    @Value("${cbas.engine.folder}")
    private String engineFolder;

    @Value("${cbas.engine.name}")
    private String robotName;

    @Value("${slik.download.filter.noref}")
    private boolean useDownloadNoRefFilter;

    private static final String SEARCH_BUTTON = "#search-button";
    private static final String TEXT_PLAIN = "text/plain";
    private static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    private static final String GAGAL_SLEEP = "batch >>> gagal sleep";
    private static final String DOWNLOAD_MANUAL = "Download Manual";
    private static final String BATCH_FAIL_REPORT_DOWNLOAD_DONE = "batch >>> fail to report download done for data: {}";
    private static final String GAGAL_DATA_REQUEST = "manual >>> gagal data request";
    private static final String GAGAL_BUKA_MENU_PERMINTAAN_DOWNLOAD = "batch >>> gagal buka menu permintaan download manual";
    private static final String GAGAL_SET_DATA_INPUT = "manual >>> gagal set data input manual: {}";
    private static final String FREM_LOCATOR_MENU = "html > body > div > div:nth-of-type(1) > div > ul > li:nth-of-type(3) > a";
    private static final String STATUS_CRASH = "CRASH";

    public void manualDownload(AppRequestPayload src) {
        try {
            Page driver = webDriverService.getDriver();
            Frame frame = webDriverService.getFrame("main");

            if (!prepareManualDownload(src, driver, frame)) {
                return;
            }

            List<DownloadBean> downloadBeans = new ArrayList<>();
            var data = src.getData();

            for (var d : data) {
                if (!processSingleDownload(driver, frame, d, downloadBeans)) {
                    return;
                }
            }
        } catch (PlaywrightException e) {
            log.error("manualDownload >>> Playwright error", e);
            engineService.setCurrentStatusEngine(STATUS_CRASH);
        } catch (Exception e) {
            log.error("manualDownload >>> unexpected error", e);
            engineService.setCurrentStatusEngine(STATUS_CRASH);
        }
    }

    private boolean prepareManualDownload(AppRequestPayload src, Page driver, Frame frame) {
        if (CollectionUtils.isEmpty(src.getData())) {
            log.error(GAGAL_DATA_REQUEST);
            return false;
        }

        if (!openManualDownloadMenu(driver, frame)) {
            return false;
        }

        driver.waitForLoadState(LoadState.LOAD);

        return sleepSafely();
    }

    private boolean processSingleDownload(Page driver, Frame frame, AppRequest d, List<DownloadBean> downloadBeans) throws Exception {
        engineService.setLastUpdate(LocalDateTime.now());
        var sLog = activityServices.start(this.robotName);

        String noRef = d.getNoRefCounter();
        boolean hasNoRef = noRef != null && !noRef.isBlank();
        if (useDownloadNoRefFilter) {
            if (!hasNoRef) {
                log.warn("manual >>> {} noRefCounter kosong, skip proses download manual (useDownloadNoRefFilter=true)", d.getNoRefCounter());
                return sleepSafely();
            }

            if (!fillUserReference(frame, noRef)) {
                return false;
            }
        } else {
            log.info("manual >>> {} skip isi USER_REFERENCE_CODE2 karena useDownloadNoRefFilter=false (noRef='{}')", d.getNoRefCounter(), noRef);
        }

        clickSearchButton(frame);
        driver.waitForLoadState(LoadState.LOAD);

        sLog.setName(DOWNLOAD_MANUAL);
        sLog.setAppId(d.getId());
        activityServices.stop(sLog);

        handleProgressModalBeforeSearch(frame, d.getNoRefCounter());
        if (!handleUtdCap(frame, d.getNoRefCounter())) {
            return false;
        }

        if (isNoDataFound(frame, d.getNoRefCounter())) {
            log.warn("manual >>> {} hasil permintaan SLIK: TIDAK ADA DATA YANG DITEMUKAN, rollback ke approval", d.getNoRefCounter());
            rollbackToApproval(d.getNoRefCounter());
            return sleepSafely();
        }

        List<Locator> rows = readResultRows(frame, d.getNoRefCounter());
        for (int i = 1; i < rows.size(); i++) {
            handleRowDownload(driver, frame, rows.get(i), d.getNoRefCounter(), d.getId(), downloadBeans);
        }

        processDownloadedFiles(downloadBeans, true);

        return sleepSafely();
    }

    private boolean isNoDataFound(Frame frame, String noRefCounter) {
        try {
            Locator infoDiv = frame.locator("div.dhx_pager_info > div");
            int count = infoDiv.count();
            if (count == 0) {
                log.debug("manual >>> {} dhx_pager_info tidak ditemukan", noRefCounter);
                return false;
            }

            String text = infoDiv.first().innerText();
            if (text == null) {
                return false;
            }

            String upper = text.trim().toUpperCase();
            log.info("manual >>> {} pager info text: {}", noRefCounter, upper);

            return upper.contains("TIDAK ADA DATA YANG DITEMUKAN");
        } catch (PlaywrightException e) {
            log.warn("manual >>> {} gagal deteksi 'Tidak ada data yang ditemukan': {}", noRefCounter, e.getMessage(), e);
            return false;
        }
    }

    private void rollbackToApproval(String noRefCounter) {
        log.info("manual >>> {} rollback ke approval via /api/m2m/robot/notfound/code", noRefCounter);

        ProcessReportRequest pr = new ProcessReportRequest();
        pr.setStatusCode(noRefCounter);

        try {
            var call = m2mService.notFoundByCode(pr);
            retrofit2.Response<ApiResponse<ProcessReportRequest>> response = call.execute();

            if (!response.isSuccessful()) {
                log.error("manual >>> rollbackToApproval HTTP not successful for noRef {}", noRefCounter);
                return;
            }

            ApiResponse<ProcessReportRequest> body = response.body();
            if (body != null && Boolean.FALSE.equals(body.getStatus())) {
                log.error("manual >>> rollbackToApproval business error for noRef {} msg={}", noRefCounter, body.getMessage());
            } else {
                log.info("manual >>> {} rollbackToApproval sukses", noRefCounter);
            }
        } catch (Exception e) {
            log.error("manual >>> fail to rollbackToApproval for noRef {}", noRefCounter, e);
        }
    }

    private boolean openManualDownloadMenu(Page driver, Frame frame) {
        try {
            Locator menu = frame.locator(FREM_LOCATOR_MENU);
            menu.click(new Locator.ClickOptions().setTimeout(5000));
            driver.waitForLoadState(LoadState.LOAD);

            Locator linkDownloadManual = frame.locator(
                    "html > body > div:nth-of-type(1) > div:nth-of-type(2) > div:nth-of-type(1) > div > aside > nav > ul > li:nth-of-type(2) > ul > li:nth-of-type(1) > ul > li:nth-of-type(3) > a"
            );
            linkDownloadManual.click(new Locator.ClickOptions().setTimeout(5000));
            return true;
        } catch (Exception e) {
            log.error(GAGAL_BUKA_MENU_PERMINTAAN_DOWNLOAD, e);
            return false;
        }
    }

    private boolean sleepSafely() {
        try {
            Thread.sleep(5000L);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(GAGAL_SLEEP, e);
            return false;
        }
    }

    private boolean fillUserReference(Frame frame, String refCode) {
        try {
            Locator inputKodeRefPengguna = frame.locator("#USER_REFERENCE_CODE2");
            inputKodeRefPengguna.fill(refCode);
            return true;
        } catch (Exception e) {
            log.error(GAGAL_SET_DATA_INPUT, e.getMessage(), e);
            return false;
        }
    }

    private void clickSearchButton(Frame frame) {
        Locator btn = frame.locator(SEARCH_BUTTON);
        btn.click(new Locator.ClickOptions().setTimeout(5000));
    }

    private void handleProgressModalBeforeSearch(Frame frame, String noRefCounter) {
        log.info("manual >>> {} check modal progress", noRefCounter);
        if (commonProcessingService.isProgressModalVisible(frame)) {
            log.error("manual >>> {} progress bar is still visible, close it first", noRefCounter);
            commonProcessingService.removeProgressModal(frame);
        }

        log.info("manual >>> {} check modal progress 2nd time", noRefCounter);
        commonProcessingService.removeProgressModal(frame);
    }

    private boolean handleUtdCap(Frame frame, String noRefCounter) {
        byte[] sc = commonProcessingService.screenshot();
        String res = commonProcessingService.checkUtdCap(sc);
        if ("NONE".equals(res)) {
            return true;
        }

        log.error("manual >>> {} gagal car data dengan error: {}", noRefCounter, res);

        Locator okb1 = frame.locator("button[class$='btn-primary']");
        if (okb1 != null && okb1.isVisible()) {
            okb1.click(new Locator.ClickOptions().setTimeout(5000));
        }

        sc = commonProcessingService.screenshot();
        res = commonProcessingService.checkUtdCap(sc);
        return "NONE".equals(res);
    }

    private List<Locator> readResultRows(Frame frame, String noRefCounter) {
        try {
            List<Locator> tables = frame.locator("table").all();
            if (tables.size() < 2) {
                log.error("manual >>> gagal baca table, jumlah table: {}, minimum 2", tables.size());
                return Collections.emptyList();
            }

            Locator table = tables.get(1);
            return table.locator("tbody > tr").all();
        } catch (Exception e) {
            log.error("manual >>> gagal baca table hasil untuk {}", noRefCounter, e);
            return Collections.emptyList();
        }
    }

    private void handleRowDownload(Page driver, Frame frame, Locator row, String noRefCounter, Long appId, List<DownloadBean> downloads) {
        List<Locator> cols = row.locator("td").all();
        if (cols.size() < 9) {
            log.warn("manual >>> jumlah kolom kurang dari 9, skip row");
            return;
        }

        String code = cols.get(1).textContent();
        Locator btnx = cols.get(8);

        ApiResponse<Long> remoteCheck = isDataNeedToBeApprovedVRemote(code);
        if (!Boolean.TRUE.equals(remoteCheck.getStatus()) || CollectionUtils.isEmpty(remoteCheck.getData())) {
            return;
        }

        Long idm = remoteCheck.getData().get(0);
        engineService.setCurrentProcess("Download: " + code);

        var sLog = activityServices.start(this.robotName);
        try {
            btnx.click(new Locator.ClickOptions().setTimeout(2000));
            driver.waitForLoadState();

            String cp = captchaService.fetchCapcha2TensorPlaywright(frame);
            if (StringUtils.isEmpty(cp)) {
                log.error("manual >>> gagal fetch captcha");
                return;
            }

            frame.locator("#captcha").fill(cp);

            Download download = waitForDownload(driver, frame.locator("#download-button"), idm);

            sLog.setName(DOWNLOAD_MANUAL);
            sLog.setAppId(appId);
            activityServices.stop(sLog);

            handleProgressModalAfterDownload(frame, noRefCounter);

            if (download != null) {
                Path destination = Paths.get(engineFolder, download.suggestedFilename());
                download.saveAs(destination);

                DownloadBean dbn = new DownloadBean();
                dbn.setId(idm);
                dbn.setNoRefCounter(code);
                dbn.setFile(download.suggestedFilename());
                downloads.add(dbn);
            }
        } catch (Exception e) {
            log.error("manual >>> gagal proses download data untuk {}", code, e);
        }
    }

    private Download waitForDownload(Page driver, Locator downloadButton, Long idm) {
        try {
            return driver.waitForDownload(() -> downloadButton.click(new Locator.ClickOptions().setTimeout(5000)));
        } catch (Exception e) {
            log.error(BATCH_FAIL_REPORT_DOWNLOAD_DONE, idm, e);
            return null;
        }
    }

    private void handleProgressModalAfterDownload(Frame frame, String noRefCounter) {
        if (commonProcessingService.isProgressModalVisible(frame)) {
            log.error("manual >>> {} progress bar is still visible, close it first", noRefCounter);
            commonProcessingService.removeProgressModal(frame);
        }
        commonProcessingService.removeProgressModal(frame);
    }

    private void processDownloadedFiles(List<DownloadBean> downloads, boolean notifyM2m) throws IOException {
        for (DownloadBean ap : downloads) {
            log.info("manual >>> proses upload file: {}", ap.getFile());

            Path sourcePath = Paths.get(engineFolder, ap.getFile());
            File sourceFile = sourcePath.toFile();
            if (!sourceFile.exists()) {
                continue;
            }

            // upload file ke MinIO
            RequestBody code = RequestBody.create(robotName, MediaType.parse(TEXT_PLAIN));
            RequestBody reffId = RequestBody.create(ap.getNoRefCounter(), MediaType.parse(TEXT_PLAIN));
            RequestBody fileStart = RequestBody.create(sourceFile, MediaType.parse(APPLICATION_OCTET_STREAM));

            MultipartBody.Part bodyStart = MultipartBody.Part.createFormData("file", ap.getNoRefCounter() + ".ideb", fileStart);

            var uploadCall = m2mImageService.uploadIdeb(code, reffId, bodyStart);
            log.info("manual >>> proses upload file: {}", ap.getFile());

            retrofit2.Response<?> uploadResponse = uploadCall.execute();
            if (!uploadResponse.isSuccessful()) {
                log.error("manual >>> uploadIdeb gagal untuk {}", ap.getFile());
            }

            // === HANYA UNTUK FLOW NORMAL (DATA FOUND) ===
            if (notifyM2m) {
                ProcessReportRequest pr = new ProcessReportRequest();
                pr.setStatusCode(ap.getNoRefCounter());
                var callDownloadDone = m2mService.downloadDone(pr);
                retrofit2.Response<?> downloadDoneResponse = callDownloadDone.execute();
                if (!downloadDoneResponse.isSuccessful()) {
                    log.error("manual >>> downloadDone gagal untuk {}", ap.getNoRefCounter());
                }

                SubmitRequest sr = new SubmitRequest();
                sr.setIdAppRequest(ap.getId());
                sr.setApprovalVariable("FIN");
                var callCallback = m2mService.processCallback(sr);
                retrofit2.Response<?> callbackResponse = callCallback.execute();
                if (!callbackResponse.isSuccessful()) {
                    log.error("manual >>> processCallback gagal untuk {}", ap.getId());
                }
            }

            // pindahkan file ke folder processed
            Path processedDir = Paths.get(engineFolder, "processed");
            try {
                Files.createDirectories(processedDir);

                Path destinationPath = processedDir.resolve(sourcePath.getFileName());
                Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("File copied to: {}", destinationPath);

                Files.deleteIfExists(sourcePath);
            } catch (IOException e) {
                log.error("manual >>> gagal memindahkan file {} ke processed", ap.getFile(), e);
            }
        }
    }

    public void processSingleDownloadedFile(AppRequest req, Download download, boolean notifyM2m) {
        if (download == null) {
            log.warn("manual >>> {} objek Download null, skip proses upload", (req != null ? req.getNoRefCounter() : "-"));
            return;
        }

        try {
            String fileName = download.suggestedFilename();
            if (fileName == null || fileName.isBlank()) {
                fileName = req.getNoRefCounter() + ".pdf";
            }

            Path destination = Paths.get(engineFolder, fileName);
            download.saveAs(destination);

            DownloadBean bean = new DownloadBean();
            bean.setId(req.getId());
            bean.setNoRefCounter(req.getNoRefCounter());
            bean.setFile(fileName);

            List<DownloadBean> single = new ArrayList<>();
            single.add(bean);

            processDownloadedFiles(single, notifyM2m);
        } catch (Exception e) {
            log.error("manual >>> {} gagal memproses single downloaded file: {}", req.getNoRefCounter(), e.getMessage(), e);
        }
    }

    private ApiResponse<Long> isDataNeedToBeApprovedVRemote(String code) {
        var wrap = new ApiResponse<Long>();
        wrap.setStatus(false);

        try {
            log.info("approval >>> check data for approval: {}", code);

            var pp = new ProcessReportRequest();
            pp.setStatusCode(code);

            var call = m2mService.downloadStart(pp);
            var res = call.execute();

            ApiResponse<Long> body = res.body();
            if (body != null) {
                return body;
            }

            log.error("approval >>> response body is null for code {}", code);
            wrap.setMessage("Response body kosong saat cek approval");
        } catch (IOException e) {
            log.error("approval >>> error when checking approval for code {}", code, e);
            wrap.setMessage("Gagal cek approval: " + e.getMessage());
        }

        return wrap;
    }

    public void batchDownload(AppRequestPayload src) {
        log.info("batch >>> start");

        Page driver = webDriverService.getDriver();
        Frame frame = webDriverService.getFrame("main");

        if (src == null || CollectionUtils.isEmpty(src.getData())) {
            log.warn("batch >>> payload kosong / data kosong");
            return;
        }

        if (!openBatchDownloadMenu(driver, frame)) {
            return;
        }

        driver.waitForLoadState();

        if (sleepSafely(2_000L)) {
            return;
        }

        List<DownloadBean> downloads = new ArrayList<>();

        for (var d : src.getData()) {
            // 👉 kalau return false berarti ada error fatal → hentikan seluruh proses
            boolean ok = processBatchDownloadForRequest(driver, frame, d, downloads);
            if (!ok) {
                return;
            }
        }

        processBatchDownloadedFiles(downloads);
    }

    private boolean processBatchDownloadForRequest(Page driver, Frame frame, AppRequest d, List<DownloadBean> downloads) {
        String noRef = d.getNoRefCounter();
        boolean hasNoRef = StringUtils.isNotBlank(noRef);

        if (useDownloadNoRefFilter) {
            if (!hasNoRef) {
                log.warn("batch >>> {} noRefCounter kosong, skip request ini (useDownloadNoRefFilter=true)", d.getNoRefCounter());
                return true;
            }

            if (!fillBatchReference(frame, noRef)) {
                return false;
            }
        } else {
            log.info("batch >>> {} skip isi BATCH_REFERENCE_CODE2 karena useDownloadNoRefFilter=false (noRef='{}')", d.getNoRefCounter(), noRef);
        }

        if (!clickSearch(frame)) {
            return false;
        }

        driver.waitForLoadState(LoadState.LOAD);
        if (sleepSafely(5_000L)) {
            return false;
        }

        List<Locator> rows = findBatchResultRows(frame);
        if (rows.isEmpty()) {
            log.info("batch >>> tidak ada hasil untuk ref {}", d.getNoRefCounter());
            return true;
        }

        processBatchRows(driver, frame, rows, downloads);

        return true;
    }

    private boolean openBatchDownloadMenu(Page driver, Frame frame) {
        try {
            log.info("batch >>> try click permintaan");
            Locator menu = frame.locator(FREM_LOCATOR_MENU);
            menu.click(new Locator.ClickOptions().setTimeout(5000));
            driver.waitForLoadState(LoadState.LOAD);

            log.info("batch >>> try click permintaan batch");
            Locator spanPermintaanDebBatch = frame.locator(
                    "html > body > div:nth-of-type(1) > div:nth-of-type(2) > div:nth-of-type(1) > div > aside > nav > ul > li:nth-of-type(2) > ul > li:nth-of-type(2) > a > span"
            );
            spanPermintaanDebBatch.click(new Locator.ClickOptions().setTimeout(2000));

            log.info("batch >>> try click download batch");
            Locator linkUploadFileBatch = frame.locator(
                    "html > body > div > div:nth-of-type(2) > div:nth-of-type(1) > div > aside > nav > ul > li:nth-of-type(2) > ul > li:nth-of-type(2) > ul > li:nth-of-type(2) > a"
            );
            linkUploadFileBatch.click(new Locator.ClickOptions().setTimeout(2000));

            return true;
        } catch (Exception e) {
            log.error("batch >>> gagal buka menu permintaan batch", e);
            return false;
        }
    }

    private boolean sleepSafely(long millis) {
        try {
            Thread.sleep(millis);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(GAGAL_SLEEP, e);
            return true;
        }
    }

    private boolean fillBatchReference(Frame frame, String refCode) {
        try {
            frame.locator("#BATCH_REFERENCE_CODE2")
                    .waitFor(new Locator.WaitForOptions().setTimeout(5000));
            Locator inputKodeRefPengguna = frame.locator("#BATCH_REFERENCE_CODE2");
            inputKodeRefPengguna.fill(refCode);
            return true;
        } catch (Exception e) {
            log.error("batch >>> gagal set data input batch: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean clickSearch(Frame frame) {
        try {
            Locator btn = frame.locator(SEARCH_BUTTON);
            btn.click(new Locator.ClickOptions().setTimeout(5000));
            return true;
        } catch (Exception e) {
            log.error("batch >>> gagal klik tombol search", e);
            return false;
        }
    }

    private List<Locator> findBatchResultRows(Frame frame) {
        try {
            Locator tables = frame.locator("table");
            List<Locator> tablec = tables.all();
            if (tablec.size() < 2) {
                log.error("batch >>> fail to read table result, number of table: {}, minimum 2", tablec.size());
                return Collections.emptyList();
            }

            Locator table = tablec.get(1);
            List<Locator> rows = table.locator("tbody > tr").all();
            log.info("batch >>> found {} rows in table", rows.size());
            return rows;
        } catch (Exception e) {
            log.error("batch >>> fail to read table with error", e);
            return Collections.emptyList();
        }
    }

    private void processBatchRows(Page driver, Frame frame, List<Locator> rows, List<DownloadBean> downloads) {
        for (int i = 1; i < rows.size(); i++) {
            Locator row = rows.get(i);
            String code = null;

            try {
                List<Locator> cols = row.locator("td").all();
                if (cols.size() <= 11) {
                    log.warn("batch >>> jumlah kolom kurang dari 12 pada row {}", i);
                    continue;
                }

                code = cols.get(4).textContent();
                log.info("batch >>> found code: {} in position: {}", code, i);
                Locator btn1 = cols.get(11);

                handleBatchRow(driver, frame, code, btn1, downloads);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("batch >>> interrupted while processing code {}", code, e);
                return;
            } catch (IOException e) {
                log.error("batch >>> IO error while processing data {}: {}", code, e.getMessage(), e);
                if (code != null) {
                    reportNotFoundByCode(code);
                }
                return;
            } catch (Exception e) {
                log.error("batch >>> fail to process data {} error: {}", code, e.getMessage(), e);
                if (code != null) {
                    reportNotFoundByCode(code);
                }
                return;
            }
        }
    }

    private void handleBatchRow(Page driver, Frame frame, String code, Locator btn1, List<DownloadBean> downloads) throws InterruptedException, IOException {
        ApiResponse<Long> remoteCheck = this.isDataNeedToBeApprovedVRemote(code);
        if (!Boolean.TRUE.equals(remoteCheck.getStatus()) || CollectionUtils.isEmpty(remoteCheck.getData())) {
            log.error("batch >>> data {} does not approved / id kosong", code);
            return;
        }

        Long idm = remoteCheck.getData().get(0);
        log.info("batch >>> data: {} found with id: {}", code, idm);
        engineService.setCurrentProcess("Download: " + code);

        log.info("batch >>> click button check for data: {}", code);
        btn1.click(new Locator.ClickOptions().setTimeout(2000));
        driver.waitForLoadState();

        String cp = captchaService.fetchCapcha2TensorPlaywright(frame);
        if (StringUtils.isEmpty(cp)) {
            log.error("batch >>> gagal fetch captcha untuk {}", code);
            return;
        }

        frame.locator("#captcha").fill(cp);

        log.info("batch >>> click button download for data: {}", code);
        Locator scr = frame.locator("#download-button");
        if (!scr.isVisible()) {
            reportProcessFail(idm, code);
            return;
        }

        Download download = waitForDownload(driver, scr, idm);
        if (sleepSafely(5_000L)) {
            return;
        }

        clickOkIfPresent(frame, code);

        if (download != null) {
            Path destination = Paths.get(engineFolder, download.suggestedFilename());
            download.saveAs(destination);
            log.info("batch >>> downloaded to path: {} file: {}", engineFolder, download.suggestedFilename());

            DownloadBean dbn = new DownloadBean();
            dbn.setId(idm);
            dbn.setNoRefCounter(code);
            dbn.setFile(download.suggestedFilename());
            downloads.add(dbn);
        }
    }

    private void clickOkIfPresent(Frame frame, String code) {
        try {
            Locator okButton = frame.locator("button.btn.btn-primary", new Frame.LocatorOptions().setHasText("OK"));
            okButton.waitFor(new Locator.WaitForOptions().setTimeout(3000));

            if (okButton.isVisible()) {
                okButton.click(new Locator.ClickOptions().setTimeout(2000));
            }
        } catch (Exception e) {
            log.error("batch >>> {} error click ok button: {}", code, e.getMessage());
        }

        log.info("batch >>> no pop up for data: {}", code);
    }

    private void reportProcessFail(Long idm, String code) {
        ProcessReportRequest pr = new ProcessReportRequest();
        pr.setIdAppDistribute(idm);

        var call = m2mService.processFail(pr);
        try {
            retrofit2.Response<?> response = call.execute();
            if (!response.isSuccessful()) {
                log.error("batch >>> processFail not successful for id {} code {}", idm, code);
            }
        } catch (IOException e) {
            log.error(BATCH_FAIL_REPORT_DOWNLOAD_DONE, idm, e);
        }
    }

    private void reportNotFoundByCode(String code) {
        ProcessReportRequest pr = new ProcessReportRequest();
        pr.setStatusCode(code);

        var call = m2mService.notFoundByCode(pr);
        try {
            retrofit2.Response<?> response = call.execute();
            if (!response.isSuccessful()) {
                log.error("batch >>> notFoundByCode not successful for code {}", code);
            }
        } catch (IOException e) {
            log.error("batch >>> fail to report not found by code: {}", code, e);
        }
    }

    private void processBatchDownloadedFiles(List<DownloadBean> downloads) {
        for (DownloadBean ap : downloads) {
            String fileName = ap.getFile();
            Path sourcePath = Paths.get(engineFolder, fileName);
            Path processedDir = Paths.get(engineFolder, "processed");

            if (!Files.exists(sourcePath)) {
                log.warn("batch >>> source file not found: {}", sourcePath);
            } else {
                if (ensureProcessedDirExists(processedDir)) {
                    log.info("batch >>> report done for data: {}, file source {}  file dest {}",
                            ap.getNoRefCounter(), sourcePath, processedDir);

                    reportDownloadDone(ap);
                    submitDownloadCallback(ap);
                    uploadIdebFile(ap, sourcePath);
                    moveFileToProcessed(sourcePath, processedDir);
                }
            }
        }
    }

    private boolean ensureProcessedDirExists(Path processedDir) {
        try {
            Files.createDirectories(processedDir);
            return true;
        } catch (IOException e) {
            log.error("batch >>> gagal membuat folder processed: {}", processedDir, e);
            return false;
        }
    }

    private void reportDownloadDone(DownloadBean ap) {
        ProcessReportRequest pr = new ProcessReportRequest();
        pr.setStatusCode(ap.getNoRefCounter());

        var call = m2mService.downloadDone(pr);
        try {
            retrofit2.Response<?> resp = call.execute();
            if (!resp.isSuccessful()) {
                log.error(BATCH_FAIL_REPORT_DOWNLOAD_DONE, ap.getNoRefCounter());
            }
        } catch (IOException e) {
            log.error(BATCH_FAIL_REPORT_DOWNLOAD_DONE, ap.getNoRefCounter(), e);
        }
    }

    private void submitDownloadCallback(DownloadBean ap) {
        log.info("batch >>> submit callback for data: {}", ap.getNoRefCounter());

        SubmitRequest sr = new SubmitRequest();
        sr.setIdAppRequest(ap.getId());
        sr.setApprovalVariable("FIN");

        var call = m2mService.processCallback(sr);
        try {
            retrofit2.Response<?> resp = call.execute();
            if (!resp.isSuccessful()) {
                log.error("batch >>> fail to submit download callback for data: {}", ap.getNoRefCounter());
            }
        } catch (IOException e) {
            log.error("batch >>> fail to submit download callback for data: {}", ap.getNoRefCounter(), e);
        }
    }

    private void uploadIdebFile(DownloadBean ap, Path sourcePath) {
        log.info("batch >>> upload file: {} for data: {}", sourcePath, ap.getNoRefCounter());

        RequestBody code = RequestBody.create(robotName, MediaType.parse(TEXT_PLAIN));
        RequestBody reffId = RequestBody.create(ap.getNoRefCounter(), MediaType.parse(TEXT_PLAIN));
        RequestBody fileStart = RequestBody.create(sourcePath.toFile(), MediaType.parse(APPLICATION_OCTET_STREAM));

        MultipartBody.Part bodyStart =
                MultipartBody.Part.createFormData("file", ap.getNoRefCounter() + ".ideb", fileStart);

        var call = m2mService.uploadIdeb(code, reffId, bodyStart);
        try {
            retrofit2.Response<?> resp = call.execute();
            if (!resp.isSuccessful()) {
                log.error("batch >>> fail to upload file: {} for data: {}", sourcePath, ap.getNoRefCounter());
            }
        } catch (IOException e) {
            log.error("batch >>> fail to upload file: {} for data: {}", sourcePath, ap.getNoRefCounter(), e);
        }
    }

    private void moveFileToProcessed(Path sourcePath, Path processedDir) {
        Path destinationPath = processedDir.resolve(sourcePath.getFileName());
        try {
            Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("File copied to: {}", destinationPath);

            Files.deleteIfExists(sourcePath);
        } catch (IOException e) {
            log.error("batch >>> fail to copy/delete file: {} to: {} with error: {}", sourcePath, destinationPath, e.getMessage(), e);
        }
    }
}
