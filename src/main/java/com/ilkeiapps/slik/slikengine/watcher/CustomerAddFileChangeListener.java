package com.ilkeiapps.slik.slikengine.watcher;

import com.ilkeiapps.slik.slikengine.service.ExtractIdebService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.devtools.filewatch.ChangedFile;
import org.springframework.boot.devtools.filewatch.ChangedFiles;
import org.springframework.boot.devtools.filewatch.FileChangeListener;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerAddFileChangeListener implements FileChangeListener {

    private final ExtractIdebService extractIdebService;

    @Override
    public void onChange(Set<ChangedFiles> changeSet) {
        log.info("CustomerAddFileChangeListener >>>> start");
        for(ChangedFiles files : changeSet) {
            for(ChangedFile file: files.getFiles()) {
                if (file.getType().equals(ChangedFile.Type.ADD)) {
                    log.info("fiel >>> " + file.getFile().getAbsolutePath() + "-" + file.getFile().getName());
                    //extractIdebService.extract(file.getFile());
                }
            }
        }
        log.info("CustomerAddFileChangeListener >>>> end");
    }
}
