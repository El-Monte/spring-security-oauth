package org.springframework.security.oauth2.client.filter;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.oauth2.client.UserRedirectRequiredException;
import org.springframework.security.oauth2.client.context.OAuth2ClientContextHolder;
import org.springframework.security.oauth2.client.filter.flash.ClientTokenFlashServices;
import org.springframework.security.oauth2.client.filter.flash.HttpSessionClientTokenFlashServices;
import org.springframework.security.oauth2.client.filter.state.HttpSessionStateServices;
import org.springframework.security.oauth2.client.filter.state.StateServices;
import org.springframework.security.oauth2.client.http.OAuth2AccessDeniedException;
import org.springframework.security.oauth2.client.http.OAuth2AccessTokenRequiredException;
import org.springframework.security.oauth2.client.provider.OAuth2AccessTokenProvider;
import org.springframework.security.oauth2.client.provider.OAuth2AccessTokenProviderChain;
import org.springframework.security.oauth2.client.provider.flow.client.ClientCredentialsAccessTokenProvider;
import org.springframework.security.oauth2.client.provider.flow.code.AuthorizationCodeAccessTokenProvider;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.common.DefaultThrowableAnalyzer;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.PortResolver;
import org.springframework.security.web.PortResolverImpl;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.util.ThrowableAnalyzer;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.util.Assert;

/**
 * Security filter for an OAuth2 client.
 * 
 * @author Ryan Heaton
 * @author Dave Syer
 */
public class OAuth2ClientContextFilter implements Filter, InitializingBean, MessageSourceAware {

	protected MessageSourceAccessor messages = SpringSecurityMessageSource.getAccessor();
	private OAuth2AccessTokenProvider accessTokenProvider = new OAuth2AccessTokenProviderChain(
			Arrays.<OAuth2AccessTokenProvider> asList(new AuthorizationCodeAccessTokenProvider(),
					new ClientCredentialsAccessTokenProvider()));
	private ClientTokenFlashServices rememberMeServices = new HttpSessionClientTokenFlashServices();
	private StateServices stateServices = new HttpSessionStateServices();
	private PortResolver portResolver = new PortResolverImpl();
	private ThrowableAnalyzer throwableAnalyzer = new DefaultThrowableAnalyzer();
	private RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
	private boolean redirectOnError = false;

	public void afterPropertiesSet() throws Exception {
		Assert.notNull(accessTokenProvider, "An OAuth2 access token provider must be supplied.");
		Assert.notNull(rememberMeServices, "RememberMeOAuth2TokenServices must be supplied.");
		Assert.notNull(redirectStrategy, "A redirect strategy must be supplied.");
	}

	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;
		// first set up the security context.
		OAuth2ClientContextImpl oauth2Context = new OAuth2ClientContextImpl();
		oauth2Context.setDetails(request);

		Map<String, OAuth2AccessToken> accessTokens = rememberMeServices.loadRememberedTokens(request, response);
		// Ensure session is created if necessary.  TODO: find a better way to do this
		rememberMeServices.rememberTokens(accessTokens, request, response);
		accessTokens = accessTokens == null ? new HashMap<String, OAuth2AccessToken>()
				: new HashMap<String, OAuth2AccessToken>(accessTokens);
		oauth2Context.setAccessTokens(Collections.unmodifiableMap(accessTokens));
		if (request.getParameter("error") != null) {
			HashMap<String, String> errorParams = new HashMap<String, String>();
			Enumeration parameterNames = request.getParameterNames();
			while (parameterNames.hasMoreElements()) {
				String param = (String) parameterNames.nextElement();
				errorParams.put(param, request.getParameter(param));
			}
			oauth2Context.setErrorParameters(errorParams);
		}
		oauth2Context.setAuthorizationCode(request.getParameter("code"));
		oauth2Context.setUserAuthorizationRedirectUri(calculateCurrentUri(request));
		oauth2Context.setPreservedState(stateServices.loadPreservedState(request.getParameter("state"), request,
				response));

		OAuth2ClientContextHolder.setContext(oauth2Context);

