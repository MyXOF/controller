/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.netconf.confignetconfconnector.operations.editconfig;

import java.util.Collections;
import java.util.Map;
import org.opendaylight.controller.config.util.ConfigTransactionClient;
import org.opendaylight.controller.netconf.confignetconfconnector.exception.NetconfConfigHandlingException;
import org.opendaylight.controller.netconf.confignetconfconnector.mapping.attributes.fromxml.AttributeConfigElement;
import org.opendaylight.controller.netconf.confignetconfconnector.mapping.config.ServiceRegistryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoneEditConfigStrategy implements EditConfigStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(NoneEditConfigStrategy.class);

    @Override
    public void executeConfiguration(String module, String instance, Map<String, AttributeConfigElement> configuration,
                                     ConfigTransactionClient ta, ServiceRegistryWrapper services) throws NetconfConfigHandlingException {
        if(configuration != null && !configuration.isEmpty()) {
            for (Map.Entry<String, AttributeConfigElement> attrEntry : configuration.entrySet()) {
                if(attrEntry.getValue().getEditStrategy().isPresent()) {
                    final Map<String, AttributeConfigElement> partialConfig =
                            Collections.singletonMap(attrEntry.getKey(), attrEntry.getValue());
                    attrEntry.getValue().getEditStrategy().get().getFittingStrategy()
                            .executeConfiguration(module, instance, partialConfig, ta, services);
                } else {
                    LOG.debug("Skipping configuration element for {}:{}:{}", module, instance, attrEntry.getKey());
                }
            }
        } else {
            LOG.debug("Skipping configuration element for {}:{}", module, instance);
        }
    }

}
