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

import org.apache.tomcat.jdbc.pool.ConnectionPool;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolConfiguration;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.sling.datasource.internal.JNDIDataSourceFactory.PROP_DATASOURCE_NAME;

@Component(
        name = DataSourceFactory.NAME,
        configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = DataSourceFactoryConfig.class, factory = true)
public class DataSourceFactory {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final String NAME = "org.apache.sling.datasource.DataSourceFactory";
    static final String PROP_DEFAULTAUTOCOMMIT = "defaultAutoCommit";
    static final String PROP_DEFAULTREADONLY = "defaultReadOnly";
    static final String PROP_DEFAULTTRANSACTIONISOLATION = "defaultTransactionIsolation";
    static final String PROP_DATASOURCE_SVC_PROPS = "datasource.svc.properties";
    /**
     * Value indicating default value should be used. if the value is set to
     * this value then that value would be treated as null
     */
    static final String DEFAULT_VAL = "default";

    /**
     * Property names where we need to treat value 'default' as null
     */
    private static final Set<String> PROPS_WITH_DFEAULT =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    PROP_DEFAULTAUTOCOMMIT,
                    PROP_DEFAULTREADONLY,
                    PROP_DEFAULTTRANSACTIONISOLATION
            )));

    @Reference
    private DriverRegistry driverRegistry;

    private String name;

    private String svcPropName;

    private ObjectName jmxName;

    private ServiceRegistration dsRegistration;

    private DataSource dataSource;

    private BundleContext bundleContext;

    @Activate
    protected void activate(BundleContext bundleContext, DataSourceFactoryConfig config, Map<String, ?> properties) throws Exception {
        this.bundleContext = bundleContext;
        name = getDataSourceName(config);

        checkArgument(name != null, "DataSource name must be specified via [%s] property", PROP_DATASOURCE_NAME);
        dataSource = new LazyJmxRegisteringDataSource(createPoolConfig(config, properties));

        svcPropName = getSvcPropName(config);
        registerDataSource(svcPropName);

        log.info("Created DataSource [{}] with properties {}", name, dataSource.getPoolProperties().toString());
    }

    @Modified
    protected void modified(DataSourceFactoryConfig config, Map<String, ?> properties) throws Exception {
        String name = getDataSourceName(config);
        String svcPropName = getSvcPropName(config);

        if (!this.name.equals(name) || !this.svcPropName.equals(svcPropName)) {
            log.info("Change in datasource name/service property name detected. DataSource would be recreated");
            deactivate();
            activate(bundleContext, config, properties);
            return;
        }

        //Other modifications can be applied at runtime itself
        //Tomcat Connection Pool is decoupled from DataSource so can be closed and reset
        dataSource.setPoolProperties(createPoolConfig(config, properties));
        closeConnectionPool();
        dataSource.createPool();
        log.info("Updated DataSource [{}] with properties {}", name, dataSource.getPoolProperties().toString());
    }

    @Deactivate
    protected void deactivate() {
        if (dsRegistration != null) {
            dsRegistration.unregister();
            dsRegistration = null;
        }

        closeConnectionPool();
        dataSource = null;
    }

    private void closeConnectionPool() {
        unregisterJmx();
        dataSource.close();
    }

    private PoolConfiguration createPoolConfig(DataSourceFactoryConfig config, Map<String, ?> properties) {
        Properties props = new Properties();

        //Copy the other properties first
        Map<String, String> otherProps = Arrays.asList(config.datasource_svc_properties())
                .stream()
                .map(element -> element.split("="))
                .filter(element -> element[1] != null && !element[1].isEmpty())
                .collect(Collectors.toMap(element -> element[0], element -> element[1]));

        for (Map.Entry<String, String> e : otherProps.entrySet()) {
            set(e.getKey(), e.getValue(), props);
        }

        props.setProperty(org.apache.tomcat.jdbc.pool.DataSourceFactory.OBJECT_NAME, name);

        for (String propName : DummyDataSourceFactory.getPropertyNames()) {
            if (properties.get(propName) != null) {
                String value = properties.get(propName).toString();
                set(propName, value, props);
            }
        }

        //Specify the DataSource such that connection creation logic is handled
        //by us where we take care of OSGi env
        PoolConfiguration poolProperties = org.apache.tomcat.jdbc.pool.DataSourceFactory.parsePoolProperties(props);
        poolProperties.setDataSource(createDriverDataSource(poolProperties));

        return poolProperties;
    }

    private DriverDataSource createDriverDataSource(PoolConfiguration poolProperties) {
        return new DriverDataSource(poolProperties, driverRegistry, bundleContext, this);
    }

    private void registerDataSource(String svcPropName) {
        Dictionary<String, Object> svcProps = new Hashtable<>();
        svcProps.put(svcPropName, name);
        svcProps.put(Constants.SERVICE_VENDOR, "Apache Software Foundation");
        svcProps.put(Constants.SERVICE_DESCRIPTION, "DataSource service based on Tomcat JDBC");
        dsRegistration = bundleContext.registerService(javax.sql.DataSource.class, dataSource, svcProps);
    }

    private void registerJmx(ConnectionPool pool) throws SQLException {
        org.apache.tomcat.jdbc.pool.jmx.ConnectionPool jmxPool = pool.getJmxPool();
        if (jmxPool == null) {
            //jmx not enabled
            return;
        }
        Hashtable<String, String> table = new Hashtable<>();
        table.put("type", "ConnectionPool");
        table.put("class", javax.sql.DataSource.class.getName());
        table.put("name", ObjectName.quote(name));

        try {
            jmxName = new ObjectName("org.apache.sling", table);
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            mbs.registerMBean(jmxPool, jmxName);
        } catch (Exception e) {
            log.warn("Error occurred while registering the JMX Bean for " +
                    "connection pool with name {}", jmxName, e);
        }
    }

    ConnectionPool getPool() {
        return dataSource.getPool();
    }

    private void unregisterJmx() {
        try {
            if (jmxName != null) {
                MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
                mbs.unregisterMBean(jmxName);
            }
        } catch (InstanceNotFoundException ignore) {
            // NOOP
        } catch (Exception e) {
            log.error("Unable to unregister JDBC pool with JMX", e);
        }
    }

    //~----------------------------------------< Config Handling >

    static String getDataSourceName(DataSourceFactoryConfig config) {
        return config.datasource_name();
    }

    static String getSvcPropName(DataSourceFactoryConfig config) {
        return config.datasource_svc_prop_name();
    }

    private static void set(String name, String value, Properties props) {
        if (PROPS_WITH_DFEAULT.contains(name) && DEFAULT_VAL.equals(value)) {
            value = null;
        }

        if (value != null) {
            value = value.trim();
        }

        if (value != null && !value.isEmpty()) {
            props.setProperty(name, value);
        }
    }

    static void checkArgument(boolean expression,
                                      String errorMessageTemplate,
                                      Object... errorMessageArgs) {
        if (!expression) {
            throw new IllegalArgumentException(
                    String.format(errorMessageTemplate, errorMessageArgs));
        }
    }

    private class LazyJmxRegisteringDataSource extends org.apache.tomcat.jdbc.pool.DataSource {
        private volatile boolean initialized;

        public LazyJmxRegisteringDataSource(PoolConfiguration poolProperties) {
            super(poolProperties);
        }

        @Override
        public ConnectionPool createPool() throws SQLException {
            ConnectionPool pool = super.createPool();
            registerJmxLazily(pool);
            return pool;
        }

        @Override
        public void close() {
            initialized = false;
            super.close();
        }

        private void registerJmxLazily(ConnectionPool pool) throws SQLException {
            if (!initialized) {
                synchronized (this) {
                    if (initialized) {
                        return;
                    }
                    DataSourceFactory.this.registerJmx(pool);
                    initialized = true;
                }
            }
        }
    }

    /**
     * Dummy impl to enable access to protected fields
     */
    private static class DummyDataSourceFactory extends org.apache.tomcat.jdbc.pool.DataSourceFactory {
        static String[] getPropertyNames() {
            return ALL_PROPERTIES;
        }
    }
}
