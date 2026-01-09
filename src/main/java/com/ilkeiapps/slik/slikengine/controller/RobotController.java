package com.ilkeiapps.slik.slikengine.controller;

import com.ilkeiapps.slik.slikengine.bean.*;
import com.ilkeiapps.slik.slikengine.entity.EngineStatus;
import com.ilkeiapps.slik.slikengine.service.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping(path = "/api/robot")
@AllArgsConstructor
public class RobotController {

    private final RobotService robotService;

    private final ProcessingService engineService;

    private final EngineService engine1Service;

    private final ProcessingService processingService;

    @GetMapping(path="ping", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PingResponse> ping() {
        return robotService.ping();
    }

    @Async
    @PostMapping(path="process", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void process(@RequestBody AppRequestWrapper src) {
        engineService.process(src);
    }

    @Async
    @PostMapping(path="approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public void approve(@RequestBody AppRequestPayload src) {
        engineService.approve(src);
    }

    @GetMapping(path="status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RobotHealth> statusProcess() {
        return robotService.statusProcess();
    }

    @GetMapping(path="screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> screenshot() {
        return null;
    }

    @GetMapping("/logout")
    public ApiResponse<String> logout() {
        var resp = new ApiResponse<String>();
        resp.setStatus(false);

        try {
            processingService.logout();
            resp.setStatus(true);
            resp.setMessage("Logout SLIK berhasil");
            resp.insertNewData("Logout SLIK berhasil");
        } catch (Exception e) {
            resp.setStatus(false);
            resp.setMessage("Logout SLIK error: " + e.getMessage());
        }

        return resp;
    }

    @GetMapping("/login")
    public ApiResponse<String> login() {
        var resp = new ApiResponse<String>();
        resp.setStatus(false);

        try {
            boolean ok = processingService.doLogin();
            resp.setStatus(ok);
            resp.setMessage(ok ? "Login SLIK berhasil" : "Login SLIK gagal");
            if (ok) resp.insertNewData("Login SLIK berhasil");
        } catch (Exception e) {
            resp.setStatus(false);
            resp.setMessage("Login SLIK error: " + e.getMessage());
        }

        return resp;
    }

    @GetMapping("/restart")
    public ApiResponse<String> restart() {
        var resp = new ApiResponse<String>();
        resp.setStatus(false);

        try {
            processingService.restartBrowser();
            resp.setStatus(true);
            resp.setMessage("Restart browser + relogin SLIK berhasil dipicu");
            resp.insertNewData("Restart browser berhasil");
        } catch (Exception e) {
            resp.setStatus(false);
            resp.setMessage("Restart browser error: " + e.getMessage());
        }

        return resp;
    }

    @GetMapping("/reload")
    public ApiResponse<String> reload() {
        var resp = new ApiResponse<String>();
        resp.setStatus(false);

        try {
            processingService.reloadBrowser();
            resp.setStatus(true);
            resp.setMessage("Reload browser berhasil dipicu");
            resp.insertNewData("Reload browser berhasil");
        } catch (Exception e) {
            resp.setStatus(false);
            resp.setMessage("Reload browser error: " + e.getMessage());
        }

        return resp;
    }

    @GetMapping(path="/check/statusprocessing/get", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<EngineStatus> process() {
        return engine1Service.getEngineService();
    }

    @PostMapping(path="/check/statusprocessing/set", produces = MediaType.APPLICATION_JSON_VALUE)
    public void statusSet(@RequestBody TestBean src) {
        engine1Service.setCurrentStatusEngine(src.getValue());
        engine1Service.setLastUpdate(LocalDateTime.now());
    }

    @PostMapping(path="/check/statuplaywrigth/set", produces = MediaType.APPLICATION_JSON_VALUE)
    public void playwrigthSet(@RequestBody TestBean src) {
        engine1Service.setLastPlaywrigthStatus(src.getValue());
        engine1Service.setLastUpdate(LocalDateTime.now());
    }
}
