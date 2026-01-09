package com.ilkeiapps.slik.slikengine;

import com.ilkeiapps.slik.slikengine.service.AuthService;
import com.ilkeiapps.slik.slikengine.service.CaptchaService;
import com.ilkeiapps.slik.slikengine.service.PlaywrightDriverService;
import com.ilkeiapps.slik.slikengine.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CmdLineRunner implements CommandLineRunner {

    private AuthService authService;

    private CaptchaService captchaService;

    private ProcessingService processingService;

    private PlaywrightDriverService webDriverService;

    @Autowired
    public void setAuthService(AuthService authService) {
        this.authService = authService;
    }

    @Autowired
    public void setCaptchaService(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    @Autowired
    public void setProcessingService(ProcessingService processingService) {
        this.processingService = processingService;
    }

    @Autowired
    public void setWebDriverService(PlaywrightDriverService webDriverService) {
        this.webDriverService = webDriverService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("runner");

        authService.initLoad();

        webDriverService.initDriver();

        //captchaService.loadimage();

        //var str = captchaService.breakCaptcha(ImageIO.read(new File("D:/proyek/cbas/c.png")));
        //log.info("break " + str);

        processingService.doLogin();

        log.info("ini version terbaru dari combination v.1.0 tanggal 23 Desember 2025");

    }
}
