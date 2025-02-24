/**
 * Copyright (c) 2021 Contributors to the SmartHome/J project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.smarthomej.binding.viessmann.internal.api;

import static org.smarthomej.binding.viessmann.internal.ViessmannBindingConstants.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smarthomej.binding.viessmann.internal.dto.oauth.AuthorizeResponseDTO;
import org.smarthomej.binding.viessmann.internal.dto.oauth.TokenResponseDTO;
import org.smarthomej.binding.viessmann.internal.handler.ViessmannBridgeHandler;

import com.google.gson.JsonSyntaxException;

/**
 * The {@link ViessmannAuth} performs the initial OAuth authorization
 * with the Viessmann authorization servers.
 *
 * @author Ronny Grun - Initial contribution
 */
@NonNullByDefault
public class ViessmannAuth {

    private final Logger logger = LoggerFactory.getLogger(ViessmannAuth.class);

    private final ViessmannBridgeHandler bridgeHandler;
    private final ViessmannApi api;
    private final String apiKey;
    private final String user;
    private final String password;

    private final HttpClient httpClient;

    private ViessmannAuthState state;

    private @Nullable AuthorizeResponseDTO authResponse;
    public @Nullable String accessToken;

    /**
     * The authorization code needed to make the first-time request
     * of the refresh and access tokens. Obtained from the call to {@code authorize()}.
     */
    private @Nullable String code;

    private @Nullable String refreshToken;

    public ViessmannAuth(ViessmannApi api, ViessmannBridgeHandler bridgeHandler, String apiKey, HttpClient httpClient,
            String user, String password) {
        this.api = api;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.bridgeHandler = bridgeHandler;
        this.user = user;
        this.password = password;
        state = ViessmannAuthState.NEED_AUTH;
        authResponse = null;
    }

    public void setState(ViessmannAuthState newState) {
        if (newState != state) {
            logger.debug("ViessmannAuth: Change state from {} to {}", state, newState);
            state = newState;
        }
    }

    public void setRefreshToken(String newRefreshToken) {
        if (!newRefreshToken.equals(refreshToken)) {
            logger.debug("ViessmannAuth: Change refreshToken from {} to {}", refreshToken, newRefreshToken);
            refreshToken = newRefreshToken;
        }
    }

    public boolean isComplete() {
        return state == ViessmannAuthState.COMPLETE;
    }

    public ViessmannAuthState doAuthorization() throws ViessmannAuthException {
        switch (state) {
            case NEED_AUTH:
                authorize();
                if (state == ViessmannAuthState.NEED_TOKEN) {
                    getTokens();
                }
                break;
            case NEED_LOGIN:
                break;
            case NEED_TOKEN:
                getTokens();
                break;
            case NEED_REFRESH_TOKEN:
                getRefreshTokens();
                break;
            case COMPLETE:
                bridgeHandler.updateBridgeStatus(ThingStatus.ONLINE);
                break;
        }
        return state;
    }

    public @Nullable String getAccessToken() throws ViessmannAuthException {
        return this.accessToken;
    }

    public void setAccessToken(String newAccessToken) {
        this.accessToken = newAccessToken;
    }