		try {
			try {
				chain.doFilter(servletRequest, servletResponse);
			} catch (Exception ex) {
				OAuth2ProtectedResourceDetails resourceThatNeedsAuthorization = checkForResourceThatNeedsAuthorization(ex);
				String neededResourceId = resourceThatNeedsAuthorization.getId();
				accessTokens.remove(neededResourceId);

				while (!accessTokens.containsKey(neededResourceId)) {
					OAuth2AccessToken accessToken;
					try {
						accessToken = accessTokenProvider.obtainNewAccessToken(resourceThatNeedsAuthorization);
						if (accessToken == null) {
							throw new IllegalStateException(
									"Access token manager returned a null access token, which is illegal according to the contract.");
						}
					} catch (UserRedirectRequiredException e) {
						redirectUser(e, request, response);
						return;
					}

					accessTokens.put(neededResourceId, accessToken);

					try {
						// try again
						if (!response.isCommitted() && !this.redirectOnError) {
							chain.doFilter(request, response);
						} else {
							// Dang. what do we do now? Best we can do is redirect.
							String redirect = request.getServletPath();
							if (request.getQueryString() != null) {
								redirect += "?" + request.getQueryString();
							}
							this.redirectStrategy.sendRedirect(request, response, redirect);
						}
					} catch (Exception e1) {
						resourceThatNeedsAuthorization = checkForResourceThatNeedsAuthorization(e1);
						neededResourceId = resourceThatNeedsAuthorization.getId();
						accessTokens.remove(neededResourceId);
					}
				}
			}
		} finally {
			OAuth2ClientContextHolder.clearContext();
			rememberMeServices.rememberTokens(accessTokens, request, response);
		}
	}

	/**
	 * Redirect the user according to the specified exception.
	 * 
	 * @param e The user redirect exception.
	 * @param request The request.
	 * @param response The response.
	 */
	protected void redirectUser(UserRedirectRequiredException e, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		if (e.getStateToPreserve() != null) {
			stateServices.preserveState(e.getStateKey(), e.getStateToPreserve(), request, response);
		}

		try {
			String redirectUri = e.getRedirectUri();
			StringBuilder builder = new StringBuilder(redirectUri);
			Map<String, String> requestParams = e.getRequestParams();
			char appendChar = redirectUri.indexOf('?') < 0 ? '?' : '&';
			for (Map.Entry<String, String> param : requestParams.entrySet()) {
				builder.append(appendChar).append(param.getKey()).append('=')
						.append(URLEncoder.encode(param.getValue(), "UTF-8"));
				appendChar = '&';
			}

			request.setAttribute("org.springframework.security.oauth2.client.UserRedirectRequiredException", e);
			this.redirectStrategy.sendRedirect(request, response, builder.toString());
		} catch (UnsupportedEncodingException uee) {
			throw new IllegalStateException(uee);
		}
	}

	/**
	 * Check the given exception for the resource that needs authorization. If the exception was not thrown because a
	 * resource needed authorization, then rethrow the exception.
	 * 
	 * @param ex The exception.
	 * @return The resource that needed authorization (never null).
	 */
	protected OAuth2ProtectedResourceDetails checkForResourceThatNeedsAuthorization(Exception ex)
			throws ServletException, IOException {
		Throwable[] causeChain = throwableAnalyzer.determineCauseChain(ex);
		OAuth2AccessTokenRequiredException ase = (OAuth2AccessTokenRequiredException) throwableAnalyzer
				.getFirstThrowableOfType(OAuth2AccessTokenRequiredException.class, causeChain);
		OAuth2ProtectedResourceDetails resourceThatNeedsAuthorization;
		if (ase != null) {
			resourceThatNeedsAuthorization = ase.getResource();
			if (resourceThatNeedsAuthorization == null) {
				throw new OAuth2AccessDeniedException(ase.getMessage());
			}
		} else {
			// Rethrow ServletExceptions and RuntimeExceptions as-is
			if (ex instanceof ServletException) {
				throw (ServletException) ex;
			}
			if (ex instanceof IOException) {
				throw (IOException) ex;
			} else if (ex instanceof RuntimeException) {
				throw (RuntimeException) ex;
			}

			// Wrap other Exceptions. These are not expected to happen
			throw new RuntimeException(ex);
		}
		return resourceThatNeedsAuthorization;
	}

	/**
	 * Calculate the current URI given the request.
	 * 
	 * @param request The request.
	 * @return The current uri.
	 */
	protected String calculateCurrentUri(HttpServletRequest request) throws UnsupportedEncodingException {
		StringBuilder queryBuilder = new StringBuilder();
		Enumeration paramNames = request.getParameterNames();
		while (paramNames.hasMoreElements()) {
			String name = (String) paramNames.nextElement();
			if (!"code".equals(name)) {
				String[] parameterValues = request.getParameterValues(name);
				if (parameterValues.length == 0) {
					queryBuilder.append(URLEncoder.encode(name, "UTF-8"));
				} else {
					for (int i = 0; i < parameterValues.length; i++) {
						String parameterValue = parameterValues[i];
						queryBuilder.append(URLEncoder.encode(name, "UTF-8")).append('=')
								.append(URLEncoder.encode(parameterValue, "UTF-8"));
						if (i + 1 < parameterValues.length) {
							queryBuilder.append('&');
						}
					}
				}
			}

			if (paramNames.hasMoreElements() && queryBuilder.length() > 0) {
				queryBuilder.append('&');
			}
		}

		return UrlUtils.buildFullRequestUrl(request.getScheme(), request.getServerName(), portResolver
				.getServerPort(request), request.getRequestURI(), queryBuilder.length() > 0 ? queryBuilder.toString()
				: null);
	}

	public void init(FilterConfig filterConfig) throws ServletException {
	}

	public void destroy() {
	}

	/**
	 * Set the message source.
	 * 
	 * @param messageSource The message source.
	 */
	public void setMessageSource(MessageSource messageSource) {
		this.messages = new MessageSourceAccessor(messageSource);
	}

	public void setAccessTokenProvider(OAuth2AccessTokenProvider accessTokenProvider) {
		this.accessTokenProvider = accessTokenProvider;
	}

	public void setClientTokenFlashServices(ClientTokenFlashServices rememberMeServices) {
		this.rememberMeServices = rememberMeServices;
	}

	public void setStateServices(StateServices stateServices) {
		this.stateServices = stateServices;
	}

	public void setThrowableAnalyzer(ThrowableAnalyzer throwableAnalyzer) {
		this.throwableAnalyzer = throwableAnalyzer;
	}

	public void setPortResolver(PortResolver portResolver) {
		this.portResolver = portResolver;
	}

	public void setRedirectStrategy(RedirectStrategy redirectStrategy) {
		this.redirectStrategy = redirectStrategy;
	}

	/**
	 * Flag to indicate that instead of executing the filter chain again, the filter should send a redirect to have the
	 * request repeated. Default to false, but users of some app server platforms (e.g. Weblogic) might find that they
	 * need this switched on to avoid problems with executing the filter chain more than once in the same request.
	 * 
	 * @param redirectOnError the flag to set (default false)
	 */
	public void setRedirectOnError(boolean redirectOnError) {
		this.redirectOnError = redirectOnError;
	}
}
