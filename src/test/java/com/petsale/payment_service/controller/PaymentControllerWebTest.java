package com.petsale.payment_service.controller;

import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@ActiveProfiles("test")
class PaymentControllerWebTest {

    private static final String CHECKOUT_URL = "https://checkout.stripe.com/c/pay/abc123";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsTheStripeCheckoutUrlAsJson() throws Exception {
        try (MockedStatic<Session> sessionMock = stagingStripeSession()) {
            mockMvc.perform(post("/api/payments/checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Dog food", "price": 19.99}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checkoutUrl").value(CHECKOUT_URL));
        }
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        try (MockedStatic<Session> sessionMock = stagingStripeSession()) {
            mockMvc.perform(post("/api/payments/checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not-json"))
                    .andExpect(status().isBadRequest());
        }
    }

    private MockedStatic<Session> stagingStripeSession() {
        MockedStatic<Session> sessionMock = mockStatic(Session.class);
        Session session = mock(Session.class);
        when(session.getUrl()).thenReturn(CHECKOUT_URL);
        sessionMock.when(() -> Session.create(any(SessionCreateParams.class))).thenReturn(session);
        return sessionMock;
    }
}
