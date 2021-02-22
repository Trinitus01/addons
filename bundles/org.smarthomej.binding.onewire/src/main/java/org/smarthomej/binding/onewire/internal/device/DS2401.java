/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 * Copyright (c) 2021 Contributors to the SmartHome/J project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.smarthomej.binding.onewire.internal.device;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.smarthomej.binding.onewire.internal.OwException;
import org.smarthomej.binding.onewire.internal.SensorId;
import org.smarthomej.binding.onewire.internal.handler.OwBaseThingHandler;
import org.smarthomej.binding.onewire.internal.handler.OwserverBridgeHandler;

/**
 * The {@link DS2401} class defines an DS2401 (iButton) device
 *
 * @author Jan N. Klug - Initial contribution
 */
@NonNullByDefault
public class DS2401 extends AbstractOwDevice {
    public DS2401(SensorId sensorId, OwBaseThingHandler callback) {
        super(sensorId, callback);
        isConfigured = true;
    }

    @Override
    public void configureChannels() throws OwException {
    }

    @Override
    public void refresh(OwserverBridgeHandler bridgeHandler, Boolean forcedRefresh) throws OwException {
    }
}
