package com.janrain.server;


import com.janrain.backplane.server.BackplaneController;
import com.janrain.backplane.server.Code;
import com.janrain.backplane.server.Token;
import com.janrain.backplane.server.config.BackplaneConfig;
import com.janrain.backplane.server.config.Client;
import com.janrain.backplane.server.config.User;
import com.janrain.commons.supersimpledb.SimpleDBException;
import com.janrain.commons.supersimpledb.SuperSimpleDB;
import com.janrain.crypto.ChannelUtil;
import org.apache.catalina.util.Base64;
import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.servlet.HandlerAdapter;

import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;

import java.util.Date;

import static org.junit.Assert.*;

/**
 * @author Tom Raney
 */

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:/spring/app-config.xml", "classpath:/spring/mvc-config.xml" })
public class TestServer {

    @Inject
	private ApplicationContext applicationContext;

    @Inject
	private BackplaneController controller;

    @Inject
    private SuperSimpleDB superSimpleDB;

    @Inject
    private BackplaneConfig bpConfig;

    private static final Logger logger = Logger.getLogger(BackplaneController.class);

    static final String OK_RESPONSE = "{\"stat\":\"ok\"}";
    static final String ERR_RESPONSE = "\"error\":";

    private MockHttpServletRequest request;
	private MockHttpServletResponse response;
    private HandlerAdapter handlerAdapter;

    /**
	 * Initialize before every individual test method
	 */
	@Before
	public void init() {
        assertNotNull(applicationContext);
        handlerAdapter = applicationContext.getBean("handlerAdapter", HandlerAdapter.class);
		refreshRequestAndResponse();
	}

    private Client createTestClient() throws SimpleDBException {
        Client client = new Client("random_id", "secret", "redirect_uri");
        superSimpleDB.store(bpConfig.getClientsTableName(), Client.class, client);
        return client;
    }


    private void refreshRequestAndResponse() {
		request = new MockHttpServletRequest();
		response = new MockHttpServletResponse();
	}

    @Test
    public void testChannelGeneration() {
        String channel = ChannelUtil.randomString(1000);
        logger.debug(channel);
        assertTrue(Base64.isBase64(channel));
    }

    @Test()
    public void testTokenEndPointAnonymousWithClientSecret() throws Exception {
        //satisfy 13.1.1
        refreshRequestAndResponse();
        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", "anonymous");
        request.setParameter("grant_type", "client_credentials");
        //shouldn't contain the client_secret below
        request.setParameter("client_secret","meh");
        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointAnonymousWithClientSecret() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
    }


    @Test()
    public void testTokenEndPointAnonymousTokenRequest() throws Exception {
        //satisfy 13.1.1

        //  should return the form:
        //  {
        //      "access_token": "l5feG0KjdXTpgDAfOvN6pU6YWxNb7qyn",
        //      "expires_in":3600,
        //      "token_type": "Bearer",
        //      "backplane_channel": "Tm5FUzstWmUOdp0xU5UW83r2q9OXrrxt"
        // }

        refreshRequestAndResponse();

        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", "anonymous");
        request.setParameter("grant_type", "client_credentials");
        request.setParameter("client_secret","");

        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointAnonymousTokenRequest() => " + response.getContentAsString());
        //assertFalse(response.getContentAsString().contains(ERR_RESPONSE));

        assertTrue("Invalid response: " + response.getContentAsString(), response.getContentAsString().
                matches("[{]\\s*\"access_token\":\\s*\".{20}+\",\\s*" +
                        "\"expires_in\":\\s*3600,\\s*" +
                        "\"token_type\":\\s*\"Bearer\",\\s*" +
                        "\"backplane_channel\":\\s*\".{32}+\"\\s*[}]"));


    }

