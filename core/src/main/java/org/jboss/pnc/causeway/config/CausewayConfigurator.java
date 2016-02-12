/**
 * Copyright (C) 2015 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.causeway.config;

import org.apache.commons.lang.StringUtils;
import org.commonjava.propulsor.boot.BootOptions;
import org.commonjava.propulsor.config.Configurator;
import org.commonjava.propulsor.config.ConfiguratorException;
import org.commonjava.web.config.ConfigurationException;
import org.commonjava.web.config.dotconf.DotConfConfigurationReader;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.apache.commons.lang.StringUtils.isNotEmpty;

/**
 * Created by jdcasey on 11/10/15.
 */
@ApplicationScoped
public class CausewayConfigurator
        implements Configurator
{
    @Inject
    private CausewayConfig causewayConfig;

    @Override
    public void load( BootOptions options )
            throws ConfiguratorException
    {
        String config = options.getConfig();
        if ( StringUtils.isEmpty( config ) )
        {
            config = CausewayConfig.DEFAULT_CAUSEWAY_CONFIG;
        }

        File configFile = new File( config );
        if ( configFile.isDirectory() )
        {
            configFile = new File( configFile, "main.conf" );
        }

        System.setProperty( CausewayConfig.CAUSEWAY_CONFIG_DIR_SYSPROP, configFile.getParentFile().getAbsolutePath() );

        if ( !configFile.exists() )
        {
            // TODO: Make resilient enough to write default configs.
            throw new ConfiguratorException( "Missing configuration: %s", configFile );
        }

        File dir = configFile.getAbsoluteFile().getParentFile();
        causewayConfig.setConfigDir( dir );

        try (InputStream in = new FileInputStream( configFile ))
        {
            new DotConfConfigurationReader( causewayConfig ).loadConfiguration( in );
        }
        catch ( ConfigurationException | IOException e )
        {
            throw new ConfiguratorException( "Failed to read configuration: %s. Reason: %s", e, configFile,
                                             e.getMessage() );
        }

        causewayConfig.configurationDone();

        String validationErrors = causewayConfig.getValidationErrors();
        if ( isNotEmpty( validationErrors ) )
        {
            throw new ConfiguratorException( "Causeway configuration is not complete!\n\n%s", validationErrors );
        }
    }
}
