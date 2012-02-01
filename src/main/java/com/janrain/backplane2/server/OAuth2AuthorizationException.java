/*
 * Copyright 2011 Janrain, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.janrain.backplane2.server;

import javax.servlet.http.HttpServletRequest;

/**
 * @author Johnny Bufu
 */
public class OAuth2AuthorizationException extends Exception {

    public OAuth2AuthorizationException(String oauthErrorCode, String message, HttpServletRequest request) {
        this(oauthErrorCode, message, request, null);
    }

    public OAuth2AuthorizationException(String oauthErrorCode, String message, HttpServletRequest request, Throwable cause) {
        super(message, cause);
        this.oauthErrorCode = oauthErrorCode;
        this.redirectUri = request.getParameter(AuthorizationRequest.Field.REDIRECT_URI.getFieldName().toLowerCase());
        this.state = request.getParameter(AuthorizationRequest.Field.STATE.getFieldName().toLowerCase());
    }

    public OAuth2AuthorizationException(String oauthErrorCode, String message, AuthorizationRequest request) {
        this(oauthErrorCode, message, request, null);
    }

    public OAuth2AuthorizationException(String oauthErrorCode, String message, AuthorizationRequest request, Throwable cause) {
        super(message, cause);
        this.oauthErrorCode = oauthErrorCode;
        this.redirectUri = request.get(AuthorizationRequest.Field.REDIRECT_URI);
        this.state = request.get(AuthorizationRequest.Field.STATE);
    }

    public String getOauthErrorCode() {
        return oauthErrorCode;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public String getState() {
        return state;
    }

    private final String oauthErrorCode;
    private final String redirectUri;
    private final String state;
}
