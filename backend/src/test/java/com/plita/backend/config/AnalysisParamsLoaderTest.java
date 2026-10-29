package com.plita.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plita.backend.analysis.AnalysisParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalysisParamsLoaderTest {

    /** Файл в resources и AnalysisParams.defaults() не должны разъезжаться. */
    @Test
    void bundledJsonEqualsDefaults() throws Exception {
        AnalysisParams fromFile = AnalysisParamsLoader.load(new ObjectMapper(), null);
        assertEquals(AnalysisParams.defaults(), fromFile);
    }
}
