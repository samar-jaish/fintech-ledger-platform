package com.portfolio.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The notification API against a real Postgres. The Kafka listener is switched off: rows are stored directly. */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
@Testcontainers
class NotificationApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired NotificationRepository repository;
    @Autowired NotificationBroadcaster broadcaster;
    @MockitoBean JwtDecoder jwtDecoder;   // tokens are supplied by the test, so no identity provider is needed

    /** Stores a transfer from one user's account to another's (either owner may be null: a house account). */
    private Notification store(String fromOwner, String toOwner, String amount) {
        var event = new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(amount), "test", fromOwner, toOwner);
        return repository.saveIfNew(UUID.randomUUID(), event, "Transfer of " + amount).orElseThrow();
    }

    @Test
    void withoutAToken_theApiIsClosed() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_showsOnlyTheCallersOwnNotifications_withDirectionRelativeToThem() throws Exception {
        String ann = "ann-" + UUID.randomUUID(), ben = "ben-" + UUID.randomUUID(), cy = "cy-" + UUID.randomUUID();
        store(ann, ben, "10.00");      // ann sends to ben
        store(cy, ann, "5.00");        // cy sends to ann
        store(cy, ben, "7.00");        // does not concern ann

        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(ann))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].direction").value("RECEIVED"))   // newest first
                .andExpect(jsonPath("$[1].direction").value("SENT"))
                .andExpect(jsonPath("$[0].fromOwner").doesNotExist());     // owner ids never leave the service
    }

    @Test
    void list_rejectsALimitOutsideTheAllowedRange() throws Exception {
        mvc.perform(get("/api/v1/notifications?limit=0").with(jwt())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications?limit=101").with(jwt())).andExpect(status().isBadRequest());
    }

    @Test
    void stream_pushesNewNotificationsToTheUsersTheyConcern_andNotToOthers() throws Exception {
        String ann = "ann-" + UUID.randomUUID(), other = "other-" + UUID.randomUUID();
        MvcResult annStream = mvc.perform(get("/api/v1/notifications/stream").with(jwt().jwt(j -> j.subject(ann))))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult otherStream = mvc.perform(get("/api/v1/notifications/stream").with(jwt().jwt(j -> j.subject(other))))
                .andExpect(request().asyncStarted()).andReturn();

        broadcaster.publish(store(ann, "someone-else", "12.34"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertTrue(annStream.getResponse().getContentAsString().contains("12.34")));
        String body = annStream.getResponse().getContentAsString();
        assertTrue(body.contains("event:notification"), body);
        assertTrue(body.contains("\"direction\":\"SENT\""), body);
        assertTrue(!otherStream.getResponse().getContentAsString().contains("12.34"), "another user must receive nothing");
    }

    @Test
    void stream_answersAtOnce_soTheClientKnowsItIsOpen() throws Exception {
        MvcResult stream = mvc.perform(get("/api/v1/notifications/stream").with(jwt()))
                .andExpect(request().asyncStarted()).andReturn();

        assertTrue(stream.getResponse().getContentAsString().contains("connected"));
    }

    @Test
    void stream_replaysWhatAReconnectingClientMissed() throws Exception {
        String ann = "ann-" + UUID.randomUUID();
        Notification first = store("someone", ann, "1.00");
        store("someone", ann, "2.00");
        store("someone", ann, "3.00");

        MvcResult stream = mvc.perform(get("/api/v1/notifications/stream")
                        .header("Last-Event-ID", String.valueOf(first.id()))
                        .with(jwt().jwt(j -> j.subject(ann))))
                .andExpect(request().asyncStarted()).andReturn();

        String body = stream.getResponse().getContentAsString();
        assertTrue(!body.contains("\"amount\":1.00") && body.contains("2.00") && body.contains("3.00"), body);
    }

    @Test
    void cors_allowsTheUiOrigin_andAnswersThePreflightForTheStream() throws Exception {
        mvc.perform(options("/api/v1/notifications/stream")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,last-event-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        mvc.perform(options("/api/v1/notifications/stream")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }
}
