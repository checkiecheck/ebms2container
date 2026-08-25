package nl.logius.ebms.cpa.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.logius.ebms.common.exception.CpaNotFoundException;
import nl.logius.ebms.common.exception.EbmsException;
import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.cpa.service.CpaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvc slice test for the new PUT /api/cpa/{cpaId} and
 * PATCH /api/cpa/{cpaId}/status endpoints.
 */
@WebMvcTest(CpaController.class)
@Import(CpaErrorHandler.class)
class CpaControllerUpdateTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @MockBean CpaService cpaService;

    private static final String CPA_ID = "urn:test:cpa:web-001";

    @Test
    void putUpdate_returns200WithUpdatedCpa() throws Exception {
        CpaDto in = CpaDto.builder().cpaId(CPA_ID).description("new").status("ACTIVE").cpaXml("<x/>").build();
        CpaDto out = CpaDto.builder().cpaId(CPA_ID).description("new").status("ACTIVE").cpaXml("<x/>").build();
        when(cpaService.update(eq(CPA_ID), any(CpaDto.class))).thenReturn(out);

        mvc.perform(put("/api/cpa/{id}", CPA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(in)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpaId").value(CPA_ID))
                .andExpect(jsonPath("$.description").value("new"));
    }

    @Test
    void putUpdate_missingCpa_returns404() throws Exception {
        CpaDto in = CpaDto.builder().cpaId(CPA_ID).description("d").cpaXml("<x/>").build();
        when(cpaService.update(eq(CPA_ID), any(CpaDto.class)))
                .thenThrow(new CpaNotFoundException(CPA_ID));

        mvc.perform(put("/api/cpa/{id}", CPA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(in)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("CPA niet gevonden"));
    }

    @Test
    void patchStatus_active_returns200() throws Exception {
        CpaDto out = CpaDto.builder().cpaId(CPA_ID).status("ACTIVE").build();
        when(cpaService.updateStatus(CPA_ID, "ACTIVE")).thenReturn(out);

        mvc.perform(patch("/api/cpa/{id}/status", CPA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void patchStatus_invalidValue_returns400WithProblemDetail() throws Exception {
        when(cpaService.updateStatus(eq(CPA_ID), any()))
                .thenThrow(new EbmsException("INVALID_STATUS", "Status moet ACTIVE of SUSPENDED zijn, ontvangen: FOO"));

        mvc.perform(patch("/api/cpa/{id}/status", CPA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "FOO"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("INVALID_STATUS"))
                .andExpect(jsonPath("$.type").value("urn:nl:logius:ebms:error:invalid_status"));
    }

    @Test
    void patchStatus_missingCpa_returns404() throws Exception {
        when(cpaService.updateStatus(eq(CPA_ID), any()))
                .thenThrow(new CpaNotFoundException(CPA_ID));

        mvc.perform(patch("/api/cpa/{id}/status", CPA_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void postCreate_duplicateCpa_returns409() throws Exception {
        CpaDto in = CpaDto.builder().cpaId(CPA_ID).description("d").cpaXml("<x/>").version("2.0").status("ACTIVE").build();
        when(cpaService.create(any(CpaDto.class)))
                .thenThrow(new EbmsException("CPA_ALREADY_EXISTS", "CPA bestaat al: " + CPA_ID + ". Gebruik update (PUT) of verwijder eerst."));

        mvc.perform(post("/api/cpa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(in)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("CPA_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PUT")));
    }
}
