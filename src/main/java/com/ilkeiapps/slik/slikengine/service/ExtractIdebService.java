package com.ilkeiapps.slik.slikengine.service;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.google.common.base.Strings;
import com.ilkeiapps.slik.slikengine.entity.EngineConfig;
import com.ilkeiapps.slik.slikengine.entity.QEngineConfig;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractIdebService {

    private final EntityManager em;

    private final CriteriaBuilderFactory configBuilder;

    @Value("${cbas.engine.folder}")
    private String engineFolder;

    public void extract() {
        var qsg = new QEngineConfig("o");
        EngineConfig csg = configBuilder
                .create(em, EngineConfig.class)
                .from(EngineConfig.class, qsg.getMetadata().getName())
                .where(qsg.code.toString()).eq("IDEB")
                .getSingleResult();

        if (csg == null || csg.getValue() == null) {
            log.warn("extract >>> engine config IDEB tidak ditemukan / value null");
            return;
        }

        try {
            List<Path> files = listFile();
            if (CollectionUtils.isEmpty(files)) {
                log.info("extract >>> tidak ada file di folder {}", engineFolder);
                return;
            }

            for (Path fl : files) {
                processEncryptedFile(fl, csg.getValue());
            }

        } catch (IOException | NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
            log.error("extract >>> gagal memproses file IDEB", e);
        }
    }

    private void processEncryptedFile(Path file, String rawPassword) throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
        byte[] bytes = Files.readAllBytes(file);

        String pwd = Strings.padEnd(rawPassword, 16, 'z');
        byte[] keyBytes = pwd.getBytes(StandardCharsets.UTF_8);

        Cipher cipher = createAesDecryptCipher(keyBytes);

        byte[] decrypted = cipher.doFinal(bytes);
        byte[] zipBytes = stripHeaderAndFixZipSignature(decrypted);

        Path zipPath = buildZipPath(file);
        Files.write(zipPath, zipBytes);

        log.info("extract >>> file {} diekstrak ke {}", file, zipPath);
    }

    private Cipher createAesDecryptCipher(byte[] keyBytes) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException {
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(keyBytes);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // NOSONAR
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher;
    }

    private byte[] stripHeaderAndFixZipSignature(byte[] decrypted) {
        String header = new String(decrypted, StandardCharsets.UTF_8);

        byte[] payload;
        if (header.contains("minVer=")) {
            payload = Arrays.copyOfRange(decrypted, 31, decrypted.length);
        } else {
            payload = Arrays.copyOfRange(decrypted, 10, decrypted.length);

            payload[0] = 0x50;
            payload[1] = 0x4B;
            payload[2] = 0x03;
            payload[3] = 0x04;
        }

        return payload;
    }

    private Path buildZipPath(Path encryptedFile) {
        File f = encryptedFile.toFile();
        String destPath = f.getParentFile().getAbsolutePath();

        String fileName = f.getName();
        int dotIdx = fileName.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;

        String zipName = baseName + ".zip";
        String fullPath = destPath + FileSystems.getDefault().getSeparator() + zipName;
        return Paths.get(fullPath);
    }

    private List<Path> listFile() throws IOException {
        try (Stream<Path> stream = Files.list(Paths.get(engineFolder))) {
            return new ArrayList<>(stream.filter(file -> !Files.isDirectory(file)).toList());
        }
    }
}
