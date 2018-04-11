/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.rest;

import java.util.concurrent.Callable;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.processors.authentication.IgniteAccessControlException;
import org.apache.ignite.internal.processors.authentication.IgniteAuthenticationProcessor;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.GridTestUtils;

import static org.apache.ignite.configuration.WALMode.NONE;

/**
 * Test REST with enabled authentication.
 */
public class JettyRestProcessorAuthenticationSelfTest extends JettyRestProcessorUnsignedSelfTest {
    /** */
    private static final String DFLT_USER = "ignite";

    /** */
    private static final String DFLT_PWD = "ignite";

    /** */
    private String tok = "";

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", true);

        super.beforeTestsStarted();
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        // Authenticate and extract token.
        if (F.isEmpty(tok)) {
            String ret = content(null, GridRestCommand.AUTHENTICATE,
                "user", DFLT_USER,
                "password", DFLT_PWD);

            int p1 = ret.indexOf("sessionToken");
            int p2 = ret.indexOf('"', p1 + 16);

            tok = ret.substring(p1 + 15, p2);
        }
    }

    /** {@inheritDoc} */
    @Override protected boolean securityEnabled() {
        return true;
    }

    /** {@inheritDoc} */
    @Override protected void initCache() {
        // We need to activate cluster.
        grid(0).cluster().active(true);

        super.initCache();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setAuthenticationEnabled(true);

        // For now persistence is needed because of current authentication implementation.
        DataStorageConfiguration dsCfg = new DataStorageConfiguration()
            .setDefaultDataRegionConfiguration(new DataRegionConfiguration()
                .setMaxSize(100 * 1024 * 1024))
            .setWalMode(NONE);

        DataRegionConfiguration testDataRegionCfg = new DataRegionConfiguration();
        testDataRegionCfg.setName("testDataRegion");
        testDataRegionCfg.setMaxSize(10 * 1024 * 1024);

        DataRegionConfiguration authDataRegionCfg = new DataRegionConfiguration();
        authDataRegionCfg.setName("authDataRegion")
            .setMaxSize(10 * 1024 * 1024)
            .setPersistenceEnabled(true);

        dsCfg.setDataRegionConfigurations(testDataRegionCfg, authDataRegionCfg);

        cfg.setDataStorageConfiguration(dsCfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected String restUrl() {
        String url = super.restUrl();

        if (!F.isEmpty(tok))
            url += "sessionToken=" + tok + "&";

        return url;
    }

    /**
     * @throws Exception If failed.
     */
    public void testAuthenticationCommand() throws Exception {
        String ret = content(null, GridRestCommand.AUTHENTICATE);

        assertResponseSucceeded(ret, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testInvalidSessionToken() throws Exception {
        tok = null;

        String ret = content(null, GridRestCommand.VERSION);

        assertResponseContainsError(ret, "Failed to handle request - session token not found or invalid");

        tok = "InvalidToken";

        ret = content(null, GridRestCommand.VERSION);

        assertResponseContainsError(ret, "Failed to handle request - session token not found or invalid");

        tok = "26BE027D32CC42329DEC92D517B44E9E";

        ret = content(null, GridRestCommand.VERSION);

        assertResponseContainsError(ret, "Failed to handle request - unknown session token (maybe expired session)");

        tok = null; // Cleanup token for next tests.
    }

    /**
     * @throws Exception If failed.
     */
    public void testWithToken() throws Exception {
        String ret = content(null, GridRestCommand.VERSION);

        assertResponseSucceeded(ret, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testAddUpdateRemoveUser() throws Exception {
        // Add user.
        String ret = content(null, GridRestCommand.ADD_USER,
            "user", "user1",
            "password", "password1");

        JsonNode res = jsonResponse(ret);

        assertTrue(res.asBoolean());

        IgniteAuthenticationProcessor auth = grid(0).context().authentication();

        assertNotNull(auth.authenticate("user1", "password1"));

        // Update user password.
        ret = content(null, GridRestCommand.UPDATE_USER,
            "user", "user1",
            "password", "password2");

        res = jsonResponse(ret);

        assertTrue(res.asBoolean());

        assertNotNull(auth.authenticate("user1", "password2"));

        // Remove user.
        ret = content(null, GridRestCommand.REMOVE_USER,
            "user", "user1");

        res = jsonResponse(ret);

        assertTrue(res.asBoolean());

        GridTestUtils.assertThrows(log, new Callable<Object>() {
            @Override public Object call() throws Exception {
                grid(0).context().authentication().authenticate("user1", "password1");

                return null;
            }
        }, IgniteAccessControlException.class, "The user name or password is incorrect");
    }
}
