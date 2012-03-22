/*
 * Copyright 2012 Janrain, Inc.
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

package com.janrain.backplane2.server.provision;

import com.janrain.backplane.server.config.User;
import com.janrain.backplane2.server.config.Backplane2Config;
import com.janrain.backplane2.server.config.Client;
import com.janrain.backplane2.server.dao.DaoFactory;
import com.janrain.commons.supersimpledb.SimpleDBException;
import com.janrain.commons.supersimpledb.SuperSimpleDB;
import com.janrain.crypto.ChannelUtil;
import com.janrain.crypto.HmacHashUtils;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.type.MapType;
import org.codehaus.jackson.map.type.SimpleType;
import org.junit.After;
import org.junit.Before;
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
import java.util.*;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author Tom Raney
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:/spring/app-config.xml", "classpath:/spring/mvc-config.xml" })
public class ProvisioningController2Test {


    /**
	 * Initialize before every individual test method
	 */
	@Before
	public void init() throws SimpleDBException {

        handlerAdapter = applicationContext.getBean("handlerAdapter", HandlerAdapter.class);
		refreshRequestAndResponse();

        // create temporary admin user account to enable the tests to work
        user = new User();
        pw = ChannelUtil.randomString(10);
        user.put(User.Field.USER.getFieldName(), ChannelUtil.randomString(20));
        user.put(User.Field.PWDHASH.getFieldName(), HmacHashUtils.hmacHash(pw));

        superSimpleDB.store(bpConfig.getTableName(Backplane2Config.SimpleDBTables.BP_ADMIN_AUTH), User.class, user);
        
        busOwner = new User();
        busOwner.put(User.Field.USER.getFieldName(), ChannelUtil.randomString(20));
        busOwner.put(User.Field.PWDHASH.getFieldName(), HmacHashUtils.hmacHash(pw));

        client = new Client( ChannelUtil.randomString(20), pw, "http://source.com", "http://redirect.com" );
        daoFactory.getClientDAO().persist(client);
        logger.info("Created test client: " + client.getClientId());
	}

    @After
    public void cleanup() throws SimpleDBException {
        superSimpleDB.delete(bpConfig.getTableName(Backplane2Config.SimpleDBTables.BP_ADMIN_AUTH), user.getIdValue());
        superSimpleDB.delete(bpConfig.getTableName(Backplane2Config.SimpleDBTables.BP_CLIENTS), client.getIdValue());
        daoFactory.getClientDAO().delete(client.getClientId());
    }

    @Test
    public void testBusOwnerCRUD() throws Exception {

        refreshRequestAndResponse();
        // create bus owner
        String jsonUpdateBusOwner = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\"," +
                " \"configs\": [ { \"USER\":\"" + busOwner.get(User.Field.USER) + "\", \"PWDHASH\":\"" + busOwner.get(User.Field.PWDHASH) + "\"} ] }";
        logger.info("passing in json " + jsonUpdateBusOwner);
        request.setContent(jsonUpdateBusOwner.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/user/update");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientUpdate -> " + response.getContentAsString());
        assertTrue(response.getStatus() == HttpServletResponse.SC_OK);

        refreshRequestAndResponse();
        String listJson = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", \"entities\": [] }";
        logger.info("passing in json " + listJson);
        request.setContent(listJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/user/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientList() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(busOwner.get(User.Field.USER)));

        refreshRequestAndResponse();
        String deleteJson = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", " +
                            "\"entities\": [\"" + busOwner.get(User.Field.USER)+ "\"] }";
        logger.info("passing in json " + deleteJson);
        request.setContent(deleteJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/user/delete");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientList() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(busOwner.get(User.Field.USER)));

        refreshRequestAndResponse();
        logger.info("passing in json " + listJson);
        request.setContent(listJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/user/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientList() => " + response.getContentAsString());
        assertFalse(response.getContentAsString().contains(busOwner.get(User.Field.USER)));
    }

    @Test
    public void testClientCRUD() throws Exception {

        refreshRequestAndResponse();
        // create client
        String jsonUpdateClient = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\"," +
                " \"configs\": [ { \"USER\":\"" + client.getClientId() + "\", \"PWDHASH\":\"" + pw + "\"} ] }";
        logger.info("passing in json " + jsonUpdateClient);
        request.setContent(jsonUpdateClient.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/client/update");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientUpdate -> " + response.getContentAsString());
        assertTrue(response.getStatus() == HttpServletResponse.SC_OK);

        refreshRequestAndResponse();
        String listJson = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", \"entities\": [] }";
        logger.info("passing in json " + listJson);
        request.setContent(listJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/client/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientList() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(client.getClientId()));

        refreshRequestAndResponse();
        String deleteJson = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", " +
                            "\"entities\": [\"" + client.getClientId()+ "\"] }";
        logger.info("passing in json " + deleteJson);
        request.setContent(deleteJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/client/delete");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientList() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains(client.getClientId()));

        refreshRequestAndResponse();
        logger.info("passing in json " + listJson);
        request.setContent(listJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/client/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientList() => " + response.getContentAsString());
        assertFalse(response.getContentAsString().contains(client.getClientId()));
    }

    @Test
    public void testBusCRUDinvalidBusOwner() throws Exception {
        // create client
        refreshRequestAndResponse();
        String jsonUpdateBus = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\"," +
                " \"configs\": [ {\n" +
                "            \"BUS_NAME\": \"customer1\",\n" +
                "            \"OWNER\": \"busowner1\",\n" +
                "            \"RETENTION_TIME_SECONDS\": \"600\",\n" +
                "            \"RETENTION_STICKY_TIME_SECONDS\": \"28800\"\n" +
                "        } ] }";
        logger.info("passing in json " + jsonUpdateBus);
        request.setContent(jsonUpdateBus.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/bus/update");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testBusUpdate -> " + response.getContentAsString());
        assertTrue(response.getStatus() == HttpServletResponse.SC_OK);
        assertTrue(response.getContentAsString().contains("Invalid bus owner: busowner1"));
    }

    @Test
    public void testBusCRUD() throws Exception {

        // create bus owner
        refreshRequestAndResponse();
        String jsonUpdateBusOwner = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\"," +
                " \"configs\": [ { \"USER\":\"" + busOwner.get(User.Field.USER) + "\", \"PWDHASH\":\"" + busOwner.get(User.Field.PWDHASH) + "\"} ] }";
        logger.info("passing in json " + jsonUpdateBusOwner);
        request.setContent(jsonUpdateBusOwner.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/user/update");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientUpdate -> " + response.getContentAsString());
        assertTrue(response.getStatus() == HttpServletResponse.SC_OK);
        assertTrue(response.getContentAsString().contains("{\"" + busOwner.get(User.Field.USER) + "\":\"BACKPLANE_UPDATE_SUCCESS\"}"));

        // create bus
        refreshRequestAndResponse();
        String jsonUpdateBus = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\"," +
                " \"configs\": [ {\n" +
                "            \"BUS_NAME\": \"customer1\",\n" +
                "            \"OWNER\": \"" + busOwner.get(User.Field.USER) + "\",\n" +
                "            \"RETENTION_TIME_SECONDS\": \"600\",\n" +
                "            \"RETENTION_STICKY_TIME_SECONDS\": \"28800\"\n" +
                "        } ] }";
        logger.info("passing in json " + jsonUpdateBus);
        request.setContent(jsonUpdateBus.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/bus/update");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testBusUpdate -> " + response.getContentAsString());
        assertTrue(response.getStatus() == HttpServletResponse.SC_OK);
        assertTrue("Failed: " + response.getContentAsString(), response.getContentAsString().contains("{\"customer1\":\"BACKPLANE_UPDATE_SUCCESS\"}"));

        refreshRequestAndResponse();
        String listJson = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", \"entities\": [] }";
        logger.info("passing in json " + listJson);
        request.setContent(listJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/bus/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testBusList() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains("customer1"));
        assertTrue(response.getContentAsString().contains(busOwner.get(User.Field.USER)));

        refreshRequestAndResponse();
        String deleteJson = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", " +
                            "\"entities\": [\"customer1\"] }";
        logger.info("passing in json " + deleteJson);
        request.setContent(deleteJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/bus/delete");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientList() => " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains("customer1"));

        refreshRequestAndResponse();
        logger.info("passing in json " + listJson);
        request.setContent(listJson.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/bus/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testClientList() => " + response.getContentAsString());
        assertFalse(response.getContentAsString().contains("customer1"));
    }

    @Test
    public void testProvisioningCreateDelete() throws Exception {
        refreshRequestAndResponse();
        String jsonUpdateClient = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\"," +
                " \"configs\": [ { \"USER\":\"" + client.getClientId() + "\", \"PWDHASH\":\"" + pw + "\"} ] }";

        String addBusOwner = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\"," +
                " \"entities\": [ \"" + client.getClientId() + "\", \"PWDHASH\":\"" + pw + "\"} ] }";
    }

    @Test
    public void testProvisioningDeleteNonExisting() throws Exception {

        refreshRequestAndResponse();

        String delete = "{ \"entities\":[\"does\", \"not\", \"exist\"], \"admin\":\"" + user.get(User.Field.USER) + "\", \"secret\":\"" + pw + "\"}";
        request.setContent(delete.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/user/delete");
        request.setMethod("POST");

        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningDelete() -> " + response.getContentAsString());
        assertTrue(response.getContentAsString().equals("{\"does\":\"BACKPLANE_ENTRY_NOT_FOUND\",\"not\":\"BACKPLANE_ENTRY_NOT_FOUND\",\"exist\":\"BACKPLANE_ENTRY_NOT_FOUND\"}"));

        refreshRequestAndResponse();
        request.setContent(delete.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/bus/delete");
        request.setMethod("POST");

        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningDelete() -> " + response.getContentAsString());
        assertTrue(response.getContentAsString().equals("{\"does\":\"BACKPLANE_ENTRY_NOT_FOUND\",\"not\":\"BACKPLANE_ENTRY_NOT_FOUND\",\"exist\":\"BACKPLANE_ENTRY_NOT_FOUND\"}"));

        refreshRequestAndResponse();
        request.setContent(delete.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/client/delete");
        request.setMethod("POST");

        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningDelete() -> " + response.getContentAsString());
        assertTrue(response.getContentAsString().equals("{\"does\":\"BACKPLANE_ENTRY_NOT_FOUND\",\"not\":\"BACKPLANE_ENTRY_NOT_FOUND\",\"exist\":\"BACKPLANE_ENTRY_NOT_FOUND\"}"));

    }

    @Test
    public void testProvisioningGrant() throws Exception {

        refreshRequestAndResponse();

        String addGrant = "{\"grants\":{\"" + client.getClientId() + "\":\"qa-test-bus\"},\"admin\":\"" + user.get(User.Field.USER) + "\", \"secret\":\"" + pw + "\"}";
        request.setContent(addGrant.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/add");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/add -> " + response.getContentAsString());
        assertTrue("Invalid response", response.getContentAsString().equals("{\"" + client.getClientId() + "\":\"GRANT_UPDATE_SUCCESS\"}"));
        
        refreshRequestAndResponse();
        String listGrants = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", \"entities\": [ \"" + client.getClientId() + "\" ] }";
        request.setContent(listGrants.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/checkExists -> " + response.getContentAsString());
        assertTrue("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{ add("qa-test-bus");}}));

        refreshRequestAndResponse();
        request.setContent(addGrant.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/revoke");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/revoke -> " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains("GRANT_UPDATE_SUCCESS"));

        refreshRequestAndResponse();
        request.setContent(listGrants.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/checkNonExists -> " + response.getContentAsString());
        assertFalse("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{ add("qa-test-bus");}}));
    }
    
    @Test
    public void testProvisioningGrantMultipleBuses() throws Exception {

        refreshRequestAndResponse();

        String grantRequestString = "{\"grants\":{\"" + client.getClientId() + "\":\"qa-test-bus1 qa-test-bus2\"},\"admin\":\"" + user.get(User.Field.USER) + "\", \"secret\":\"" + pw + "\"}";
        request.setContent(grantRequestString.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/add");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/add -> " + response.getContentAsString());
        assertTrue("Invalid response", response.getContentAsString().equals("{\"" + client.getClientId() + "\":\"GRANT_UPDATE_SUCCESS\"}"));
        
        refreshRequestAndResponse();
        String listGrants = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", \"entities\": [ \"" + client.getClientId() + "\" ] }";
        request.setContent(listGrants.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/checkExists -> " + response.getContentAsString());
        assertTrue("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{
            add("qa-test-bus1");
            add("qa-test-bus2");
        }}));

        refreshRequestAndResponse();
        request.setContent(grantRequestString.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/revoke");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/revoke -> " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains("GRANT_UPDATE_SUCCESS"));

        refreshRequestAndResponse();
        request.setContent(listGrants.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/checkNonExists -> " + response.getContentAsString());
        assertFalse("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{ add("qa-test-bus1");}}));
        assertFalse("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{ add("qa-test-bus2");}}));
    }
    
    @Test
    public void testProvisioningGrantMultipleBusesRevokeOneAtATime() throws Exception {

        refreshRequestAndResponse();

        String grantRequestString = "{\"grants\":{\"" + client.getClientId() + "\":\"qa-test-bus1 qa-test-bus2\"},\"admin\":\"" + user.get(User.Field.USER) + "\", \"secret\":\"" + pw + "\"}";
        request.setContent(grantRequestString.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/add");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/add -> " + response.getContentAsString());
        assertTrue("Invalid response", response.getContentAsString().equals("{\"" + client.getClientId() + "\":\"GRANT_UPDATE_SUCCESS\"}"));
        
        refreshRequestAndResponse();
        String listGrants = "{ \"admin\": \"" + user.get(User.Field.USER) + "\", \"secret\": \"" + pw + "\", \"entities\": [ \"" + client.getClientId() + "\" ] }";
        request.setContent(listGrants.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/checkExists -> " + response.getContentAsString());
        assertTrue("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{
            add("qa-test-bus1");
            add("qa-test-bus2");
        }}));

        refreshRequestAndResponse();
        String revoke1request = "{\"grants\":{\"" + client.getClientId() + "\":\"qa-test-bus1\"},\"admin\":\"" + user.get(User.Field.USER) + "\", \"secret\":\"" + pw + "\"}";
        request.setContent(revoke1request.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/revoke");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/revoke -> " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains("GRANT_UPDATE_SUCCESS"));

        refreshRequestAndResponse();
        request.setContent(listGrants.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/checkNonExists -> " + response.getContentAsString());
        assertFalse("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{ add("qa-test-bus1");}}));
        assertTrue("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{ add("qa-test-bus2");}}));

        refreshRequestAndResponse();
        String revoke2request = "{\"grants\":{\"" + client.getClientId() + "\":\"qa-test-bus2\"},\"admin\":\"" + user.get(User.Field.USER) + "\", \"secret\":\"" + pw + "\"}";
        request.setContent(revoke2request.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/revoke");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/revoke -> " + response.getContentAsString());
        assertTrue(response.getContentAsString().contains("GRANT_UPDATE_SUCCESS"));

        refreshRequestAndResponse();
        request.setContent(listGrants.getBytes());
        request.addHeader("Content-type", "application/json");
        request.setRequestURI("/v2/provision/grant/list");
        request.setMethod("POST");
        handlerAdapter.handle(request, response, controller);
        logger.info("testProvisioningGrant()/checkNonExists -> " + response.getContentAsString());
        assertFalse("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{ add("qa-test-bus1");}}));
        assertFalse("Invalid response", checkGrantExists(response, client.getClientId(), new ArrayList<String>() {{ add("qa-test-bus2");}}));
    }
    
    // - PRIVATE

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private ProvisioningController2 controller;

    @Inject
    private SuperSimpleDB superSimpleDB;

    @Inject
    private Backplane2Config bpConfig;

    @Inject
    private DaoFactory daoFactory;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private HandlerAdapter handlerAdapter;

    private static final Logger logger = Logger.getLogger(ProvisioningController2Test.class);
    private User user;
    private User busOwner;
    private Client client;
    private String pw;


    private void refreshRequestAndResponse() {
		request = new MockHttpServletRequest();
		response = new MockHttpServletResponse();
	}

    /** return true if clientToCheck has grants for all busesToCheck */
    private boolean checkGrantExists(MockHttpServletResponse grantListResponse, String clientToCheck, List<String> busesToCheck) throws Exception {

        Map<String,Map<String,String>> listResult = new ObjectMapper().readValue(grantListResponse.getContentAsString(), MapType.construct(Map.class, SimpleType.construct(String.class),
                MapType.construct(Map.class, SimpleType.construct(String.class), SimpleType.construct(String.class))));

        Set<String> busesGranted = new HashSet<String>();
        Map<String, String> grantsForClient = listResult.get(clientToCheck);
        if (grantsForClient != null) {
            for(String grantId : grantsForClient.keySet()) {
                busesGranted.addAll(Arrays.asList(grantsForClient.get(grantId).split(" ")));
            }
        }

        return busesGranted.containsAll(busesToCheck);
    }
}