    /**
     * Call the Viessmann authorize endpoint to get the authorization code.
     */
    private void authorize() throws ViessmannAuthException {
        logger.debug("ViessmannAuth: State is {}: Executing step: 'authorize'", state);
        StringBuilder url = new StringBuilder(VIESSMANN_AUTHORIZE_URL);
        url.append("?response_type=code");
        url.append("&client_id=").append(apiKey);
        url.append("&code_challenge=2e21faa1-db2c-4d0b-a10f-575fd372bc8c-575fd372bc8c");
        url.append("&redirect_uri=http://localhost:8080/viessmann/authcode/");
        url.append("&scope=").append(VIESSMANN_SCOPE);
        logger.trace("ViessmannAuth: Getting authorize URL={}", url);
        String response = executeUrlAuthorize("GET", url.toString());
        logger.trace("ViessmannAuth: Auth response: {}", response);
        if (response != null) {
            if (response.indexOf("<!DOCTYPE html>") >= 0) {
                logger.warn("ViessmannAuth: Login failed. Please check user and passowrd.");
                updateBridgeStatusLogin();
                return;
            }
            if (response.indexOf("error") >= 0) {
                logger.warn("ViessmannAuth: Login failed. Wrong code response.");
                return;
            }
        }
        try {
            authResponse = api.getGson().fromJson(response, AuthorizeResponseDTO.class);
            if (authResponse == null) {
                logger.debug("ViessmannAuth: Got null authorize response from Viessmann API");
                setState(ViessmannAuthState.NEED_AUTH);
                return;
            } else {
                AuthorizeResponseDTO resp = this.authResponse;
                if (resp == null) {
                    logger.warn("AuthorizeResponseDTO is null. This should not happen.");
                    return;
                }
                if (resp.errorMsg != null) {
                    logger.debug("ViessmannAuth: Got null authorize response from Viessmann API");
                    setState(ViessmannAuthState.NEED_AUTH);
                    return;
                }
                code = resp.code;
                setState(ViessmannAuthState.NEED_TOKEN);
            }
        } catch (JsonSyntaxException e) {
            logger.info("ViessmannAuth: Exception while parsing authorize response: {}", e.getMessage());
            setState(ViessmannAuthState.NEED_AUTH);
        }
    }

