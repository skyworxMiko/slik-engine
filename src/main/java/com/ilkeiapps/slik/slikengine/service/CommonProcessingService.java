package com.ilkeiapps.slik.slikengine.service;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.ilkeiapps.slik.slikengine.bean.PlaywrigthProcessResponse;
import com.ilkeiapps.slik.slikengine.entity.EngineConfig;
import com.ilkeiapps.slik.slikengine.entity.QEngineConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommonProcessingService {

    private final EntityManager em;

    private final CriteriaBuilderFactory configBuilder;

    private final PlaywrightDriverService webDriverService;

    private final CaptchaService captchaService;

    private final EngineService engineService;

    @Value("${cbas.slik.profile.requestor}")
    private Boolean isProfileRequestor;

    private static final String OPENCV_UN1 = "opencv/un1.png";
    private static final String OPENCV_UN2 = "opencv/un2.png";
    private static final String OPENCV_UN3 = "opencv/un3.png";
    private static final String OPENCV_CAP3 = "opencv/cap3.png";
    private static final String OPENCV_CP3 = "opencv/cp3.png";
    private static final String OPENCV_SESS1 = "opencv/sess1.png";
    private static final String OPENCV_SESS2 = "opencv/sess2.png";
    private static final String OPENCV_HTTP1 = "opencv/http1.png";
    private static final String OPENCV_LIMA1 = "opencv/lima1.png";
    private static final String OPENCV_RES1 = "opencv/res1.png";
    private static final String OPENCV_RES2 = "opencv/res2.png";
    private static final String OPENCV_RES3 = "opencv/res3.png";
    private static final String OPENCV_SIT1 = "opencv/sit1.png";
    private static final String OPENCV_LOGIN = "opencv/login.png";
    private static final String OPENCV_LOGIN1 = "opencv/login1.png";
    private static final String SESS1 = "SESS1";
    private static final String SESS2 = "SESS2";
    private static final String UTD1 = "UTD1";
    private static final String UTD2 = "UTD2";
    private static final String UTD3 = "UTD3";
    private static final String CAP = "CAP";
    private static final String HTTP = "HTTP";
    private static final String LIMA = "LIMA";
    private static final String RESET = "RESET";
    private static final String RESET2 = "RESET2";
    private static final String RESET3 = "RESET3";
    private static final String SITE = "SITE";
    private static final String LOGIN = "LOGIN";
    private static final String NONE = "NONE";
    private static final String CHECK_UTD = "checkUtd >>> path: ";
    private static final String EL_REMOVE = "el => el.remove()";
    private static final String EL_DIALOG_DRAGGABLE = "div.ui-dialog.ui-widget.ui-widget-content.ui-corner-all.ui-front.ui-draggable.ui-resizable";
    private static final String LOGIN_CAPTCHA_NOT_MATCH_MSG = "Teks Captcha tidak sama";
    private static final int LOGIN_MAX_CAPTCHA_RETRY = 3;
    private static final long SCREENSHOT_TIMEOUT_LOG_COOLDOWN_MS = 60_000;
    private volatile long lastScreenshotTimeoutLog = 0L;
    private static final int LOGIN_FILL_TIMEOUT_MS = 30_000;
    private static final String LOGIN_ID_SELECTOR = "input[data-validation-label='ID']";
    private static final String LOGIN_PASSWORD_SELECTOR = "#password";

    public PlaywrigthProcessResponse processLogin(int mode) {
        log.info("processLogin >>> start dengan mode {}", mode);

        PlaywrigthProcessResponse response = createDefaultResponse();

        initProfileRequestorFlag();

        Page pageDriver = webDriverService.getDriver();
        log.info("processLogin >>> getDriver");

        List<EngineConfig> configs = loadLoginConfigs();
        fillCredentials(pageDriver, configs, mode);

        if (!performLoginWithCaptchaRetry(pageDriver, response)) {
            return response;
        }

        if (!completePostLoginFlow(pageDriver, response)) {
            return response;
        }

        response.setResult(true);
        return response;
    }

    private boolean performLoginWithCaptchaRetry(Page pageDriver, PlaywrigthProcessResponse response) {
        int attempt = 1;
        boolean captchaResolved = false;

        while (attempt <= LOGIN_MAX_CAPTCHA_RETRY && !captchaResolved) {
            log.info("processLogin >>> login attempt {}", attempt);

            String captchaText = fetchCaptcha(pageDriver, response);
            if (captchaText == null) {
                return false;
            }

            if (!fillCaptchaAndClickLogin(pageDriver, response, captchaText)) {
                return false;
            }

            waitAfterLogin(pageDriver);

            Frame frameAfterLogin = webDriverService.getFrame("main");
            boolean captchaError = frameAfterLogin != null
                                   && checkDialogContainText(frameAfterLogin, LOGIN_CAPTCHA_NOT_MATCH_MSG);

            if (captchaError) {
                handleCaptchaErrorDuringLogin(frameAfterLogin, attempt);
                if (attempt == LOGIN_MAX_CAPTCHA_RETRY) {
                    return false;
                }
                attempt = attempt + 1;
            } else {
                captchaResolved = true;
            }
        }

        return captchaResolved;
    }

    private void handleCaptchaErrorDuringLogin(Frame frameAfterLogin, int attempt) {
        log.warn("processLogin >>> captcha tidak sama pada attempt {}", attempt);
        clickOkButtonInFrame(frameAfterLogin);
    }

    private boolean completePostLoginFlow(Page pageDriver, PlaywrigthProcessResponse response) {
        if (!checkLoginErrorMessages(pageDriver, response)) {
            return false;
        }

        Frame welcomeFrame = processWelcomeFrame(pageDriver, response);
        if (welcomeFrame == null) {
            return false;
        }

        Frame finalFrame = switchToRequestorRoleIfNeeded(pageDriver, welcomeFrame, response);
        if (finalFrame == null) {
            return false;
        }

        webDriverService.setFrame(finalFrame);
        return true;
    }

    private PlaywrigthProcessResponse createDefaultResponse() {
        PlaywrigthProcessResponse response = new PlaywrigthProcessResponse();
        response.setResult(false);
        return response;
    }

    private void initProfileRequestorFlag() {
        if (isProfileRequestor == null) {
            log.info("processLogin >>> profile requestor adalah null, set ke true");
            isProfileRequestor = true;
        }
    }

    private List<EngineConfig> loadLoginConfigs() {
        QEngineConfig qsg = new QEngineConfig("o");
        return configBuilder.create(em, EngineConfig.class)
                .from(EngineConfig.class, qsg.getMetadata().getName())
                .where(qsg.code.toString()).in("SLKU", "SLKP", "SSLKU", "SSLKP")
                .getResultList();
    }

    private void fillCredentials(Page pageDriver, List<EngineConfig> configs, int mode) {
        String userCode = (mode == 1) ? "SLKU" : "SSLKU";
        String passCode = (mode == 1) ? "SLKP" : "SSLKP";

        String userValue = findConfigValue(configs, userCode);
        String passValue = findConfigValue(configs, passCode);

        Locator userLocator = pageDriver.locator(LOGIN_ID_SELECTOR);
        Locator passLocator = pageDriver.locator(LOGIN_PASSWORD_SELECTOR);

        fillWithLogging(userLocator, userValue, "ID");
        fillWithLogging(passLocator, passValue, "PASSWORD");
    }

    private void fillWithLogging(Locator locator, String value, String fieldName) {
        try {
            locator.fill(
                    value,
                    new Locator.FillOptions().setTimeout(LOGIN_FILL_TIMEOUT_MS)
            );
        } catch (PlaywrightException e) {
            if (e instanceof TimeoutError timeoutError) { // pattern matching instanceof
                log.error(
                        "fillCredentials >>> timeout {} ms saat mengisi field {}: {}",
                        LOGIN_FILL_TIMEOUT_MS,
                        fieldName,
                        timeoutError.getMessage(),
                        timeoutError
                );
            } else {
                log.error(
                        "fillCredentials >>> Playwright error saat mengisi field {}: {}",
                        fieldName,
                        e.getMessage(),
                        e
                );
            }
            throw e;
        }
    }

    private String findConfigValue(List<EngineConfig> configs, String code) {
        return configs.stream()
                .filter(f -> code.equals(f.getCode()))
                .findFirst()
                .map(EngineConfig::getValue)
                .orElseThrow(() -> new IllegalStateException("Config " + code + " tidak ditemukan"));
    }

    private String fetchCaptcha(Page driver, PlaywrigthProcessResponse wrap) {
        log.info("processLogin >>> mulai proses cek captcha...");
        log.debug("processLogin >>> driver status: {}", "initialized");
        log.debug("processLogin >>> captchaService instance: {}", captchaService != null ? "ready" : "null");

        try {
            log.info("processLogin >>> memanggil captchaService.fetchCapchaTensorPlaywright()...");
            if (captchaService == null) {
                log.error("processLogin >>> captchaService null");
                wrap.setMessage("Captcha service tidak tersedia");
                return null;
            }

            String cp = captchaService.fetchCapchaTensorPlaywright(driver);

            if (cp == null || cp.isBlank()) {
                log.error("processLogin >>> Captcha service tidak mengembalikan hasil (cp == null / blank)");
                wrap.setMessage("Captcha service tidak tersedia");
                return null;
            }

            log.info("processLogin >>> Captcha berhasil diambil: [{}] (length={})", cp, cp.length());
            return cp;
        } catch (Exception ex) {
            log.error("processLogin >>> ERROR saat fetchCapchaTensorPlaywright", ex);
            wrap.setMessage("Captcha service error: " + ex.getMessage());
            return null;
        }
    }

    private boolean fillCaptchaAndClickLogin(Page driver, PlaywrigthProcessResponse wrap, String cp) {
        try {
            log.info("processLogin >>> mengisi captcha ke input field...");
            Locator cap = driver.locator("#captcha-answer");
            log.debug("processLogin >>> locator captcha-answer: {}", cap != null ? "found" : "not found");

            if (cap == null) {
                wrap.setMessage("Field captcha tidak ditemukan");
                return false;
            }

            cap.fill(cp);
            log.info("processLogin >>> captcha diisi dengan sukses");
            log.info("processLogin >>> menyimpan data untuk ML...");
            log.info("processLogin >>> klik tombol login...");

            Locator login = driver.locator("#login-button");
            log.debug("processLogin >>> locator login-button: {}", login != null ? "found" : "not found");

            if (login == null) {
                wrap.setMessage("Tombol login tidak ditemukan");
                return false;
            }

            login.click(new Locator.ClickOptions().setTimeout(3000));
            log.info("processLogin >>> klik login sukses, menunggu hasil...");
            return true;
        } catch (Exception e) {
            log.error("processLogin >>> ERROR saat memproses captcha / klik login", e);
            wrap.setMessage("Login gagal: " + e.getMessage());
            return false;
        }
    }

    private void waitAfterLogin(Page driver) {
        log.info("load0 start");
        driver.waitForLoadState(LoadState.LOAD);
        log.info("load0 end");

        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("processLogin >>> sleep error", e);
        }
    }

    private boolean checkLoginErrorMessages(Page driver, PlaywrigthProcessResponse wrap) {
        if (driver == null) {
            log.error("processLogin >>> driver null saat cek login");
            wrap.setMessage("Driver tidak tersedia");
            return false;
        }

        try {
            Locator fifty = driver.getByText("Tidak dapat login");
            if (fifty != null && fifty.isVisible()) {
                log.error("processLogin >>> Tidak dapat login karena session 50 menit");
                wrap.setMessage("Tidak dapat login karena session 50 menit");
                return false;
            }

            Locator text = driver.getByText("Teks Captcha");
            if (text != null && text.isVisible()) {
                log.error("processLogin >>> Tidak dapat login karena text captcha tidak sama");
                wrap.setMessage("Tidak dapat login karena text captcha");
                return false;
            }
        } catch (PlaywrightException e) {
            log.warn("processLogin >>> gagal cek pesan error login: {}", e.getMessage());
        }

        log.info("processLogin >>> small delay after login");
        driver.waitForTimeout(3000);
        log.info("processLogin >>> cek frame");
        return true;
    }

    private Frame processWelcomeFrame(Page driver, PlaywrigthProcessResponse wrap) {
        try {
            List<Frame> frames = driver.frames();
            if (frames.isEmpty()) {
                log.error("processLogin >>> Frame tidak tersedia");
                wrap.setMessage("Tidak dapat frame");
                return null;
            }

            Frame frame = driver.frame("main");
            Locator welcome = frame.getByText("Yth. Bapak/IbuPejabat dan/atau Petugas Pelaksana SLIK,");

            if (welcome == null || !welcome.isVisible()) {
                log.error("processLogin >>> Tulisan pembuka tidak terdeteksi");
                wrap.setMessage("Tidak dapat login");
                return null;
            }

            Locator okb = frame.locator("button[class$='btn-primary']");
            okb.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            if (okb.isEnabled() && okb.isVisible()) {
                log.info("processLogin >>> click Tulisan pembuka OK button");
                okb.click(new Locator.ClickOptions().setTimeout(5000));
            }

            return frame;
        } catch (Exception e) {
            log.error("processLogin >>> Frame prosessing gagal: {}", e.getMessage());
            wrap.setMessage("Frame prosessing gagal: " + e.getMessage());
            return null;
        }
    }

    private Frame switchToRequestorRoleIfNeeded(Page driver, Frame currentFrame, PlaywrigthProcessResponse wrap) {
        if (Boolean.TRUE.equals(isProfileRequestor)) {
            return currentFrame;
        }

        String rrole = engineService.getRole();
        log.info("processLogin >>> cek role: {}", rrole);

        if (!"REQ".equals(rrole)) {
            return currentFrame;
        }

        driver.navigate("https://slik.ojk.go.id/slik/authentication/change-group/15");
        driver.waitForLoadState(LoadState.LOAD);

        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("processLogin >>> sleep error", e);
        }

        List<Frame> frames = driver.frames();
        if (frames.isEmpty()) {
            log.error("processLogin >>> Tidak dapat frame");
            wrap.setMessage("Tidak dapat frame");
            return null;
        }

        return driver.frame("main");
    }

    public byte[] screenshot() {
        Page driver = webDriverService.getDriver();
        if (driver == null) {
            log.warn("screenshot >>> driver null, tidak bisa mengambil screenshot");
            return new byte[0];
        }

        if (driver.isClosed()) {
            log.warn("screenshot >>> driver sudah closed, skip ambil screenshot");
            return new byte[0];
        }

        try {
            return driver.screenshot(new Page.ScreenshotOptions().setFullPage(true).setTimeout(5000.0));
        } catch (PlaywrightException e) {
            handlePlaywrightScreenshotException(e);
        } catch (Exception e) {
            log.error("screenshot >>> gagal mengambil screenshot (non-Playwright exception)", e);
        }

        return new byte[0];
    }

    private void handlePlaywrightScreenshotException(PlaywrightException e) {
        String msg = e.getMessage();

        if (msg == null) {
            log.error("screenshot >>> gagal mengambil screenshot (PlaywrightException tanpa message)", e);
            return;
        }

        if (isTimeoutMessage(msg)) {
            logScreenshotTimeout(msg);
            return;
        }

        if (isTargetClosedMessage(msg)) {
            String shortMsg = shortenPlaywrightMessage(msg);
            log.warn("screenshot >>> browser/page sudah closed (TargetClosedError), skip ambil screenshot. message={}", shortMsg);
            return;
        }

        log.error("screenshot >>> gagal mengambil screenshot", e);
    }

    private boolean isTimeoutMessage(String msg) {
        return msg.contains("Timeout");
    }

    private boolean isTargetClosedMessage(String msg) {
        return msg.contains("Target page, context or browser has been closed");
    }

    private void logScreenshotTimeout(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastScreenshotTimeoutLog > SCREENSHOT_TIMEOUT_LOG_COOLDOWN_MS) {
            lastScreenshotTimeoutLog = now;
            log.warn("screenshot >>> timeout saat ambil screenshot, diabaikan: {}", msg);
        } else {
            log.debug("screenshot >>> timeout (sudah dilaporkan kurang dari 1 menit yang lalu)");
        }
    }

    private String shortenPlaywrightMessage(String msg) {
        if (msg == null) {
            return null;
        }

        int stackIdx = msg.indexOf("stack='");
        if (stackIdx > 0) {
            return msg.substring(0, stackIdx).trim();
        }

        int newlineIdx = msg.indexOf('\n');
        if (newlineIdx > 0) {
            return msg.substring(0, newlineIdx).trim();
        }

        return msg.trim();
    }

    public void doRefresh() {
        log.info("doRefresh >>> start");

        var frame = webDriverService.getFrame("main");

        try {
            Locator homeMenu = frame.locator("#top1menu");
            if (homeMenu.isVisible() && homeMenu.isEnabled()) {
                log.info("doRefresh >>> link home found");

                Locator klikBeranda = frame.locator("xpath=/html/body/div/div[1]/div/a[1]");
                klikBeranda.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
                log.info("doRefresh >>> link home is visible and will clicked");
                klikBeranda.click();
            } else {
                log.warn("doRefresh >>> link home NOT found, restarting");
                webDriverService.restart();
            }
        } catch (Exception e) {
            log.error("doRefresh >>> link home not found");
        }
    }

    public boolean isProgressModalVisible(Frame frame) {
        log.info("checkProgressBar >>> start");

        if (frame == null) {
            log.warn("checkProgressBar >>> frame is null, skip check");
            return false;
        }

        for (int i = 0; i < 3; i++) {
            log.info("checkProgressBar >>> check #{}", i);
            if (isProgressModalVisibleOnce(frame)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("checkProgressBar >>> interrupted while sleeping", e);
                return false;
            }
        }

        log.info("checkProgressBar >>> max attempts reached, return false");
        return false;
    }

    private boolean isProgressModalVisibleOnce(Frame frame) {
        try {
            Locator modal = frame.locator("#rcbBlockLayer_slik-ui");
            int count = modal.count();

            if (count == 0) {
                log.info("checkProgressBar >>> modal not found, result=false");
                return false;
            }

            Locator first = modal.first();
            boolean visible = first.isVisible();
            boolean enabled = first.isEnabled(new Locator.IsEnabledOptions().setTimeout(0));
            boolean result = visible && enabled;
            log.info("checkProgressBar >>> modal visible={}, enabled={}, result={}", visible, enabled, result);

            return result;
        } catch (PlaywrightException e) {
            log.warn("checkProgressBar >>> PlaywrightException: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean removeProgressModal(Frame frame) {
        log.info("removeProgressModal >>> start");

        if (frame == null) {
            log.warn("removeProgressModal >>> frame null, skip");
            return false;
        }

        try {
            boolean blockRemoved = removeLayer(frame, "#rcbBlockLayer_slik-ui", "rcbBlockLayer_slik-ui");
            boolean modalRemoved = removeLayer(frame, "#rcbModalLayer_slik-ui", "rcbModalLayer_slik-ui");

            if (blockRemoved || modalRemoved) {
                log.info("removeProgressModal >>> removed layer(s) block={}, modal={}", blockRemoved, modalRemoved);
            } else {
                log.info("removeProgressModal >>> no progress modal/layer found");
            }

            return blockRemoved || modalRemoved;

        } catch (TimeoutError e) {
            // Timeout ketika cek/handle layer: anggap saja tidak ada progress modal
            log.warn("removeProgressModal >>> timeout while handling progress modal, assume none: {}",
                    e.getMessage());
            return false;
        } catch (PlaywrightException e) {
            log.warn("removeProgressModal >>> Playwright error while handling progress modal: {}",
                    e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("removeProgressModal unexpected exception >>> {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean removeLayer(Frame frame, String selector, String layerName) {
        if (frame == null) {
            log.warn("removeProgressModal >>> frame null, skip remove {}", layerName);
            return false;
        }

        try {
            Object removedCountObj = frame.evaluate(
                    "sel => {" +
                    "  const nodes = document.querySelectorAll(sel);" +
                    "  let count = 0;" +
                    "  nodes.forEach(n => { n.remove(); count++; });" +
                    "  return count;" +
                    "}",
                    selector
            );

            int removedCount = 0;
            if (removedCountObj instanceof Number countNumber) {
                removedCount = countNumber.intValue();
            }

            if (removedCount > 0) {
                log.info("removeProgressModal >>> removed {} element(s) for {}", removedCount, layerName);
                return true;
            }

            log.info("removeProgressModal >>> no element removed for {}", layerName);
            return false;

        } catch (PlaywrightException e) {
            log.warn("removeProgressModal >>> error removing {}: {}", layerName, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("removeProgressModal >>> unexpected error while removing {}: {}",
                    layerName, e.getMessage(), e);
            return false;
        }
    }

    public void removeDialog(Frame frame) {
        log.info("removeDialog >>> start");
        try {
            Locator dialogs = frame.locator(EL_DIALOG_DRAGGABLE);

            int count = dialogs.count();
            if (count == 0) {
                log.info("removeDialog >>> no dialog found, skip");
                return;
            }

            boolean removed = false;
            for (int i = 0; i < count; i++) {
                Locator dialog = dialogs.nth(i);
                if (dialog.isVisible() && dialog.isEnabled()) {
                    log.info("removeDialog >>> found visible dialog at index {}", i);
                    frame.evaluate(EL_REMOVE, dialog);
                    log.info("removeDialog >>> dialog removed");
                    removed = true;
                    break;
                }
            }

            if (!removed) {
                log.info("removeDialog >>> dialogs exist but none visible/enabled, skip");
            } else {
                removeOverlayIfPresent(frame);
            }

        } catch (TimeoutError e) {
            log.warn("removeDialog >>> timeout while handling dialog, probably no visible dialog", e);
        } catch (PlaywrightException e) {
            log.error("removeDialog >>> Playwright error", e);
        } catch (Exception e) {
            log.error("removeDialog >>> unexpected error", e);
        }
    }

    private void removeOverlayIfPresent(Frame frame) {
        Locator overlay = frame.locator("div.ui-widget-overlay.ui-front");
        if (overlay != null && overlay.count() > 0 && overlay.isVisible()) {
            frame.evaluate(EL_REMOVE, overlay);
            log.info("removeDialog >>> Overlay removed");
        } else {
            log.info("removeDialog >>> No overlay found");
        }
    }

    public boolean checkDialogContainText(Frame frame, String text) {
        log.info("checkDialogContainText >>> start");
        try {
            if (frame == null) {
                log.warn("checkDialogContainText >>> frame is null, skip");
                return false;
            }

            Locator dialogs = frame.locator(
                    EL_DIALOG_DRAGGABLE
            );

            int count = dialogs.count();
            if (count == 0) {
                log.info("checkDialogContainText >>> no dialog found");
                return false;
            }

            String needle = text == null ? "" : text.toLowerCase();

            for (int i = 0; i < count; i++) {
                Locator dialog = dialogs.nth(i);
                if (!dialog.isVisible()) {
                    continue;
                }

                String dialogText = dialog.innerText();
                log.info("checkDialogContainText >>> dialog[{}] text: {}", i, dialogText);

                if (dialogText != null && dialogText.toLowerCase().contains(needle)) {
                    log.info("checkDialogContainText >>> dialog contains '{}'", text);
                    return true;
                }
            }

        } catch (PlaywrightException e) {
            log.warn("checkDialogContainText >>> PlaywrightException: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("checkDialogContainText >>> unexpected error: {}", e.getMessage(), e);
        }

        return false;
    }

    public boolean clickOkButtonInFrame(Frame frame) {
        log.info("clickOkButtonInFrame >>> start");
        try {
            Locator dialogs = frame.locator("div.ui-dialog.ui-widget.ui-widget-content.ui-corner-all.ui-front");
            int dlgCount = dialogs.count();
            if (dlgCount == 0) {
                log.info("clickOkButtonInFrame >>> no dialog found, nothing to click");
                return false;
            }

            Locator dialog = dialogs.first();
            Locator okButtons = dialog.locator("button.btn.btn-primary").filter(new Locator.FilterOptions().setHasText("OK"));

            int okCount = okButtons.count();
            if (okCount == 0) {
                log.info("clickOkButtonInFrame >>> no OK button inside dialog, skip");
                return false;
            }

            Locator okButton = okButtons.first();
            if (!okButton.isVisible()) {
                log.info("clickOkButtonInFrame >>> OK button not visible, skip");
                return false;
            }

            okButton.click(new Locator.ClickOptions().setTimeout(2000));
            log.info("clickOkButtonInFrame >>> OK clicked");
            return true;

        } catch (TimeoutError e) {
            log.warn("clickOkButtonInFrame >>> timeout while clicking OK, probably not enabled", e);
            return false;
        } catch (Exception e) {
            log.error("clickOkButtonInFrame >>> unexpected error click ok button", e);
            return false;
        }
    }

    public boolean isUtd() {
        var sc = this.screenshot();
        var found = this.checkUtd(OPENCV_UN1, sc, 0.9f);
        if (!found) {
            return this.checkUtd(OPENCV_UN2, sc, 0.9f);
        }
        return true;
    }

    public boolean isCaptcha() {
        var sc = this.screenshot();
        return this.checkUtd(OPENCV_CAP3, sc, 0.9f);
    }

    public boolean isSession() {
        var sc = this.screenshot();
        var found = this.checkUtd(OPENCV_SESS1, sc, 0.9f);
        if (!found) {
            return this.checkUtd(OPENCV_SESS2, sc, 0.9f);
        }
        return true;
    }

    public String checkUtdCap(byte[] sc) {
        var found = this.checkUtd(OPENCV_UN1, sc, 0.75f);
        if (found) {
            return UTD1;
        }

        found = this.checkUtd(OPENCV_UN2, sc, 0.75f);
        if (found) {
            return UTD2;
        }

        found = this.checkUtd(OPENCV_UN3, sc, 0.75f);
        if (found) {
            return UTD3;
        }

        found = this.checkUtd(OPENCV_CP3, sc, 0.8f);
        if (found) {
            return CAP;
        }

        return NONE;
    }

    public String checkScreenshot(byte[] sc) {
        if (sc == null || sc.length == 0) {
            log.warn("checkScreenshot >>> screenshot kosong, skip deteksi UTD");
            return NONE;
        }

        if (this.checkUtd(OPENCV_SESS1, sc, 0.75f)) {
            return SESS1;
        }
        if (this.checkUtd(OPENCV_SESS2, sc, 0.75f)) {
            return SESS2;
        }
        if (this.checkUtd(OPENCV_CP3, sc, 0.75f)) {
            return CAP;
        }
        if (this.checkUtd(OPENCV_UN1, sc, 0.75f)) {
            return UTD1;
        }
        if (this.checkUtd(OPENCV_UN2, sc, 0.75f)) {
            return UTD2;
        }
        if (this.checkUtd(OPENCV_HTTP1, sc, 0.75f)) {
            return HTTP;
        }
        if (this.checkUtd(OPENCV_LIMA1, sc, 0.75f)) {
            return LIMA;
        }
        if (this.checkUtd(OPENCV_RES1, sc, 0.75f)) {
            return RESET;
        }
        if (this.checkUtd(OPENCV_RES2, sc, 0.75f)) {
            return RESET2;
        }
        if (this.checkUtd(OPENCV_RES3, sc, 0.75f)) {
            return RESET3;
        }
        if (this.checkUtd(OPENCV_SIT1, sc, 0.75f)) {
            return SITE;
        }
        if (this.checkUtd(OPENCV_LOGIN, sc, 0.75f)) {
            return LOGIN;
        }
        if (this.checkUtd(OPENCV_LOGIN1, sc, 0.75f)) {
            return LOGIN;
        }

        return NONE;
    }

    private boolean checkUtd(String path, byte[] sc, float threshHold) {
        log.info("{}{}", CHECK_UTD, path);

        if (sc == null || sc.length == 0) {
            log.warn("{}{} >>> screenshot kosong, skip matching", CHECK_UTD, path);
            return false;
        }

        Mat source = null;
        Mat template = null;
        Mat sourceGray = new Mat();
        Mat templateGray = new Mat();
        Mat result = new Mat();

        try {
            MatOfByte matOfByte = new MatOfByte(sc);
            source = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

            if (source.empty()) {
                log.warn("{}{} >>> hasil decode source kosong, skip", CHECK_UTD, path);
                return false;
            }

            template = this.readImageAsMat(path);
            if (template == null || template.empty()) {
                log.warn("{}{} >>> template tidak ditemukan / kosong", CHECK_UTD, path);
                return false;
            }

            Imgproc.cvtColor(source, sourceGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(template, templateGray, Imgproc.COLOR_BGR2GRAY);

            int resultCols = sourceGray.cols() - templateGray.cols() + 1;
            int resultRows = sourceGray.rows() - templateGray.rows() + 1;

            if (resultCols <= 0 || resultRows <= 0) {
                log.warn("{}{} >>> ukuran template lebih besar dari source, cols={}, rows={}",
                        CHECK_UTD, path, resultCols, resultRows);
                return false;
            }

            result.create(resultRows, resultCols, CvType.CV_32FC1);

            // Perform template matching
            Imgproc.matchTemplate(sourceGray, templateGray, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);

            log.debug("{}{} >>> maxVal={}", CHECK_UTD, path, mmr.maxVal);

            if (mmr.maxVal < threshHold) {
                log.info("{}{} not found with conflevel: {}", CHECK_UTD, path, mmr.maxVal);
                return false;
            }

            log.info("{}{} found", CHECK_UTD, path);
            return true;
        } catch (CvException e) {
            log.error("{}{} >>> OpenCV error saat matching", CHECK_UTD, path, e);
            return false;
        } catch (Exception e) {
            log.error("{}{} >>> error tak terduga saat matching", CHECK_UTD, path, e);
            return false;
        } finally {
            if (source != null) {
                source.release();
            }
            if (template != null) {
                template.release();
            }
            sourceGray.release();
            templateGray.release();
            result.release();
        }
    }

    private Mat readImageAsMat(String path) {
        var resource = new ClassPathResource(path);
        try (InputStream is = resource.getInputStream()) {
            byte[] imageBytes = is.readAllBytes();
            MatOfByte mob = new MatOfByte(imageBytes);
            return Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
        } catch (Exception e) {
            log.error("Error reading image from path: {}", path, e);
        }
        return null;
    }

    public boolean clickYesButtonInFrame(Frame frame) {
        log.info("clickYesButtonInFrame >>> start");
        try {
            if (frame == null) {
                log.warn("clickYesButtonInFrame >>> frame is null, skip");
                return false;
            }

            Locator dialogs = frame.locator(EL_DIALOG_DRAGGABLE);
            int count = dialogs.count();
            if (count == 0) {
                log.info("clickYesButtonInFrame >>> no dialog found, nothing to click");
                return false;
            }

            Locator dialog = dialogs.first();

            Locator yesButton = dialog
                    .locator("button")
                    .filter(new Locator.FilterOptions().setHasText("Ya"))
                    .first();

            if (!yesButton.isVisible()) {
                log.info("clickYesButtonInFrame >>> 'Ya' button not visible, skip");
                return false;
            }

            yesButton.click(new Locator.ClickOptions().setTimeout(5000));
            log.info("clickYesButtonInFrame >>> 'Ya' clicked");
            return true;

        } catch (TimeoutError e) {
            log.warn("clickYesButtonInFrame >>> timeout while clicking 'Ya', probably not enabled", e);
            return false;
        } catch (PlaywrightException e) {
            log.warn("clickYesButtonInFrame >>> PlaywrightException while clicking 'Ya': {}", e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("clickYesButtonInFrame >>> unexpected error while clicking 'Ya': {}", e.getMessage(), e);
            return false;
        }
    }
}
