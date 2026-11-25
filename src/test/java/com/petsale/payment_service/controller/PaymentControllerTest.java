package com.petsale.payment_service.controller;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PaymentControllerTest {

    private static final String CHECKOUT_URL = "https://checkout.stripe.com/c/pay/abc123";
    private static final String API_KEY = "sk_test_dummy";

    private PaymentController controller;
    private String previousStripeApiKey;

    @BeforeEach
    void setUp() {
        previousStripeApiKey = Stripe.apiKey;
        controller = new PaymentController();
        ReflectionTestUtils.setField(controller, "stripeKey", API_KEY);
        Stripe.apiKey = null;
    }

    @AfterEach
    void restoreStripeApiKey() {
        Stripe.apiKey = previousStripeApiKey;
    }

    @ParameterizedTest(name = "{0} USD -> {1} cents")
    @CsvSource({
            "19.99, 1999",
            "0.29, 29",
            "1.10, 110",
            "59.97, 5997",
            "0.01, 1",
            "10.00, 1000"
    })
    void convertsDollarsToCentsWithoutFloatingPointTruncation(String price, long expectedCents) throws StripeException {
        assertThat(unitAmountFor(price)).isEqualTo(expectedCents);
    }

    @Test
    void buildsPaymentModeSessionWithOneLineItemForTheProduct() throws StripeException {
        SessionCreateParams params = paramsFor("19.99", "Dog food");

        assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
        assertThat(params.getSuccessUrl()).isEqualTo("http://localhost:3000/?success=true");
        assertThat(params.getCancelUrl()).isEqualTo("http://localhost:3000/?canceled=true");
        assertThat(params.getLineItems()).hasSize(1);

        SessionCreateParams.LineItem lineItem = params.getLineItems().get(0);
        assertThat(lineItem.getQuantity()).isEqualTo(1L);
        assertThat(lineItem.getPriceData().getCurrency()).isEqualTo("usd");
        assertThat(lineItem.getPriceData().getUnitAmount()).isEqualTo(1999L);
        assertThat(lineItem.getPriceData().getProductData().getName()).isEqualTo("Dog food");
    }

    @Test
    void returnsOnlyTheStripeCheckoutUrl() throws StripeException {
        Session session = sessionWithUrl(CHECKOUT_URL);
        try (MockedStatic<Session> sessionMock = mockStatic(Session.class)) {
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class))).thenReturn(session);

            Map<String, String> response = controller.createCheckoutSession(request("19.99", "Dog food"));

            assertThat(response).containsExactly(Map.entry("checkoutUrl", CHECKOUT_URL));
        }
    }

    @Test
    void configuresTheStripeSdkWithTheConfiguredApiKey() throws StripeException {
        Session session = sessionWithUrl(CHECKOUT_URL);
        try (MockedStatic<Session> sessionMock = mockStatic(Session.class)) {
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class))).thenReturn(session);

            controller.createCheckoutSession(request("19.99", "Dog food"));

            assertThat(Stripe.apiKey).isEqualTo(API_KEY);
        }
    }

    @Test
    void propagatesStripeExceptionsToTheCaller() {
        StripeException stripeFailure = mock(StripeException.class);

        try (MockedStatic<Session> sessionMock = mockStatic(Session.class)) {
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class)))
                    .thenThrow(stripeFailure);

            assertThatThrownBy(() -> controller.createCheckoutSession(request("19.99", "Dog food")))
                    .isSameAs(stripeFailure);
        }
    }

    @Test
    void currentlyFailsWithNullPointerExceptionWhenPriceIsMissing() {
        // Characterization: the payload is an untyped Map with no validation; document the 500 risk.
        try (MockedStatic<Session> sessionMock = mockStatic(Session.class)) {
            assertThatThrownBy(() -> controller.createCheckoutSession(request(null, "Dog food")))
                    .isInstanceOf(NullPointerException.class);

            sessionMock.verifyNoInteractions();
        }
    }

    @Test
    void currentlyForwardsNegativePricesToStripe() throws StripeException {
        // Characterization: no validation rejects a negative amount before it reaches Stripe.
        assertThat(unitAmountFor("-1.00")).isEqualTo(-100L);
    }

    private long unitAmountFor(String price) throws StripeException {
        return paramsFor(price, "Dog food").getLineItems().get(0).getPriceData().getUnitAmount();
    }

    private SessionCreateParams paramsFor(String price, String name) throws StripeException {
        Session session = sessionWithUrl(CHECKOUT_URL);
        try (MockedStatic<Session> sessionMock = mockStatic(Session.class)) {
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class))).thenReturn(session);

            controller.createCheckoutSession(request(price, name));

            ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
            sessionMock.verify(() -> Session.create(captor.capture()));
            return captor.getValue();
        }
    }

    private Session sessionWithUrl(String url) {
        Session session = mock(Session.class);
        when(session.getUrl()).thenReturn(url);
        return session;
    }

    private Map<String, Object> request(String price, String name) {
        return new java.util.HashMap<>() {{
            put("name", name);
            if (price != null) {
                put("price", price);
            }
        }};
    }
}