    /**
     * Call the Viessmann token endpoint to get the access and refresh tokens. Once successfully retrieved,
     * the access and refresh tokens will be injected into the OHC OAuth service.
     * Warnings are suppressed to avoid the Gson.fromJson warnings.
     */
    private void getTokens() throws ViessmannAuthException {
        logger.debug("ViessmannAuth: State is {}: Executing step: 'getToken'", state);
        StringBuilder url = new StringBuilder(VIESSMANN_TOKEN_URL);
        url.append("?grant_type=authorization_code");
        url.append("&client_id=").append(apiKey);
        url.append("&redirect_uri=http://localhost:8080/viessmann/authcode/");
        url.append("&code_verifier=2e21faa1-db2c-4d0b-a10f-575fd372bc8c-575fd372bc8c");
        url.append("&code=").append(code);

        logger.trace("ViessmannAuth: Posting token URL={}", url);
        String response = executeUrlToken("POST", url.toString());

        TokenResponseDTO tokenResponse = api.getGson().fromJson(response, TokenResponseDTO.class);
        if (tokenResponse == null) {
            logger.debug("ViessmannAuth: Got null token response from Viessmann API");
            bridgeHandler.updateBridgeStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("ViessmannAuth: Got null token response from Viessmann API"));
            setState(ViessmannAuthState.NEED_AUTH);
            return;
        }
        logger.trace("ViessmannAuth: Got a valid token response: {}", response);
        api.setTokenResponseDTO(tokenResponse);
        refreshToken = tokenResponse.refreshToken;
        api.setTokenExpiryDate(TimeUnit.SECONDS.toMillis(tokenResponse.expiresIn));
        setState(ViessmannAuthState.COMPLETE);
    }

    /**
     * Call the Viessmann token endpoint to get the access and refresh tokens. Once successfully retrieved,
     * the access and refresh tokens will be injected into the OHC OAuth service.
     * Warnings are suppressed to avoid the Gson.fromJson warnings.
     */
    private void getRefreshTokens() throws ViessmannAuthException {
        logger.debug("ViessmannAuth: State is {}: Executing step: 'getRefreshToken'", state);
        StringBuilder url = new StringBuilder(VIESSMANN_TOKEN_URL);
        url.append("?grant_type=refresh_token");
        url.append("&client_id=").append(apiKey);
        url.append("&refresh_token=").append(refreshToken);

        logger.trace("ViessmannAuth: Posting token URL={}", url);
        String response = executeUrlToken("POST", url.toString());

        TokenResponseDTO tokenResponse = api.getGson().fromJson(response, TokenResponseDTO.class);
        if (tokenResponse == null) {
            logger.debug("ViessmannAuth: Got null token response from Viessmann API");
            bridgeHandler.updateBridgeStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("ViessmannAuth: Got null token response from Viessmann API"));
            setState(ViessmannAuthState.NEED_AUTH);
            return;
        }
        logger.trace("ViessmannAuth: Got a valid token response: {}", response);
        bridgeHandler.updateBridgeStatus(ThingStatus.ONLINE);
        api.setTokenResponseDTO(tokenResponse);
        api.setTokenExpiryDate(TimeUnit.SECONDS.toMillis(tokenResponse.expiresIn));

        setState(ViessmannAuthState.COMPLETE);
    }

    private void updateBridgeStatusLogin() {
        bridgeHandler.updateBridgeStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                String.format("Login fails. Please check user and password."));
    }

    private void updateBridgeStatusApiKey() {
        bridgeHandler.updateBridgeStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                String.format("Login fails. Please check API Key."));
    }

    private @Nullable String executeUrlAuthorize(String method, String url) {
        Request request = httpClient.newRequest(url);
        request.timeout(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        request.method(method);
        String authorization = new String(Base64.getEncoder().encode((user + ":" + password).getBytes()),
                StandardCharsets.UTF_8);
        request.header("Authorization", "Basic " + authorization);
        try {
            ContentResponse contentResponse = request.send();
            switch (contentResponse.getStatus()) {
                case HttpStatus.OK_200:
                    return contentResponse.getContentAsString();
                case HttpStatus.BAD_REQUEST_400:
                    logger.debug("BAD REQUEST(400) response received: {}", contentResponse.getContentAsString());
                    updateBridgeStatusApiKey();
                    return contentResponse.getContentAsString();
                case HttpStatus.UNAUTHORIZED_401:
                    logger.debug("UNAUTHORIZED(401) response received: {}", contentResponse.getContentAsString());
                    return contentResponse.getContentAsString();
                case HttpStatus.NO_CONTENT_204:
                    logger.debug("HTTP response 204: No content. Check configuration");
                    break;
                default:
                    logger.debug("HTTP {} failed: {}, {}", method, contentResponse.getStatus(),
                            contentResponse.getReason());
                    break;
            }
        } catch (TimeoutException e) {
            logger.debug("TimeoutException: Call to Viessmann API timed out");
        } catch (ExecutionException e) {
            logger.debug("ExecutionException on call to Viessmann authorization API", e);
        } catch (InterruptedException e) {
            logger.debug("InterruptedException on call to Viessman authorization API: {}", e.getMessage());
        }
        return null;
    }

    private @Nullable String executeUrlToken(String method, String url) {
        Request request = httpClient.newRequest(url);
        request.timeout(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        request.method("POST");
        request.header("Content-Type", "application/x-www-form-urlencoded");
        request.header("Host", "iam.viessmann.com");
        try {
            ContentResponse contentResponse = request.send();
            switch (contentResponse.getStatus()) {
                case HttpStatus.OK_200:
                    return contentResponse.getContentAsString();
                case HttpStatus.BAD_REQUEST_400:
                    logger.debug("BAD REQUEST(400) response received: {}", contentResponse.getContentAsString());
                    return contentResponse.getContentAsString();
                case HttpStatus.UNAUTHORIZED_401:
                    logger.debug("UNAUTHORIZED(401) response received: {}", contentResponse.getContentAsString());
                    return contentResponse.getContentAsString();
                case HttpStatus.NO_CONTENT_204:
                    logger.debug("HTTP response 204: No content. Check configuration");
                    break;
                default:
                    logger.debug("HTTP {} failed: {}, {}", method, contentResponse.getStatus(),
                            contentResponse.getReason());
                    break;
            }
        } catch (TimeoutException e) {
            logger.debug("TimeoutException: Call to Viessmann API timed out");
        } catch (ExecutionException e) {
            logger.debug("ExecutionException on call to Viessmann authorization API", e);
        } catch (InterruptedException e) {
            logger.debug("InterruptedException on call to Viessmann authorization API: {}", e.getMessage());
        }
        return null;
    }
}
