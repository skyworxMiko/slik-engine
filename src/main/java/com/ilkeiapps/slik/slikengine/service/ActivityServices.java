package com.ilkeiapps.slik.slikengine.service;

import com.ilkeiapps.slik.slikengine.bean.ActivityServicesBean;
import com.ilkeiapps.slik.slikengine.retrofit.IM2MImage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServices {

    private final IM2MImage m2mImageService;

    private final CommonProcessingService commonProcessingService;

    private static final String TEXT_PLAIN = "text/plain";

    private static final String IMAGE_PNG = "image/png";

    public ActivityServicesBean start(String robotName) {
        var obj = new ActivityServicesBean();
        obj.setRobotName(robotName);
        obj.setStart(LocalDateTime.now());
        obj.setPrevStart(commonProcessingService.screenshot());
        return obj;
    }

    public void stop(ActivityServicesBean obj) {
        try {
            obj.setEnd(LocalDateTime.now());
            obj.setPrevEnd(commonProcessingService.screenshot());
            this.insertLog(obj);
        } catch (Exception e) {
            log.error("Error inserting log: {}", e.getMessage());
        }

    }

    private void insertLog(ActivityServicesBean bean) throws IOException {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        MediaType textPlain = MediaType.parse(TEXT_PLAIN);
        MediaType imagePng = MediaType.parse(IMAGE_PNG);

        RequestBody code = null;
        RequestBody name = null;
        RequestBody command = null;
        RequestBody type = null;
        RequestBody lst = null;
        RequestBody len = null;

        if (!StringUtils.isEmpty(bean.getRobotName())) {
            code = RequestBody.create(bean.getRobotName(), textPlain);
        }

        if (!StringUtils.isEmpty(bean.getName())) {
            name = RequestBody.create(bean.getName(), textPlain);
        }

        if (!StringUtils.isEmpty(bean.getCommand())) {
            command = RequestBody.create(bean.getCommand(), textPlain);
        }

        if (!StringUtils.isEmpty(bean.getType())) {
            type = RequestBody.create(bean.getType(), textPlain);
        }

        if (bean.getStart() != null) {
            lst = RequestBody.create(fmt.format(bean.getStart()), textPlain);
        }

        if (bean.getEnd() != null) {
            len = RequestBody.create(fmt.format(bean.getEnd()), textPlain);
        }

        RequestBody fileStart = RequestBody.create(bean.getPrevStart(), imagePng);
        MultipartBody.Part bodyStart = MultipartBody.Part.createFormData("docStart", "st", fileStart);

        RequestBody fileEnd = RequestBody.create(bean.getPrevEnd(), imagePng);
        MultipartBody.Part bodyEnd = MultipartBody.Part.createFormData("docEnd", "ed", fileEnd);

        Long appId = bean.getAppId();
        if (appId == null) {
            appId = -1L;
        }
        RequestBody app = RequestBody.create(appId.toString(), textPlain);

        var call = m2mImageService.inserLog(code, name, command, type, null, lst, len, app, bodyStart, bodyEnd);
        Response<Void> response = call.execute();
        if (!response.isSuccessful()) {
            log.warn("insertLog failed: HTTP {}", response.code());
        }
    }
}
