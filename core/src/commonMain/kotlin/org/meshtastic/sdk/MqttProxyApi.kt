/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.Flow
import org.meshtastic.proto.MqttClientProxyMessage

/**
 * MQTT client proxy: the device publishes through the host's broker connection and the host
 * delivers broker messages back to it.
 *
 * Acquired via [RadioClient.mqttProxy].
 *
 * @since 0.3.0
 */
public interface MqttProxyApi {

    /** Messages the device asks the host to publish, from [MeshEvent.MqttProxyMessage]. */
    public val messages: Flow<MqttClientProxyMessage>

    /**
     * Write [message] to the device as `ToRadio.mqttClientProxyMessage`.
     *
     * @return true once the outbound queue accepted the frame; false when not connected or full
     */
    public suspend fun deliver(message: MqttClientProxyMessage): Boolean
}
