/**
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
package org.smarthomej.binding.tuya.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link ChannelConfiguration} holds the configuration of a single device channel
 *
 * @author Jan N. Klug - Initial contribution
 */
@NonNullByDefault
public class ChannelConfiguration {
    public int dp = 0;
    public int dp2 = 0;
    public int min = Integer.MIN_VALUE;
    public int max = Integer.MAX_VALUE;
    public String range = "";
}
