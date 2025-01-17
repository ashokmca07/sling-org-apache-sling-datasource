/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.datasource.internal;

import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import javax.naming.Context;
import java.util.Dictionary;
import java.util.Hashtable;

import static org.junit.Assert.assertNotNull;

public class JNDIDataSourceFactoryTest {

    @Rule
    public final OsgiContext osgiContext = new OsgiContext();

    @After
    public void tearDown() {
        System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
    }

    @Test
    public void testIfServiceActive() {
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put("datasource.name", "ds");
        properties.put("datasource.jndi.name", "java:/comp/env/ds");

        defaultJNDIProperties(properties);
        JNDIDataSourceFactory jndiDataSourceFactory = osgiContext.registerInjectActivateService(new JNDIDataSourceFactory(), properties);

        assertNotNull(jndiDataSourceFactory);
    }

    @Test(expected = RuntimeException.class)
    public void testIllegalStateException() {
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put("datasource.name", "ds");
        properties.put("datasource.jndi.name", "java:/comp/env");

        defaultJNDIProperties(properties);
        osgiContext.registerInjectActivateService(new JNDIDataSourceFactory(), properties);
    }

    private void defaultJNDIProperties(Dictionary<String, Object> properties) {
        properties.put("jndi.properties", new String[]{
                "java.naming.factory.initial=org.osjava.sj.SimpleContextFactory",
                "org.osjava.sj.delimiter=.",
                "jndi.syntax.separator=/",
                "org.osjava.sj.space=java:/comp/env",
                "org.osjava.sj.jndi.shared=true",
                "org.osjava.sj.root=src/test/resources/datasource.properties"
        });
    }

}