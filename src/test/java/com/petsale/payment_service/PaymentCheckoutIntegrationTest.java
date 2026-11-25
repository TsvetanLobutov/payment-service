package com.petsale.payment_service;

import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack checkout test: the real Spring context, the property binding for the Stripe key,
 * the controller and the JSON contract the React frontend consumes. Only the Stripe SDK is faked
 * (its static {@code Session.create} call), so no API key, network or test-mode account is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentCheckoutIntegrationTest {

    private static final String CHECKOUT_URL = "https://checkout.stripe.com/c/pay/abc123";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void checkoutReturnsStripeUrlAndSendsTheExactAmountInCents() throws Exception {
        try (MockedStatic<Session> sessionMock = stagingStripeSession()) {
            mockMvc.perform(post("/api/payments/checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Dog food", "price": 19.99}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checkoutUrl").value(CHECKOUT_URL));

            SessionCreateParams params = capturedParams(sessionMock);
            assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
            assertThat(params.getLineItems().get(0).getPriceData().getUnitAmount()).isEqualTo(1999L);
            assertThat(params.getLineItems().get(0).getPriceData().getProductData().getName())
                    .isEqualTo("Dog food");
        }
    }

    @Test
    void acceptsTheFrontendTotalSentAsAJsonNumber() throws Exception {
        // The frontend sums the cart in JavaScript, so the amount can carry float noise.
        try (MockedStatic<Session> sessionMock = stagingStripeSession()) {
            mockMvc.perform(post("/api/payments/checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Cart (and more)", "price": 59.97000000000001}
                                    """))
                    .andExpect(status().isOk());

            assertThat(capturedParams(sessionMock).getLineItems().get(0).getPriceData().getUnitAmount())
                    .isEqualTo(5997L);
        }
    }

    @Test
    void acceptsTheAmountAsAString() throws Exception {
        // The controller calls toString() on the value, so both JSON numbers and strings work.
        try (MockedStatic<Session> sessionMock = stagingStripeSession()) {
            mockMvc.perform(post("/api/payments/checkout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "Dog food", "price": "0.29"}
                                    """))
                    .andExpect(status().isOk());

            assertThat(capturedParams(sessionMock).getLineItems().get(0).getPriceData().getUnitAmount())
                    .isEqualTo(29L);
        }
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/payments/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    private MockedStatic<Session> stagingStripeSession() {
        MockedStatic<Session> sessionMock = mockStatic(Session.class);
        Session session = mock(Session.class);
        when(session.getUrl()).thenReturn(CHECKOUT_URL);
        sessionMock.when(() -> Session.create(any(SessionCreateParams.class))).thenReturn(session);
        return sessionMock;
    }

    private SessionCreateParams capturedParams(MockedStatic<Session> sessionMock) {
        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        sessionMock.verify(() -> Session.create(captor.capture()));
        return captor.getValue();
    }
}
