package com.ilkeiapps.slik.slikengine.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Arrays;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaywrightDriverService {

    private final EngineService engineService;
    private Page driver;
    private Browser browser;
    private BrowserContext context;
    private Playwright playwright;
    private Frame currentFrame;

    @Value("${cbas.slik.proxy.use}")
    private Boolean useProxy;

    @Value("${cbas.slik.proxy.url}")
    private String proxyUrl;

    @Value("${cbas.slik.headless}")
    private Boolean headless;

    @Value("${cbas.engine.folder}")
    private String idebFolder;

    private static final String SLIK_URL = "https://slik.ojk.go.id/slik";
    private static final String CRASH = "CRASH";
    private static final String INITIAL = "INITIAL";
    private static final String NONE = "NONE";
    private static final String LOGINERROR = "LOGINERROR";

    public synchronized void initDriver() {
        log.info("initDriver >>> starting....");

        driver = null;
        context = null;
        browser = null;
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception e) {
                log.warn("initDriver >>> gagal close playwright sebelum re-init", e);
            }
        }
        playwright = null;

        try {
            log.info("initDriver >>> step 1: create Playwright");
            playwright = Playwright.create();

            log.info("initDriver >>> step 2: set launch options");
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setArgs(Arrays.asList("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage"));

            if (Boolean.TRUE.equals(useProxy)) {
                log.info("initDriver >>> using proxy: {}", proxyUrl);
                Proxy proxy = new Proxy(proxyUrl);
                options.setProxy(proxy);
            }

            options.setHeadless(Boolean.TRUE.equals(headless));

            File chkDir = new File(idebFolder);
            if (!chkDir.exists() && !chkDir.mkdirs()) {
                log.error("initDriver >>> gagal membuat folder download: {}", idebFolder);
            }
            options.setDownloadsPath(chkDir.toPath());

            log.info("initDriver >>> step 3: launch browser");
            browser = playwright.chromium().launch(options);

            log.info("initDriver >>> step 4: create context & page");
            context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true));
            driver = context.newPage();

            log.info("initDriver >>> driver created successfully");

            if (engineService != null) {
                engineService.setCurrentStatusEngine(INITIAL);
                engineService.setLastPlaywrigthStatus(NONE);
                engineService.setLastUpdate(LocalDateTime.now());
            }
        } catch (PlaywrightException e) {
            log.error("initDriver >>> Playwright error: {}", e.getMessage(), e);
            driver = null;
            context = null;
            browser = null;

            if (playwright != null) {
                try {
                    playwright.close();
                } catch (Exception ex) {
                    log.warn("initDriver >>> gagal close playwright setelah error", ex);
                }
            }
            playwright = null;

            if (engineService != null) {
                engineService.setCurrentStatusEngine(CRASH);
                engineService.setLastPlaywrigthStatus(LOGINERROR);
                engineService.setLastUpdate(LocalDateTime.now());
            }
        }

        log.info("initDriver >>> setup done....");
    }

    public void restart() {
        log.info("restart >>> restarting....");

        if (!shouldRestart()) {
            return;
        }

        sleepQuietly(5_000, "before cleanup");

        closeResources();

        sleepQuietly(2_000, "after cleanup");

        reinitAndNavigate();

        log.info("restart >>> done");
    }

    private boolean shouldRestart() {
        String es = engineService != null ? engineService.getCurrentStatusEngine() : null;
        if (!CRASH.equals(es)) {
            log.info("restart >>> canceled, status: {} is not CRASH", es);
            return false;
        }
        return true;
    }

    private void sleepQuietly(long millis, String phase) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("restart >>> interrupted while sleeping ({})", phase, e);
        }
    }

    private void closeResources() {
        closePage(driver);
        driver = null;

        closeBrowser(browser);
        browser = null;

        closePlaywright();
        playwright = null;
        context = null;
    }

    private void closePage(Page page) {
        try {
            if (page != null) {
                page.close();
            }
        } catch (Exception e) {
            log.warn("restart >>> gagal close {}", "driver", e);
        }
    }

    private void closeBrowser(Browser br) {
        try {
            if (br != null) {
                br.close();
            }
        } catch (Exception e) {
            log.warn("restart >>> gagal close browser", e);
        }
    }

    private void closePlaywright() {
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            log.warn("restart >>> gagal close playwright", e);
        }
    }

    private void reinitAndNavigate() {
        try {
            this.initDriver();

            if (driver != null) {
                driver.navigate(SLIK_URL);
                driver.waitForLoadState(LoadState.LOAD);
            } else {
                log.error("restart >>> driver null setelah initDriver, tidak bisa navigate");
            }

            if (engineService != null) {
                engineService.setCurrentStatusEngine(INITIAL);
                engineService.setLastPlaywrigthStatus(NONE);
                engineService.setLastUpdate(LocalDateTime.now());
            }
        } catch (Exception e) {
            log.error("restart >>> gagal re-init driver", e);
            if (engineService != null) {
                engineService.setCurrentStatusEngine(CRASH);
                engineService.setLastPlaywrigthStatus(LOGINERROR);
                engineService.setLastUpdate(LocalDateTime.now());
            }
        }
    }

    public void refreshLogin() {
        log.info("refreshLogin >>> refresh browser for login");
        if (driver != null) {
            driver.reload();
        } else {
            log.warn("refreshLogin >>> driver null, skip reload");
        }
    }

    public void refresh() {
        log.info("refresh >>> refresh browser");
        if (engineService != null) {
            engineService.setCurrentStatusEngine("IDLE");
            engineService.setLastUpdate(LocalDateTime.now());
        }
        if (driver != null) {
            driver.reload();
        } else {
            log.warn("refresh >>> driver null, skip reload");
        }
        log.info("refresh >>> status is set to IDLE");
    }

    public Page getDriver() {
        if (driver == null) {
            log.error("getDriver >>> driver null, Playwright belum di-init");
        }
        return driver;
    }

    public Frame getFrame(String frameName) {
        if (driver == null) {
            log.warn("getFrame >>> driver null, tidak bisa ambil frame '{}'", frameName);
            return null;
        }

        Page page = driver;

        try {
            page.waitForLoadState(LoadState.LOAD);
        } catch (PlaywrightException e) {
            log.warn("getFrame >>> gagal waitForLoadState, lanjut coba ambil frame '{}'", frameName, e);
        }

        Frame frame = page.frame(frameName);
        if (frame == null) {
            log.warn("getFrame >>> frame '{}' tidak ditemukan, url sekarang={}", frameName, page.url());
        } else {
            log.debug("getFrame >>> frame '{}' ditemukan, url={}", frameName, frame.url());
        }
        return frame;
    }

    public Frame getFrame() {
        if (currentFrame != null) {
            return currentFrame;
        }
        currentFrame = getFrame("main");
        return currentFrame;
    }

    public void setFrame(Frame finalFrame) {
        if (finalFrame == null) {
            log.warn("setFrame >>> finalFrame null, clear currentFrame");
            this.currentFrame = null;
            return;
        }

        this.currentFrame = finalFrame;
        try {
            log.info("setFrame >>> frame set: name={} url={}",
                    finalFrame.name(), finalFrame.url());
        } catch (Exception e) {
            log.info("setFrame >>> frame set (gagal baca name/url)", e);
        }
    }

    public boolean isPlaywrightActive() {
        if (playwright == null || browser == null || context == null || driver == null) {
            return false;
        }

        try {
            if (!browser.isConnected()) {
                return false;
            }

            if (isContextClosed(browser, context)) {
                return false;
            }

            return !driver.isClosed();
        } catch (Exception e) {
            log.warn("isPlaywrightActive >>> gagal cek status: {}", e.getMessage());
            return false;
        }
    }

    private boolean isContextClosed(Browser browser, BrowserContext ctx) {
        try {
            return browser.contexts().stream().noneMatch(c -> c == ctx);
        } catch (Exception e) {
            log.warn("isContextClosed >>> gagal cek context, anggap sudah close: {}", e.getMessage());
            return true;
        }
    }

}