    @Test()
    public void testTokenEndPointClientTokenRequestInvalidCode() throws Exception {

        refreshRequestAndResponse();
        Client client = createTestClient();
        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", client.get(User.Field.USER));
        request.setParameter("grant_type", "code");

        //will fail because the code below is not valid
        request.setParameter("code", "meh");
        request.setParameter("client_secret", client.get(User.Field.PWDHASH));
        request.setParameter("redirect_uri", client.get(Client.ClientField.REDIRECT_URI));
        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointClientTokenRequestInvalidCode() => " + request.toString() + " => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));

    }


    @Test()
    public void testTokenEndPointClientTokenRequest() throws Exception {

        //  should return the form:
        //  {
        //      "access_token":"l5feG0KjdXTpgDAfOvN6pU6YWxNb7qyn",
        //      "token_type":"Bearer"
        //  }

        refreshRequestAndResponse();
        Client client = createTestClient();

        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", client.get(User.Field.USER));
        request.setParameter("grant_type", "code");

        //create code for test
        Code code = new Code("test");
        superSimpleDB.store(bpConfig.getCodeTableName(), Code.class, code);

        request.setParameter("code", code.getIdValue());
        request.setParameter("client_secret", client.get(User.Field.PWDHASH));
        request.setParameter("redirect_uri", client.get(Client.ClientField.REDIRECT_URI));
        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointClientTokenRequest() => " + response.getContentAsString());
        //assertFalse(response.getContentAsString().contains(ERR_RESPONSE));

        assertTrue("Invalid response: " + response.getContentAsString(), response.getContentAsString().
                matches("[{]\\s*\"access_token\":\\s*\".{20}+\",\\s*" +
                        "\"token_type\":\\s*\"Bearer\"\\s*[}]"));


    }

    @Test()
    public void TryToUseMalformedScopeTest() throws Exception {

        refreshRequestAndResponse();
        Client client = createTestClient();

        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", client.get(User.Field.USER));
        request.setParameter("grant_type", "code");

        //create code for test
        Code code = new Code("test");
        superSimpleDB.store(bpConfig.getCodeTableName(), Code.class, code);

        request.setParameter("code", code.getIdValue());
        request.setParameter("client_secret", client.get(User.Field.PWDHASH));
        request.setParameter("redirect_uri", client.get(Client.ClientField.REDIRECT_URI));
        request.setParameter("scope", "bus;mybus.com bus:yourbus.com");
        handlerAdapter.handle(request, response, controller);
        logger.debug("TryToUseMalformedScopeTest() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));

        // try again with anonymous access with privileged use of payload
        request.setParameter("client_id", Token.ANONYMOUS);
        request.setParameter("client_secret", "");
        request.setParameter("scope", "payload.blah.blah");
        handlerAdapter.handle(request, response, controller);
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
        logger.debug("TryToUseMalformedScopetest() => " + response.getContentAsString());

    }

    @Test()
    public void TryToUseInvalidScopeTest() throws Exception {

        refreshRequestAndResponse();
        Client client = createTestClient();

        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", client.get(User.Field.USER));
        request.setParameter("grant_type", "code");

        //create code for test
        Code code = new Code("mybus.com");
        superSimpleDB.store(bpConfig.getCodeTableName(), Code.class, code);

        request.setParameter("code", code.getIdValue());
        request.setParameter("client_secret", client.get(User.Field.PWDHASH));
        request.setParameter("redirect_uri", client.get(Client.ClientField.REDIRECT_URI));
        request.setParameter("scope", "bus:mybus.com bus:yourbus.com");
        handlerAdapter.handle(request, response, controller);
        logger.debug("TryToUseInvalidScopeTest() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
    }



    @Test()
    public void TryToUseExpiredCode() throws Exception {
        refreshRequestAndResponse();
        Client client = createTestClient();

        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", client.get(User.Field.USER));
        request.setParameter("grant_type", "code");

        //create expired code for test
        Code code = new Code("test",new Date());
        superSimpleDB.store(bpConfig.getCodeTableName(), Code.class, code);

        request.setParameter("code", code.getIdValue());
        request.setParameter("client_secret", client.get(User.Field.PWDHASH));
        request.setParameter("redirect_uri", client.get(Client.ClientField.REDIRECT_URI));
        handlerAdapter.handle(request, response, controller);
        logger.debug("TryToUseExpiredCode() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
    }

    @Test()
    public void testTokenEndPointNoURI() throws Exception {
        refreshRequestAndResponse();
        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", "meh");
        request.setParameter("grant_type", "code");

        //create code for test
        Code code = new Code("test");
        superSimpleDB.store(bpConfig.getCodeTableName(), Code.class, code);

        request.setParameter("code", code.getIdValue());

        //will fail because no redirect_uri value is included
        request.setParameter("redirect_uri","");
        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointNoURI() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
    }

    @Test()
    public void testTokenEndPointNoClientSecret() throws Exception {
        refreshRequestAndResponse();
        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", "meh");
        request.setParameter("grant_type", "client_credentials");
        //will fail because no client_secret is included
        request.setParameter("client_secret","");
        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointNoClientSecret() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
    }


    @Test()
    public void testTokenEndPointEmptyCode() throws Exception {
        refreshRequestAndResponse();
        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", "meh");
        request.setParameter("grant_type", "code");
        //will fail because no code value is included
        request.setParameter("code","");
        request.setParameter("redirect_uri","meh");
        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointEmptyCode() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
    }

    @Test()
    public void testTokenEndPointBadGrantType() throws Exception {
        refreshRequestAndResponse();
        request.setRequestURI("/token");
        request.setMethod("POST");
        request.setParameter("client_id", "meh");
        //will fail because bad grant type included
        request.setParameter("grant_type", "unexpected_value");
        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointBadGrantType() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
    }

    @Test()
    public void testTokenEndPointNoParams() throws Exception {
        // test empty parameters submitted to the token endpoint
        refreshRequestAndResponse();
        request.setRequestURI("/token");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.debug("testTokenEndPointNoParams() => " + response.getContentAsString());

        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
        assertTrue(response.getStatus() == HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test()
    public void testMessageEndPoint() throws Exception {
        refreshRequestAndResponse();

        request.setRequestURI("/message/" + 1);
        request.setMethod("GET");
        handlerAdapter.handle(request, response, controller);
        logger.debug("testMessageEndPoint()  => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(ERR_RESPONSE));
    }








}
