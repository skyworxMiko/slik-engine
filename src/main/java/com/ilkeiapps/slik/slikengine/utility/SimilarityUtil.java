package com.ilkeiapps.slik.slikengine.utility;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

@Slf4j
public class SimilarityUtil {

    public Double countDistant(String src, String dest) {
        if (src == null || src.isEmpty() || dest == null || dest.isEmpty()) {
            return 0D;
        }

        log.info("countDistant >>> counting src: " + src + " and dest: " + dest);
        try {
            var distant = new JaroWinklerSimilarity();
            var res = distant.apply(src, dest);
            log.info("countDistant >>> counting src: " + src + " and dest: " + dest + " with result: " + res);
            return res;
        } catch (Exception ignored) {
            /* IGNORE */
        }
        return 0D;
    }
}
