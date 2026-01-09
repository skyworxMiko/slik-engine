package com.ilkeiapps.slik.slikengine.controller;

import com.ilkeiapps.slik.slikengine.service.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

@Slf4j
@RestController
@RequestMapping(path = "/api/playwrigth")
@AllArgsConstructor
public class PlaywrigthController {
    private final ExtractIdebService e;
    private final S3Service s3Service;

    @GetMapping(path="test", produces = MediaType.APPLICATION_JSON_VALUE)
    public void test() {
        e.extract();
    }

    @GetMapping(path="upload", produces = MediaType.APPLICATION_JSON_VALUE)
    public void upload() {
        s3Service.upload(new File("D:\\temp\\screenshot\\xd1.jpg"));
    }
}
