package com.ilkeiapps.slik.slikengine.service;

import com.ilkeiapps.slik.slikengine.bean.*;
import com.ilkeiapps.slik.slikengine.retrofit.IM2M;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.LoadState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalProcessingService {

    private final PlaywrightDriverService webDriverService;
    private final CaptchaService captchaService;
    private final CommonProcessingService commonProcessingService;
    private final IM2M m2mService;
    private final EngineService engineService;
    private final ActivityServices activityServices;

    private static final String FORM_APPROVAL = "Form Approval";
    private static final String CRASH = "CRASH";

    @Value("${cbas.engine.name}")
    private String robotName;

    @Value("${cbas.slik.process}")
    private boolean processSlik;

    @Value("${slik.approval.filter.createdby}")
    private boolean useCreatedByFilter;

    @Value("${slik.approval.filter.noref}")
    private boolean useNoRefFilter;

    public void approval(AppRequestPayload src) {
        if (src == null || CollectionUtils.isEmpty(src.getData())) {
            log.info("approval >>> data is empty");
            return;
        }

        if (!prepareApprovalPage()) {
            return;
        }

        for (AppRequest req : src.getData()) {
            if (!processApprovalForRequest(req)) {
                return;
            }
        }
    }

    private boolean prepareApprovalPage() {
        var driver = webDriverService.getDriver();
        var frame = webDriverService.getFrame("main");

        try {
            log.info("approval >>> click menu permintaan");
            Locator menu = frame.locator("html > body > div:nth-of-type(1) > div:nth-of-type(1) > div > ul > li:nth-of-type(2) > a");
            menu.click(new Locator.ClickOptions().setTimeout(5000));

            driver.waitForLoadState(LoadState.LOAD);

            var fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy");
            var today = LocalDateTime.now();
            var tdago = today.minusDays(3);
            String st = tdago.format(fmt);

            frame.locator("#START_DATE").waitFor(new Locator.WaitForOptions().setTimeout(5000));
            frame.evaluate("document.getElementById('START_DATE').removeAttribute('readonly');");
            frame.evaluate("document.getElementById('START_DATE').value='" + st + "'");

            driver.waitForLoadState(LoadState.LOAD);
            return true;
        } catch (Exception e) {
            log.error("approval >>> Fail to load frame", e);
            return false;
        }
    }

    private boolean processApprovalForRequest(AppRequest req) {
        // 1. Isi filter noRef + klik cari
        fillFilterNoRefCode(req);

        // 2. Baca tabel & ambil list id yang perlu di-approve
        List<DownloadBean> ids = readResultTableAndCollectIds(req);

        // Kalau di tengah proses baca tabel terjadi error fatal,
        if (CRASH.equals(engineService.getCurrentStatusEngine())) {
            return false;
        }

        // Jika tidak ada yang harus di-approve, lanjut ke request berikutnya.
        if (CollectionUtils.isEmpty(ids)) {
            return true;
        }

        // 3. Captcha + kirim approval ke SLIK + cek dialog/screenshot
        handleApprovalWithCaptcha(req, ids);
        return true;
    }

    private String resolveCreatedBy(AppRequest req) {
        if (req == null) {
            return "";
        }
        String createdBy = req.getAppRequestCreatedBy();
        return createdBy != null ? createdBy : "";
    }

    private void fillFilterNoRefCode(AppRequest req) {
        var driver = webDriverService.getDriver();
        var frame = webDriverService.getFrame("main");
        var sLog = activityServices.start(this.robotName);

        try {
            String createdByValue = Optional.of(resolveCreatedBy(req)).orElse("");
            String noRefValue = Optional.ofNullable(req.getNoRefCounter()).orElse("");

            // flag dari application.properties + ada isinya
            boolean hasCreatedBy = useCreatedByFilter && !createdByValue.isBlank();
            boolean hasNoRef = useNoRefFilter && !noRefValue.isBlank();

            if (!hasCreatedBy && !hasNoRef) {
                log.info(
                        "approval [{}] >>> tidak mengisi filter CREATED_BY/USER_REFERENCE_CODE " +
                        "(useCreatedByFilter={}, useNoRefFilter={}), lanjut tanpa filter",
                        req.getNoRefCounter(), useCreatedByFilter, useNoRefFilter
                );
            } else {
                // Isi CREATED_BY kalau di-enable dan ada nilai
                if (hasCreatedBy) {
                    log.info("approval [{}] >>> isikan filter nama pengguna: {}", req.getNoRefCounter(), createdByValue);
                    Locator createdBy = frame.locator("#CREATED_BY");
                    createdBy.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                    createdBy.fill(createdByValue);
                } else {
                    log.info("approval [{}] >>> skip CREATED_BY (config/useCreatedByFilter={}, value='{}')", req.getNoRefCounter(), useCreatedByFilter, createdByValue);
                }

                // Isi USER_REFERENCE_CODE kalau di-enable dan ada nilai
                if (hasNoRef) {
                    log.info("approval [{}] >>> isikan filter no ref code: {}", req.getNoRefCounter(), noRefValue);
                    Locator noRefCode = frame.locator("#USER_REFERENCE_CODE");
                    noRefCode.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                    noRefCode.fill(noRefValue);
                } else {
                    log.info("approval [{}] >>> skip USER_REFERENCE_CODE (config/useNoRefFilter={}, value='{}')", req.getNoRefCounter(), useNoRefFilter, noRefValue);
                }
            }

            engineService.setLastUpdate(LocalDateTime.now());

            log.info("approval [{}] >>> click button permintaan data", req.getNoRefCounter());
            Locator findButton = frame.locator("#Cari3000-button");
            findButton.click(new Locator.ClickOptions().setTimeout(5000));

            driver.waitForLoadState(LoadState.LOAD);

            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("approval [{}] >>> interrupted after click Cari Data Untuk di setujui", req.getNoRefCounter(), e);
        } catch (Exception e) {
            log.error("approval [{}] >>> Fail to click button Cari Data Untuk di setujui", req.getNoRefCounter(), e);
        } finally {
            sLog.setName(FORM_APPROVAL);
            sLog.setAppId(req.getId());
            activityServices.stop(sLog);
        }
    }

    private void handleApprovalWithCaptcha(AppRequest req, List<DownloadBean> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            log.warn("approval [{}] >>> handleApprovalWithCaptcha dipanggil tapi ids kosong, skip", req.getNoRefCounter());
            return;
        }

        if (CRASH.equals(engineService.getCurrentStatusEngine())) {
            log.error("approval [{}] >>> engine status CRASH sebelum captcha, batalkan approval", req.getNoRefCounter());
            return;
        }

        fillCaptcha(req, ids.size());
        processSlikApproval(req, ids);
    }

    private void fillCaptcha(AppRequest req, int selectedCount) {
        var frame = webDriverService.getFrame("main");
        var sLog = activityServices.start(this.robotName);

        try {
            log.info("readTable [{}] >>> row selected: {}", req.getNoRefCounter(), selectedCount);
            String cap2 = captchaService.fetchCapcha2TensorPlaywright(frame);

            Locator cap = frame.locator("#captcha");
            cap.fill(cap2);

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("approval [{}] >>> error while sleeping before captcha submit", req.getNoRefCounter(), e);
            }
        } finally {
            sLog.setName(FORM_APPROVAL);
            sLog.setAppId(req.getId());
            activityServices.stop(sLog);
        }
    }

    private void processSlikApproval(AppRequest req, List<DownloadBean> ids) {
        if (!Boolean.TRUE.equals(processSlik)) {
            return;
        }

        var frame = webDriverService.getFrame("main");

        var sLog = activityServices.start(this.robotName);
        try {
            for (DownloadBean id : ids) {
                try {
                    log.info("approval >>> send request for noref: {}", id.getNoRefCounter());
                    String script = """
                            asyncCallContent('CI00350261', {
                                'MENU_ID': 81643,
                                'ACTION_TYPE_CODE': 'UP001',
                                '__ACTIONLOG': true,
                                '__KEY': 'APPROVED_FLAG,INQUIRY_ID',
                                '__BEFORE_APPROVE_FLAG': 'Y',
                                '__BEFORE_USER_REF_CODE': '%s',
                                'APPROVED_FLAG': 'Y',
                                'INQUIRY_ID': '%s'
                            });
                            """.formatted(id.getNoRefCounter(), id.getInqId());

                    frame.evaluate(script);

                    ProcessReportRequest pp = new ProcessReportRequest();
                    pp.setStatusCode(id.getNoRefCounter());

                    SubmitRequest sr = new SubmitRequest();
                    sr.setIdAppRequest(id.getId());
                    sr.setApprovalVariable("APPR");

                    log.info("readTable [{}] >>> set status approval done with payload: {}", req.getNoRefCounter(), pp);
                    var cl = m2mService.approvalDone(pp);
                    cl.execute();

                    log.info("readTable [{}] >>> callback with payload: {}", req.getNoRefCounter(), sr);
                    var cl2 = m2mService.processCallback(sr);
                    cl2.execute();
                } catch (IOException e) {
                    log.error("approval [{}] >>> error when calling M2M services for noref {}", req.getNoRefCounter(), id.getNoRefCounter(), e);
                }
            }
        } finally {
            sLog.setName(FORM_APPROVAL);
            sLog.setAppId(req.getId());
            activityServices.stop(sLog);
        }

        handlePostSubmitDialogs(req);
        verifySubmitWithScreenshot(req);
    }

    private void handlePostSubmitDialogs(AppRequest req) {
        var frame = webDriverService.getFrame("main");
        var sLog = activityServices.start(this.robotName);

        try {
            log.info("approval [{}] >>> check modal popup dan dialog setelah submit data ke slik", req.getNoRefCounter());

            if (commonProcessingService.isProgressModalVisible(frame)) {
                log.info("approval [{}] >>> Progress modal masih tampil akan di hilangkan secara aplikasi", req.getNoRefCounter());
                commonProcessingService.removeProgressModal(frame);
            }

            commonProcessingService.removeDialog(frame);
        } finally {
            sLog.setName(FORM_APPROVAL);
            sLog.setAppId(req.getId());
            activityServices.stop(sLog);
        }
    }

    private void verifySubmitWithScreenshot(AppRequest req) {
        log.info("approval [{}] >>> cross check modal popup dan dialog setelah submit data ke slik", req.getNoRefCounter());

        byte[] sc = commonProcessingService.screenshot();
        String stat = commonProcessingService.checkScreenshot(sc);
        log.info("approval [{}] >>> checkscreentshot {}", req.getNoRefCounter(), stat);

        if (!"NONE".equals(stat)) {
            log.info("approval [{}] >>> processing {} wrong action", req.getNoRefCounter(), req.getNoRefCounter());
            webDriverService.refresh();

            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("approval [{}] >>> Gagal sleep2", req.getNoRefCounter(), e);
            }

            byte[] ss = commonProcessingService.screenshot();
            stat = commonProcessingService.checkScreenshot(ss);

            if (!"NONE".equals(stat)) {
                engineService.setCurrentStatusEngine(CRASH);
            }
        }
    }

    private ApiResponse<Long> isDataNeedToBeApprovedVRemote(String code) {
        var wrap = new ApiResponse<Long>();
        wrap.setStatus(false);

        try {
            log.info("approval >>> check data for approval: {}", code);

            var pp = new ProcessReportRequest();
            pp.setStatusCode(code);

            var call = m2mService.approvalStart(pp);
            var res = call.execute();

            if (!res.isSuccessful() || res.body() == null) {
                log.error("approval >>> approvalStart failed for code {} with httpStatus {} and message {}", code, res.code(), res.message());
                return wrap;
            }

            return res.body();
        } catch (IOException e) {
            log.error("approval >>> IOException when calling approvalStart for code {}", code, e);
            return wrap;
        }
    }

    private int getTotalPages(AppRequest req, Locator resultList) {
        try {
            Locator pageArea = resultList.locator("#pageArea");
            if (pageArea.count() == 0) {
                log.info("approval [{}] >>> pageArea tidak ditemukan, diasumsikan hanya 1 halaman",
                        req.getNoRefCounter());
                return 1;
            }

            Locator pages = pageArea.locator("div.dhx_page, div.dhx_page_active");
            int count = pages.count();
            if (count == 0) {
                log.info("approval [{}] >>> pageArea ada tapi tidak ada dhx_page, diasumsikan hanya 1 halaman",
                        req.getNoRefCounter());
                return 1;
            }

            int maxPage = 1;
            for (int i = 0; i < count; i++) {
                Locator candidate = pages.nth(i);
                String label = candidate.textContent();
                if (label == null) {
                    continue;
                }
                String trimmed = label.trim();
                // hanya ambil yang murni angka
                if (trimmed.matches("\\d+")) {
                    int pageNum = Integer.parseInt(trimmed);
                    if (pageNum > maxPage) {
                        maxPage = pageNum;
                    }
                }
            }

            log.info("approval [{}] >>> jumlah halaman numeric di pageArea: {} (raw count: {})",
                    req.getNoRefCounter(), maxPage, count);
            return maxPage;
        } catch (Exception e) {
            log.warn("approval [{}] >>> gagal membaca jumlah halaman, fallback ke 1 halaman",
                    req.getNoRefCounter(), e);
            return 1;
        }
    }

    private boolean goToPage(AppRequest req, int pageNumber, Locator resultList) {
        var driver = webDriverService.getDriver();
        boolean success = false;

        try {
            log.info("approval [{}] >>> pindah ke halaman {}", req.getNoRefCounter(), pageNumber);

            Locator pageArea = resultList.locator("#pageArea");
            Locator pages = pageArea.locator("div.dhx_page, div.dhx_page_active");
            int pageCount = pages.count();
            if (pageCount == 0) {
                log.warn("approval [{}] >>> pagination tidak ditemukan saat pindah ke halaman {}", req.getNoRefCounter(), pageNumber);
                return false;
            }

            Locator target = null;
            for (int i = 0; i < pageCount; i++) {
                Locator candidate = pages.nth(i);
                String label = candidate.textContent();
                if (label != null && label.trim().equals(String.valueOf(pageNumber))) {
                    target = candidate;
                    break;
                }
            }

            if (target == null) {
                log.warn("approval [{}] >>> tombol untuk halaman {} tidak ditemukan, stop iterasi halaman", req.getNoRefCounter(), pageNumber);
                return false;
            }

            target.click(new Locator.ClickOptions().setTimeout(5000));
            driver.waitForLoadState(LoadState.LOAD);
            Thread.sleep(1000L);

            success = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("approval [{}] >>> interrupted saat pindah ke halaman {}", req.getNoRefCounter(), pageNumber, e);
            engineService.setCurrentStatusEngine(CRASH);
        } catch (Exception e) {
            log.error("approval [{}] >>> gagal pindah ke halaman {}", req.getNoRefCounter(), pageNumber, e);
            engineService.setCurrentStatusEngine(CRASH);
        }

        return success;
    }

    private List<DownloadBean> readResultTableAndCollectIds(AppRequest req) {
        var frame = webDriverService.getFrame("main");
        var sLog = activityServices.start(this.robotName);
        List<DownloadBean> ids = new ArrayList<>();

        try {
            log.info("approval [{}] >>> find table resultlist", req.getNoRefCounter());
            Locator resultList = frame.locator("#resultList");
            if (resultList == null || resultList.count() == 0) {
                log.error("approval [{}] >>> resultList tidak ditemukan", req.getNoRefCounter());
                engineService.setCurrentStatusEngine(CRASH);
                return ids;
            }

            int totalPages = getTotalPages(req, resultList);
            log.info("approval [{}] >>> total halaman di resultlist: {}", req.getNoRefCounter(), totalPages);

            boolean shouldStop = false;
            for (int page = 1; page <= totalPages && !shouldStop; page++) {

                if (page > 1) {
                    boolean moved = goToPage(req, page, resultList);
                    if (!moved) {
                        shouldStop = true;
                    }
                }

                if (!shouldStop) {
                    readCurrentPageRows(req, resultList, ids);

                    if (CRASH.equals(engineService.getCurrentStatusEngine())) {
                        log.error("approval [{}] >>> status engine CRASH saat membaca halaman {}", req.getNoRefCounter(), page);
                        shouldStop = true;
                    }
                }
            }

            log.info("approval [{}] >>> total row yang terpilih untuk approve (semua halaman): {}", req.getNoRefCounter(), ids.size());
            return ids;
        } catch (Exception e) {
            log.error("approval [{}] >>> Fail to read table resultlist", req.getNoRefCounter(), e);
            engineService.setCurrentStatusEngine(CRASH);
            return ids;
        } finally {
            sLog.setName(FORM_APPROVAL);
            sLog.setAppId(req.getId());
            activityServices.stop(sLog);
        }
    }

    private void readCurrentPageRows(AppRequest req, Locator resultList, List<DownloadBean> ids) {
        Locator tables = resultList.locator("table");
        int tableCount = tables.count();
        if (tableCount < 1) {
            log.error("approval [{}] >>> table result tidak ditemukan di halaman ini", req.getNoRefCounter());
            engineService.setCurrentStatusEngine(CRASH);
            return;
        }

        int tableIndex = tableCount > 1 ? 1 : 0;
        Locator table = tables.nth(tableIndex);
        Locator rows = table.locator("tbody > tr");

        int rowCount = rows.count();
        if (rowCount < 1) {
            log.warn("approval [{}] >>> tidak ada row data di halaman ini", req.getNoRefCounter());
            return;
        }

        log.info("approval [{}] >>> row di table result (halaman ini): {}", req.getNoRefCounter(), rowCount);

        for (int i = 0; i < rowCount; i++) {
            if (CRASH.equals(engineService.getCurrentStatusEngine())) {
                log.error("approval [{}] >>> status engine CRASH saat proses row index {}", req.getNoRefCounter(), i);
                return;
            }

            Locator row = rows.nth(i);

            if (!row.isVisible()) {
                log.debug("approval [{}] >>> row index {} tidak visible, skip", req.getNoRefCounter(), i);
            } else {
                Locator cols = row.locator("td");
                int colCount = cols.count();

                if (colCount < 10) {
                    log.warn("approval [{}] >>> row index {} hanya {} kolom (< 10), skip", req.getNoRefCounter(), i, colCount);
                } else {
                    log.info("approval [{}] >>> process row index {} dengan {} kolom", req.getNoRefCounter(), i, colCount);
                    processRow(req, cols, ids);
                }
            }
        }
    }

    private void processRow(AppRequest req, Locator cols, List<DownloadBean> ids) {
        String code = cols.nth(1).textContent();    // USER_REFERENCE_CODE / no ref
        String inqId = cols.nth(8).innerHTML();     // INQUIRY_ID / id SLIK
        Locator ckbCell = cols.nth(9);              // kolom checkbox

        var selId = this.isDataNeedToBeApprovedVRemote(code);

        if (!isValidSelId(selId)) {
            log.info("readTable [{}] >>> skip, noRef {} tidak ditemukan / tidak perlu approve",
                    req.getNoRefCounter(), code);
            ensureRowUnchecked(req, ckbCell, code);
            return;
        }

        ensureRowChecked(req, ckbCell, code);

        DownloadBean idm = buildDownloadBean(selId, code, inqId);
        log.info("readTable [{}] >>> noRef {} match dengan data CBAS, row tetap terpilih",
                req.getNoRefCounter(), code);
        ids.add(idm);
    }

    private boolean isValidSelId(ApiResponse<Long> selId) {
        return selId != null
               && Boolean.TRUE.equals(selId.getStatus())
               && selId.getData() != null
               && !selId.getData().isEmpty();
    }

    private void ensureRowUnchecked(AppRequest req, Locator ckbCell, String code) {
        try {
            if (ckbCell == null || !ckbCell.isVisible()) {
                return;
            }

            Locator checkbox = ckbCell.locator("input[type='checkbox']");
            if (checkbox.count() == 0) {
                return;
            }

            Locator cb = checkbox.first();
            if (cb.isChecked()) {
                cb.uncheck(new Locator.UncheckOptions().setTimeout(2000));
                log.info("readTable [{}] >>> pastikan row noRef {} tidak dicentang", req.getNoRefCounter(), code);
            }
        } catch (Exception e) {
            log.warn("readTable [{}] >>> gagal memastikan uncheck row untuk noRef {}", req.getNoRefCounter(), code, e);
        }
    }

    private void ensureRowChecked(AppRequest req, Locator ckbCell, String code) {
        try {
            if (ckbCell == null || !ckbCell.isVisible()) {
                return;
            }

            Locator checkbox = ckbCell.locator("input[type='checkbox']");
            if (checkbox.count() > 0) {
                Locator cb = checkbox.first();
                if (!cb.isChecked()) {
                    cb.check(new Locator.CheckOptions().setTimeout(2000));
                    log.info("readTable [{}] >>> ceklis row untuk noRef {}", req.getNoRefCounter(), code);
                }
            } else {
                ckbCell.click(new Locator.ClickOptions().setTimeout(2000));
                log.info("readTable [{}] >>> klik cell checkbox untuk noRef {}", req.getNoRefCounter(), code);
            }
        } catch (Exception e) {
            log.error("readTable [{}] >>> gagal ceklis row untuk noRef {}", req.getNoRefCounter(), code, e);
        }
    }

    private DownloadBean buildDownloadBean(ApiResponse<Long> selId, String code, String inqId) {
        DownloadBean idm = new DownloadBean();
        idm.setId(selId.getData().get(0));
        idm.setNoRefCounter(code);
        idm.setInqId(inqId);
        return idm;
    }

}
