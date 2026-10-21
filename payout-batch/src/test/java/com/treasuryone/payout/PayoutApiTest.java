package com.treasuryone.payout;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract smoke test with the full Spring context (real controller, service, repository and fake switch).
 * Uses the seeded demo account 1012345678 of CORP-001, and a unique batchRef so it does not depend on other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayoutApiTest {

    @Autowired
    MockMvc mvc;

    private static String body(String batchRef, String secondAmount) {
        return """
                {
                  "batchRef": "%s",
                  "debitAccountId": "1012345678",
                  "currency": "NGN",
                  "lines": [
                    { "lineRef": "EMP-0001", "accountNumber": "0123456789", "bankCode": "000013",
                      "beneficiaryName": "Adaeze Okonkwo", "amount": "485000.00" },
                    { "lineRef": "EMP-0002", "accountNumber": "2093456712", "bankCode": "000014",
                      "beneficiaryName": "Babatunde Adeyemi", "amount": "%s" }
                  ]
                }
                """.formatted(batchRef, secondAmount);
    }

    private static String bodyWith(String batchRef, String currency, String linesJson) {
        return """
                { "batchRef": "%s", "debitAccountId": "1012345678", "currency": %s, "lines": %s }
                """.formatted(batchRef, currency, linesJson);
    }

    private void assertSubmitRejected(String json, int status, String code) throws Exception {
        mvc.perform(post("/api/v1/payout-batches")
                        .header("X-Corporate-Id", "CORP-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    /** The shape of the body is checked by the annotations on SubmitRequest, so it has to be proven over HTTP. */
    @Test
    void theBodyRulesAreEnforcedOverHttp() throws Exception {
        String oneLine = """
                [ { "lineRef": "L1", "accountNumber": "0123456789", "bankCode": "000013",
                    "beneficiaryName": "A Person", "amount": "10.00" } ]""";
        String ref = "BODY-" + System.nanoTime();

        assertSubmitRejected(bodyWith(ref, "\"USD\"", oneLine), 422, "UNSUPPORTED_CURRENCY");
        assertSubmitRejected(bodyWith(ref, "null", oneLine), 422, "UNSUPPORTED_CURRENCY");
        assertSubmitRejected(bodyWith(ref, "\"NGN\"", "[]"), 422, "EMPTY_BATCH");
        assertSubmitRejected(bodyWith(ref, "\"NGN\"", "null"), 422, "EMPTY_BATCH");
        assertSubmitRejected(bodyWith(" ", "\"NGN\"", oneLine), 422, "INVALID_REQUEST");
        assertSubmitRejected(bodyWith("x".repeat(101), "\"NGN\"", oneLine), 422, "INVALID_REQUEST");

        // 5,001 lines: too large. The lines are not even looked at, so they can be identical.
        String line = "{\"lineRef\":\"L\",\"accountNumber\":\"0123456789\",\"bankCode\":\"000013\","
                + "\"beneficiaryName\":\"A\",\"amount\":\"1.00\"}";
        String tooMany = "[" + String.join(",", java.util.Collections.nCopies(5001, line)) + "]";
        assertSubmitRejected(bodyWith(ref, "\"NGN\"", tooMany), 422, "BATCH_TOO_LARGE");

        // not JSON at all
        mvc.perform(post("/api/v1/payout-batches")
                        .header("X-Corporate-Id", "CORP-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void submitResubmitConflictAndReads() throws Exception {
        String ref = "API-" + System.nanoTime();

        // 202 Accepted with a batchId as soon as the batch is validated and the funds are earmarked
        String created = mvc.perform(post("/api/v1/payout-batches")
                        .header("X-Corporate-Id", "CORP-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ref, "362500.00")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").isNumber())
                .andExpect(jsonPath("$.batchRef").value(ref))
                .andExpect(jsonPath("$.totalLines").value(2))
                .andReturn().getResponse().getContentAsString();
        String batchId = created.replaceAll(".*\"batchId\":(\\d+).*", "$1");

        // identical resubmission: 200 with the existing batch
        mvc.perform(post("/api/v1/payout-batches")
                        .header("X-Corporate-Id", "CORP-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ref, "362500.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(Long.parseLong(batchId)));

        // same batchRef, different content: 422
        mvc.perform(post("/api/v1/payout-batches")
                        .header("X-Corporate-Id", "CORP-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ref, "1.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_REF_CONFLICT"));

        // missing header: 400
        mvc.perform(post("/api/v1/payout-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ref + "-x", "1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_CORPORATE_ID"));

        // reads
        mvc.perform(get("/api/v1/payout-batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchRef").value(ref))
                .andExpect(jsonPath("$.lineCounts.PENDING").exists());
        mvc.perform(get("/api/v1/payout-batches/" + batchId + "/lines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountNumber").value("0123456789"))
                .andExpect(jsonPath("$[0].paymentRef").isString());
        mvc.perform(get("/api/v1/payout-batches/" + batchId + "/lines").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payout-batches/999999999"))
                .andExpect(status().isNotFound());

        // resume is safe at any time
        mvc.perform(post("/api/v1/payout-batches/" + batchId + "/resume"))
                .andExpect(status().isAccepted());

        // cancel: 200 while the batch is still running, 409 if the fake switch already finished both lines
        int cancelStatus = mvc.perform(post("/api/v1/payout-batches/" + batchId + "/cancel"))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertTrue(cancelStatus == 200 || cancelStatus == 409,
                "unexpected cancel status " + cancelStatus);
    }
}
