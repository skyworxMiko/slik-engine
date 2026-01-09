package com.ilkeiapps.slik.slikengine.bean;


import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Setter
@Getter
@FieldNameConstants
public class AppRequest {

    private Long id;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requestInputDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime appRequestReqDate;

    private String appRequestMatchMode;

    private String appRequestCreatedBy;

    private String appRequestPurpose;

    private String appRequestCustType;

    private String appRequestNoSurat;

    private String appRequestCustName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate appRequestDob;

    private String appRequestKtp;

    private String appRequestNpwp;

    private String appRequestPob;

    private String appRequestHomeAddress;

    private String appRequestKtpAddress;

    private String appRequestPhoneNumber;

    private String appRequestGender;

    private String appRequestMotherName;

    private String appRequestKecamatan;

    private String appRequestKelurahan;

    private String appRequestKota;

    private String appRequestKodePos;

    private String appRequestPartnerName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate appRequestPartnerDob;

    private String appRequestPartnerKtp;

    private String appRequestPartnerAddress;

    private String appRequestPartnerKecamatan;

    private String appRequestPartnerKelurahan;

    private String appRequestPartnerKota;

    private String appRequestPartnerKodePos;

    private Integer counter;

    private String noRefCounter;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUpdate;

    private String robotCode;

}
