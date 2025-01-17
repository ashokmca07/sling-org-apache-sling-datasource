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

import java.util.Arrays;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.stream.Collectors;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.sling.datasource.internal.DataSourceFactory.checkArgument;

@Component(
        immediate = true,
        name = JNDIDataSourceFactory.NAME,
        configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = JNDIDataSourceFactory.Config.class, factory = true)
public class JNDIDataSourceFactory {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String NAME = "org.apache.sling.datasource.JNDIDataSourceFactory";
    static final String PROP_DATASOURCE_NAME = "datasource.name";
    static final String PROP_DS_SVC_PROP_NAME = "datasource.svc.prop.name";
    static final String PROP_DS_JNDI_NAME = "datasource.jndi.name";
    static final String PROP_JNDI_PROPS = "jndi.properties";

    @SuppressWarnings("java:S100")
    @ObjectClassDefinition(name = "Apache Sling JNDI DataSource",
            description = "Registers a DataSource instance with OSGi ServiceRegistry which is looked up from the JNDI")
    public @interface Config {

        @AttributeDefinition
        String datasource_name() default "";

        @AttributeDefinition
        String datasource_svc_prop_name() default PROP_DATASOURCE_NAME;

        @AttributeDefinition(name = "JNDI Name (*)", description = "JNDI location name used to perform DataSource instance lookup")
        String datasource_jndi_name() default PROP_DATASOURCE_NAME;

        @AttributeDefinition(name = "JNDI Properties", description = "Set the environment for the JNDI InitialContext i.e. properties passed on to " +
                "InitialContext for performing the JNDI instance lookup. Each row form a map entry where each row format be propertyName=property" +
                " e.g. java.naming.factory.initial=exampleFactory", cardinality = 1024)
        String[] jndi_properties() default {};
    }

    private ServiceRegistration dsRegistration;

    @Activate
    protected void activate(BundleContext bundleContext, Config config) throws Exception {
        String name = config.datasource_name();
        String jndiName = config.datasource_jndi_name();

        checkArgument(name != null, "DataSource name must be specified via [%s] property", PROP_DATASOURCE_NAME);
        checkArgument(jndiName != null, "DataSource JNDI name must be specified via [%s] property", PROP_DS_JNDI_NAME);

        DataSource dataSource = lookupDataSource(jndiName, config);
        String svcPropName = config.datasource_svc_prop_name();

        Dictionary<String, Object> svcProps = new Hashtable<>();
        svcProps.put(svcPropName, name);
        svcProps.put(Constants.SERVICE_VENDOR, "Apache Software Foundation");
        svcProps.put(Constants.SERVICE_DESCRIPTION, "DataSource service looked up from " + jndiName);
        dsRegistration = bundleContext.registerService(javax.sql.DataSource.class, dataSource, svcProps);

        log.info("Registered DataSource [{}] looked up from JNDI at [{}]", name, jndiName);
    }

    @Deactivate
    protected void deactivate() {
        if (dsRegistration != null) {
            dsRegistration.unregister();
            dsRegistration = null;
        }
    }

    private DataSource lookupDataSource(String jndiName, Config config) throws NamingException {
        Hashtable<String, String> jndiProps = createJndiEnv(config);
        Context context = null;
        try {
            log.info("Looking up DataSource [{}] with InitialContext env [{}]", jndiName, jndiProps);
            context = new InitialContext(jndiProps);
            Object lookup = context.lookup(jndiName);
            if (lookup == null) {
                throw new NameNotFoundException("JNDI object with [" + jndiName + "] not found");
            }

            if (!(lookup instanceof DataSource)) {
                throw new IllegalStateException("JNDI object of type " + lookup.getClass() +
                        "is not an instance of javax.sql.DataSource");
            }

            return (DataSource) lookup;
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private Hashtable<String, String> createJndiEnv(Config config) {
        Hashtable<String, String> jndiEnvProps = Arrays.asList(config.jndi_properties())
                .stream()
                .map(element -> element.split("="))
                .filter(element -> element[1] != null && !element[1].isEmpty())
                .collect(Collectors.toMap(element -> element[0], element -> element[1], (a, b) -> a, Hashtable::new));

        return jndiEnvProps;
    }
}
