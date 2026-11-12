package com.cryptopilot.billing.client;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link SePayOrderApi} over HTTP: Basic authentication with {@code merchant_id:secret_key}, short timeouts, since a
 * reconciliation run asks about many orders and one slow answer must not hold the others.
 *
 * <p>Rule: NSF-13; D-147. Reference: SePay developer documentation, "Tổng quan API", "API lấy chi tiết đơn hàng".
 */
@Component
class SePayHttpOrderApi implements SePayOrderApi {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final SePayProperties properties;
    private final RestClient rest;

    SePayHttpOrderApi(SePayProperties properties) {
        this.properties = properties;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requests.setReadTimeout(READ_TIMEOUT);
        this.rest = RestClient.builder()
                .baseUrl(properties.apiUrl())
                .requestFactory(requests)
                .build();
    }

    @Override
    public Optional<String> orderDetail(String orderCode) {
        if (!properties.configured()) {
            throw new GatewayUnavailableException("SePay is not configured on this deployment");
        }
        try {
            return Optional.ofNullable(rest.get()
                    .uri("/v1/order/detail/{code}", orderCode)
                    .header(HttpHeaders.AUTHORIZATION, basic())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class));
        } catch (HttpClientErrorException.NotFound noSuchOrder) {
            return Optional.empty();
        } catch (RestClientException failed) {
            throw new GatewayUnavailableException(
                    "SePay did not answer about " + orderCode + ": " + failed.getMessage(), failed);
        }
    }

    @Override
    public CancelAnswer cancel(String orderCode) {
        if (!properties.configured()) {
            throw new GatewayUnavailableException("SePay is not configured on this deployment");
        }
        try {
            rest.post()
                    .uri("/v1/order/cancel")
                    .header(HttpHeaders.AUTHORIZATION, basic())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("order_invoice_number", orderCode))
                    .retrieve()
                    .toBodilessEntity();
            return CancelAnswer.CANCELLED;
        } catch (HttpClientErrorException.NotFound noSuchOrder) {
            return CancelAnswer.UNKNOWN;
        } catch (HttpClientErrorException refused) {
            int status = refused.getStatusCode().value();
            if (status == 401 || status == 403 || status == 429) {
                // Wrong keys or too many requests: SePay said nothing about the order.
                throw new GatewayUnavailableException(
                        "SePay did not cancel " + orderCode + ": HTTP " + status, refused);
            }
            return CancelAnswer.REFUSED;
        } catch (RestClientException failed) {
            throw new GatewayUnavailableException(
                    "SePay did not cancel " + orderCode + ": " + failed.getMessage(), failed);
        }
    }

    private String basic() {
        String credentials = properties.merchantId() + ":" + properties.secretKey();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
