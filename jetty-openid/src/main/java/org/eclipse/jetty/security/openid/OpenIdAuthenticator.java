//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security.openid;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;

/**
 * <p>Implements authentication using OpenId Connect on top of OAuth 2.0.
 *
 * <p>The OpenIdAuthenticator redirects unauthenticated requests to the OpenID Connect Provider. The End-User is
 * eventually redirected back with an Authorization Code to the /j_security_check URI within the context.
 * The Authorization Code is then used to authenticate the user through the {@link OpenIdCredentials} and {@link OpenIdLoginService}.
 * </p>
 * <p>
 * Once a user is authenticated the OpenID Claims can be retrieved through an attribute on the session with the key {@link #CLAIMS}.
 * The full response containing the OAuth 2.0 Access Token can be obtained with the session attribute {@link #RESPONSE}.
 * </p>
 * <p>{@link SessionAuthentication} is then used to wrap Authentication results so that they are associated with the session.</p>
 */
public class OpenIdAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = Log.getLogger(OpenIdAuthenticator.class);

    public static final String CLAIMS = "org.eclipse.jetty.security.openid.claims";
    public static final String RESPONSE = "org.eclipse.jetty.security.openid.response";
    public static final String ERROR_PAGE = "org.eclipse.jetty.security.openid.error_page";
    public static final String J_URI = "org.eclipse.jetty.security.openid.URI";
    public static final String J_POST = "org.eclipse.jetty.security.openid.POST";
    public static final String J_METHOD = "org.eclipse.jetty.security.openid.METHOD";
    public static final String CSRF_TOKEN = "org.eclipse.jetty.security.openid.csrf_token";
    public static final String J_SECURITY_CHECK = "/j_security_check";

    private OpenIdConfiguration _configuration;
    private String _errorPage;
    private String _errorPath;
    private boolean _alwaysSaveUri;

    public OpenIdAuthenticator()
    {
    }

    public OpenIdAuthenticator(OpenIdConfiguration configuration, String errorPage)
    {
        this._configuration = configuration;
        if (errorPage != null)
            setErrorPage(errorPage);
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        super.setConfiguration(configuration);

        String error = configuration.getInitParameter(ERROR_PAGE);
        if (error != null)
            setErrorPage(error);

        if (_configuration != null)
            return;

        LoginService loginService = configuration.getLoginService();
        if (!(loginService instanceof OpenIdLoginService))
            throw new IllegalArgumentException("invalid LoginService");
        this._configuration = ((OpenIdLoginService)loginService).getConfiguration();
    }

    @Override
    public String getAuthMethod()
    {
        return Constraint.__OPENID_AUTH;
    }

    /**
     * If true, uris that cause a redirect to a login page will always
     * be remembered. If false, only the first uri that leads to a login
     * page redirect is remembered.
     *
     * @param alwaysSave true to always save the uri
     */
    public void setAlwaysSaveUri(boolean alwaysSave)
    {
        _alwaysSaveUri = alwaysSave;
    }

    public boolean isAlwaysSaveUri()
    {
        return _alwaysSaveUri;
    }

    private void setErrorPage(String path)
    {
        if (path == null || path.trim().length() == 0)
        {
            _errorPath = null;
            _errorPage = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("error-page must start with /");
                path = "/" + path;
            }
            _errorPage = path;
            _errorPath = path;

            if (_errorPath.indexOf('?') > 0)
                _errorPath = _errorPath.substring(0, _errorPath.indexOf('?'));
        }
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("login {} {} {}", username, credentials, request);

        UserIdentity user = super.login(username, credentials, request);
        if (user != null)
        {
            HttpSession session = ((HttpServletRequest)request).getSession();
            Authentication cached = new SessionAuthentication(getAuthMethod(), user, credentials);
            session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, cached);
            session.setAttribute(CLAIMS, ((OpenIdCredentials)credentials).getClaims());
            session.setAttribute(RESPONSE, ((OpenIdCredentials)credentials).getResponse());
        }
        return user;
    }

    @Override
    public void logout(ServletRequest request)
    {
        super.logout(request);
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);

        if (session == null)
            return;

        //clean up session
        session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
        session.removeAttribute(CLAIMS);
        session.removeAttribute(RESPONSE);
    }

    @Override
    public void prepareRequest(ServletRequest request)
    {
        //if this is a request resulting from a redirect after auth is complete
        //(ie its from a redirect to the original request uri) then due to
        //browser handling of 302 redirects, the method may not be the same as
        //that of the original request. Replace the method and original post
        //params (if it was a post).
        //
        //See Servlet Spec 3.1 sec 13.6.3
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);
        if (session == null || session.getAttribute(SessionAuthentication.__J_AUTHENTICATED) == null)
            return; //not authenticated yet

        String juri = (String)session.getAttribute(J_URI);
        if (juri == null || juri.length() == 0)
            return; //no original uri saved

        String method = (String)session.getAttribute(J_METHOD);
        if (method == null || method.length() == 0)
            return; //didn't save original request method

        StringBuffer buf = httpRequest.getRequestURL();
        if (httpRequest.getQueryString() != null)
            buf.append("?").append(httpRequest.getQueryString());

        if (!juri.equals(buf.toString()))
            return; //this request is not for the same url as the original

        //restore the original request's method on this request
        if (LOG.isDebugEnabled())
            LOG.debug("Restoring original method {} for {} with method {}", method, juri, httpRequest.getMethod());
        Request baseRequest = Request.getBaseRequest(request);
        baseRequest.setMethod(method);
    }

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        final HttpServletRequest request = (HttpServletRequest)req;
        final HttpServletResponse response = (HttpServletResponse)res;
        final Request baseRequest = Request.getBaseRequest(request);
        final Response baseResponse = baseRequest.getResponse();

        String uri = request.getRequestURI();
        if (uri == null)
            uri = URIUtil.SLASH;

        mandatory |= isJSecurityCheck(uri);
        if (!mandatory)
            return new DeferredAuthentication(this);

        if (isErrorPage(URIUtil.addPaths(request.getServletPath(), request.getPathInfo())) && !DeferredAuthentication.isDeferred(response))
            return new DeferredAuthentication(this);

        try
        {
            if (request.isRequestedSessionIdFromURL())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Session ID should be cookie for OpenID authentication to work");

                baseResponse.sendRedirect(getRedirectCode(baseRequest.getHttpVersion()), URIUtil.addPaths(request.getContextPath(), _errorPage));
                return Authentication.SEND_FAILURE;
            }

            // Handle a request for authentication.
            if (isJSecurityCheck(uri))
            {
                String authCode = request.getParameter("code");
                if (authCode != null)
                {
                    // Verify anti-forgery state token
                    String state = request.getParameter("state");
                    String antiForgeryToken = (String)request.getSession().getAttribute(CSRF_TOKEN);
                    if (antiForgeryToken == null || !antiForgeryToken.equals(state))
                    {
                        LOG.warn("auth failed 403: invalid state parameter");
                        if (response != null)
                            response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        return Authentication.SEND_FAILURE;
                    }

                    // Attempt to login with the provided authCode
                    OpenIdCredentials credentials = new OpenIdCredentials(authCode, getRedirectUri(request), _configuration);
                    UserIdentity user = login(null, credentials, request);
                    HttpSession session = request.getSession(false);
                    if (user != null)
                    {
                        // Redirect to original request
                        String nuri;
                        synchronized (session)
                        {
                            nuri = (String)session.getAttribute(J_URI);

                            if (nuri == null || nuri.length() == 0)
                            {
                                nuri = request.getContextPath();
                                if (nuri.length() == 0)
                                    nuri = URIUtil.SLASH;
                            }
                        }
                        OpenIdAuthentication openIdAuth = new OpenIdAuthentication(getAuthMethod(), user);
                        if (LOG.isDebugEnabled())
                            LOG.debug("authenticated {}->{}", openIdAuth, nuri);

                        response.setContentLength(0);
                        baseResponse.sendRedirect(getRedirectCode(baseRequest.getHttpVersion()), nuri);
                        return openIdAuth;
                    }
                }

                // not authenticated
                if (LOG.isDebugEnabled())
                    LOG.debug("OpenId authentication FAILED");
                if (_errorPage == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth failed 403");
                    if (response != null)
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth failed {}", _errorPage);
                    baseResponse.sendRedirect(getRedirectCode(baseRequest.getHttpVersion()), URIUtil.addPaths(request.getContextPath(), _errorPage));
                }

                return Authentication.SEND_FAILURE;
            }

            // Look for cached authentication
            HttpSession session = request.getSession(false);
            Authentication authentication = session == null ? null : (Authentication)session.getAttribute(SessionAuthentication.__J_AUTHENTICATED);
            if (authentication != null)
            {
                // Has authentication been revoked?
                if (authentication instanceof Authentication.User && _loginService != null &&
                    !_loginService.validate(((Authentication.User)authentication).getUserIdentity()))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth revoked {}", authentication);
                    session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
                }
                else
                {
                    synchronized (session)
                    {
                        String jUri = (String)session.getAttribute(J_URI);
                        if (jUri != null)
                        {
                            //check if the request is for the same url as the original and restore
                            //params if it was a post
                            if (LOG.isDebugEnabled())
                                LOG.debug("auth retry {}->{}", authentication, jUri);
                            StringBuffer buf = request.getRequestURL();
                            if (request.getQueryString() != null)
                                buf.append("?").append(request.getQueryString());

                            if (jUri.equals(buf.toString()))
                            {
                                @SuppressWarnings("unchecked")
                                MultiMap<String> jPost = (MultiMap<String>)session.getAttribute(J_POST);
                                if (jPost != null)
                                {
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("auth rePOST {}->{}", authentication, jUri);
                                    baseRequest.setContentParameters(jPost);
                                }
                                session.removeAttribute(J_URI);
                                session.removeAttribute(J_METHOD);
                                session.removeAttribute(J_POST);
                            }
                        }
                    }
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth {}", authentication);
                    return authentication;
                }
            }

            // if we can't send challenge
            if (DeferredAuthentication.isDeferred(response))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("auth deferred {}", session == null ? null : session.getId());
                return Authentication.UNAUTHENTICATED;
            }

            // remember the current URI
            session = (session != null ? session : request.getSession(true));
            synchronized (session)
            {
                // But only if it is not set already, or we save every uri that leads to a login redirect
                if (session.getAttribute(J_URI) == null || isAlwaysSaveUri())
                {
                    StringBuffer buf = request.getRequestURL();
                    if (request.getQueryString() != null)
                        buf.append("?").append(request.getQueryString());
                    session.setAttribute(J_URI, buf.toString());
                    session.setAttribute(J_METHOD, request.getMethod());

                    if (MimeTypes.Type.FORM_ENCODED.is(req.getContentType()) && HttpMethod.POST.is(request.getMethod()))
                    {
                        MultiMap<String> formParameters = new MultiMap<>();
                        baseRequest.extractFormParameters(formParameters);
                        session.setAttribute(J_POST, formParameters);
                    }
                }
            }

            // send the the challenge
            String challengeUri = getChallengeUri(request);
            if (LOG.isDebugEnabled())
                LOG.debug("challenge {}->{}", session.getId(), challengeUri);
            baseResponse.sendRedirect(getRedirectCode(baseRequest.getHttpVersion()), challengeUri);

            return Authentication.SEND_CONTINUE;
        }
        catch (IOException e)
        {
            throw new ServerAuthException(e);
        }
    }

    public boolean isJSecurityCheck(String uri)
    {
        int jsc = uri.indexOf(J_SECURITY_CHECK);

        if (jsc < 0)
            return false;
        int e = jsc + J_SECURITY_CHECK.length();
        if (e == uri.length())
            return true;
        char c = uri.charAt(e);
        return c == ';' || c == '#' || c == '/' || c == '?';
    }

    public boolean isErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_errorPath));
    }

    private static int getRedirectCode(HttpVersion httpVersion)
    {
        return (httpVersion.getVersion() < HttpVersion.HTTP_1_1.getVersion()
            ? HttpServletResponse.SC_MOVED_TEMPORARILY : HttpServletResponse.SC_SEE_OTHER);
    }

    private String getRedirectUri(HttpServletRequest request)
    {
        final StringBuffer redirectUri = new StringBuffer(128);
        URIUtil.appendSchemeHostPort(redirectUri, request.getScheme(),
            request.getServerName(), request.getServerPort());
        redirectUri.append(request.getContextPath());
        redirectUri.append(J_SECURITY_CHECK);
        return redirectUri.toString();
    }

    protected String getChallengeUri(HttpServletRequest request)
    {
        HttpSession session = request.getSession();
        String antiForgeryToken;
        synchronized (session)
        {
            antiForgeryToken = (session.getAttribute(CSRF_TOKEN) == null)
                ? new BigInteger(130, new SecureRandom()).toString(32)
                : (String)session.getAttribute(CSRF_TOKEN);
            session.setAttribute(CSRF_TOKEN, antiForgeryToken);
        }

        // any custom scopes requested from configuration
        StringBuilder scopes = new StringBuilder();
        for (String s : _configuration.getScopes())
        {
            scopes.append(" ").append(s);
        }

        return _configuration.getAuthEndpoint() +
            "?client_id=" + UrlEncoded.encodeString(_configuration.getClientId(), StandardCharsets.UTF_8) +
            "&redirect_uri=" + UrlEncoded.encodeString(getRedirectUri(request), StandardCharsets.UTF_8) +
            "&scope=openid" + UrlEncoded.encodeString(scopes.toString(), StandardCharsets.UTF_8) +
            "&state=" + antiForgeryToken +
            "&response_type=code";
    }

    @Override
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser)
    {
        return true;
    }

    /**
     * This Authentication represents a just completed OpenId Connect authentication.
     * Subsequent requests from the same user are authenticated by the presents
     * of a {@link SessionAuthentication} instance in their session.
     */
    public static class OpenIdAuthentication extends UserAuthentication implements Authentication.ResponseSent
    {
        public OpenIdAuthentication(String method, UserIdentity userIdentity)
        {
            super(method, userIdentity);
        }

        @Override
        public String toString()
        {
            return "OpenId" + super.toString();
        }
    }
}
