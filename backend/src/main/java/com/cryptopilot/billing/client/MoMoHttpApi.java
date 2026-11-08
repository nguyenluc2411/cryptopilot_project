package com.cryptopilot.billing.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link MoMoApi} over HTTP, with short timeouts: a create waits on it while the Trader waits for the payment page.
 *
 * <p>Rule: D-146.
 */
@Component
class MoMoHttpApi implements MoMoApi {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient rest;

    MoMoHttpApi(MoMoProperties properties) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requests.setReadTimeout(READ_TIMEOUT);
        this.rest = RestClient.builder()
                .baseUrl(properties.endpoint().toString())
                .requestFactory(requests)
                .build();
    }

    @Override
    public String post(String path, Map<String, Object> request) {
        try {
            String body = rest.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                throw new GatewayUnavailableException("MoMo answered " + path + " with an empty body");
            }
            return body;
        } catch (RestClientException failed) {
            throw new GatewayUnavailableException("MoMo did not answer " + path + ": " + failed.getMessage(), failed);
        }
    }
}
