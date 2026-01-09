package com.ilkeiapps.slik.slikengine.service;

import com.ilkeiapps.slik.slikengine.retrofit.IOCR;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private final IOCR iocr;

    @Value("${cbas.ml.folder}")
    private String screenShootFolder;

    public String fetchCapchaTensorPlaywright(Page page) {
        String script = """
                () => {
                    var img = document.getElementById('captcha-img');
                    if (!img) {
                        return null;
                    }
                
                    var canvas = document.createElement('canvas');
                    var ctx = canvas.getContext('2d');
                
                    function getMaxSize(srcWidth, srcHeight, maxWidth, maxHeight) {
                        var widthScale = null;
                        var heightScale = null;
                
                        if (maxWidth != null) {
                            widthScale = maxWidth / srcWidth;
                        }
                        if (maxHeight != null) {
                            heightScale = maxHeight / srcHeight;
                        }
                
                        var ratio = Math.min(widthScale || heightScale, heightScale || widthScale);
                        return {
                            width: Math.round(srcWidth * ratio),
                            height: Math.round(srcHeight * ratio)
                        };
                    }
                
                    var size = getMaxSize(img.width, img.height, 600, 600);
                    canvas.width = size.width;
                    canvas.height = size.height;
                    ctx.fillStyle = 'black';
                    ctx.fillRect(0, 0, size.width, size.height);
                    ctx.drawImage(img, 0, 0, size.width, size.height);
                
                    return canvas.toDataURL('image/jpeg', 0.9);
                }
                """;

        try {
            String tys = (String) page.evaluate(script);

            if (tys == null) {
                log.error("fetchCapchaTensorPlaywright >>> evaluate return null (captcha-img tidak ditemukan?)");
                return null;
            }

            if (tys.isBlank()) {
                log.error("fetchCapchaTensorPlaywright >>> dataURL kosong");
                return null;
            }

            // format: "data:image/jpeg;base64,xxxxx"
            int commaIdx = tys.indexOf(',');
            if (commaIdx < 0 || commaIdx == tys.length() - 1) {
                log.error("fetchCapchaTensorPlaywright >>> dataURL tidak berisi payload base64: {}", tys);
                return null;
            }

            String base64 = tys.substring(commaIdx + 1);
            byte[] by = Base64.getDecoder().decode(base64);

            BufferedImage xim;
            try (ByteArrayInputStream bis = new ByteArrayInputStream(by)) {
                xim = ImageIO.read(bis);
            }

            if (xim == null) {
                log.error("fetchCapchaTensorPlaywright >>> ImageIO.read mengembalikan null (byte bukan image valid)");
                return null;
            }

            long now = System.currentTimeMillis();

            Path folder = Path.of(screenShootFolder);
            Files.createDirectories(folder);

            Path filePath = folder.resolve("captcha-" + now + ".png");
            File fl = filePath.toFile();

            ImageIO.write(xim, "png", fl);
            log.info("fetchCapchaTensorPlaywright >>> captcha disimpan di {}", filePath.toAbsolutePath());

            RequestBody body = RequestBody.create(fl, MediaType.parse("image/png"));
            MultipartBody.Part rb = MultipartBody.Part.createFormData("file", "image", body);

            var call = iocr.fetchCaptcha(rb);
            var res = call.execute();
            var bd = res.body();
            if (bd != null && Boolean.TRUE.equals(bd.getStatus())) {
                return bd.getResult();
            }
        } catch (Exception e) {
            log.error("fetchCapchaTensorPlaywright >>> error", e);
        }

        return null;
    }

    public String fetchCapcha2TensorPlaywright(Frame frame) {
        String script = """
                () => {
                    var img = document.getElementById('captcha-img');
                    if (!img) {
                        return null;
                    }
                
                    var canvas = document.createElement('canvas');
                    var ctx = canvas.getContext('2d');
                
                    function getMaxSize(srcWidth, srcHeight, maxWidth, maxHeight) {
                        var widthScale = null;
                        var heightScale = null;
                
                        if (maxWidth != null) {
                            widthScale = maxWidth / srcWidth;
                        }
                        if (maxHeight != null) {
                            heightScale = maxHeight / srcHeight;
                        }
                
                        var ratio = Math.min(widthScale || heightScale, heightScale || widthScale);
                        return {
                            width: Math.round(srcWidth * ratio),
                            height: Math.round(srcHeight * ratio)
                        };
                    }
                
                    var size = getMaxSize(img.width, img.height, 600, 600);
                    canvas.width = size.width;
                    canvas.height = size.height;
                    ctx.fillStyle = 'white';
                    ctx.fillRect(0, 0, size.width, size.height);
                    ctx.drawImage(img, 0, 0, size.width, size.height);
                
                    return canvas.toDataURL('image/jpeg', 0.9);
                }
                """;

        try {
            String tys = (String) frame.evaluate(script);

            if (tys == null) {
                log.error("fetchCapcha2TensorPlaywright >>> evaluate return null (captcha-img tidak ditemukan?)");
                return null;
            }

            if (tys.isBlank()) {
                log.error("fetchCapcha2TensorPlaywright >>> dataURL kosong");
                return null;
            }

            String[] tyss = tys.split(",");
            if (tyss.length == 0) {
                log.error("fetchCapcha2TensorPlaywright >>> hasil split dataURL kosong: {}", tys);
                return null;
            }

            byte[] by = Base64.getDecoder().decode(tyss[tyss.length - 1]);

            BufferedImage xim;
            try (ByteArrayInputStream bis = new ByteArrayInputStream(by)) {
                xim = ImageIO.read(bis);
            }

            if (xim == null) {
                log.error("fetchCapcha2TensorPlaywright >>> ImageIO.read mengembalikan null (byte bukan image valid)");
                return null;
            }

            long now = System.currentTimeMillis();

            Path folder = Path.of(screenShootFolder);
            Files.createDirectories(folder);

            Path filePath = folder.resolve("captcha2-" + now + ".png");
            File fl = filePath.toFile();

            ImageIO.write(xim, "png", fl);
            log.info("fetchCapcha2TensorPlaywright >>> captcha disimpan di {}", filePath.toAbsolutePath());

            RequestBody body = RequestBody.create(fl, MediaType.parse("image/png"));
            MultipartBody.Part rb = MultipartBody.Part.createFormData("file", "image", body);

            var call = iocr.fetchCaptchaWhite(rb);
            var res = call.execute();
            var bd = res.body();
            if (bd != null && Boolean.TRUE.equals(bd.getStatus())) {
                String resx = bd.getResult();
                if (resx != null && resx.contains("[UNK]")) {
                    return null;
                }
                return bd.getResult();
            }

        } catch (Exception e) {
            log.error("fetchCapcha2TensorPlaywright >>> error", e);
        }

        return null;
    }

}
