/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.agent;

import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.websocket.Message;
import com.thoughtworks.go.websocket.MessageCallback;
import com.thoughtworks.go.websocket.MessageEncoding;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.thoughtworks.go.util.ExceptionUtils.bomb;

public class WebSocketSessionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketSessionHandler.class);

    // This is a session aware socket
    private Session session;
    private String sessionName = "[No Session]";
    private final Map<String, MessageCallback> callbacks = new ConcurrentHashMap<>();
    private WebSocketClientHandler webSocketClientHandler;
    private SystemEnvironment systemEnvironment;

    @Autowired
    public WebSocketSessionHandler(WebSocketClientHandler webSocketClientHandler, SystemEnvironment systemEnvironment) {
        this.webSocketClientHandler = webSocketClientHandler;
        this.systemEnvironment = systemEnvironment;
    }

    synchronized void stop() {
        if (isRunning()) {
            LOG.debug("close {}", sessionName());
            session.close();
            session = null;
            sessionName = "[No Session]";
        }
    }

    private synchronized boolean isRunning() {
        return session != null && session.isOpen();
    }

    synchronized boolean isNotRunning() {
        return !isRunning();
    }

    public synchronized void reconnectSessionIfNotRunning() throws Exception {
        if (isNotRunning()) {
            if (session == null) {
                LOG.debug("Creating new session");
            } else {
                LOG.debug("Re-establishing websocket session");
            }
            this.session = webSocketClientHandler.connect();
            this.sessionName = "[" + session.getRemoteAddress() + "]";
            LOG.debug("Done creating new session");
        }
    }

    public void ping() throws IOException {
        LOG.debug("Sending ws ping");
        session.getRemote().sendPing(ByteBuffer.wrap("".getBytes()));
        LOG.debug("Done sending ws ping");
    }

    public void fireAndForget(Message message) {
        send(message);
    }

    private synchronized void send(Message message) {
        for (int retries = 1; retries <= systemEnvironment.getWebsocketSendRetryCount(); retries++) {
            try {
                LOG.debug("{} attempt {} to send message: {}", sessionName(), retries, message);
                reconnectSessionIfNotRunning();
                session.getRemote().sendBytes(ByteBuffer.wrap(MessageEncoding.encodeMessage(message)));
                LOG.debug("{} Done send message: {}", sessionName(), message);
                break;
            } catch (Throwable e) {
                try {
                    LOG.info("{} attempt {} failed to send message: {}.", sessionName(), retries, message);
                    if (retries == systemEnvironment.getWebsocketSendRetryCount()) {
                        bomb(e);
                    }
                    Thread.sleep(2000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    public void sendAndWaitForAcknowledgement(Message message) {
        final CountDownLatch wait = new CountDownLatch(1);
        sendWithCallback(message, new MessageCallback() {
            @Override
            public void call() {
                wait.countDown();
            }
        });
        try {
            boolean success = wait.await(systemEnvironment.getWebsocketAckMessageTimeout(), TimeUnit.MILLISECONDS);
            if (!success) {
                LOG.debug("Removing callback for " + message.getAcknowledgementId());
                callbacks.remove(message.getAcknowledgementId());
                LOG.error(String.format("Did not receive a response from the server within %s mills. Message: %s. Action: %s. AckId: %s.", systemEnvironment.getWebsocketAckMessageTimeout(), message.getData(), message.getAction(), message.getAcknowledgementId()));
                throw new RuntimeException(String.format("Did not receive a response from the server within %s mills. Message: %s. Action: %s. AckId: %s", systemEnvironment.getWebsocketAckMessageTimeout(), message.getData(), message.getAction(), message.getAcknowledgementId()));
            }
        } catch (InterruptedException e) {
            LOG.debug("Removing callback for " + message.getAcknowledgementId());
            callbacks.remove(message.getAcknowledgementId());
            LOG.error(String.format("Thread interrupted. Message: %s. Action: %s. AckId: %s. Exception: %s", message.getData(), message.getAction(), message.getAcknowledgementId(), e.getMessage()), e);
            bomb(e);
        }
    }

    private void sendWithCallback(Message message, MessageCallback callback) {
        LOG.debug("Registering callback for action: {}, ackid: {}, data: {}", message.getAction(), message.getAcknowledgementId(), message.getData());
        callbacks.put(message.getAcknowledgementId(), callback);
        send(message);
    }

    private String sessionName() {
        return session == null ? "[No session initialized]" : "Session[" + session.getRemoteAddress() + "]";
    }

    String getSessionName() {
        return sessionName;
    }

    public void acknowledge(Message message) {
        String acknowledgementId = MessageEncoding.decodeData(message.getData(), String.class);
        LOG.debug("Acknowledging {}", acknowledgementId);
        MessageCallback callback = callbacks.remove(acknowledgementId);
        if (callback == null) {
            LOG.error("Callback for {} was null. This could happen if the server is responding very slowly or the agent was restarted.", acknowledgementId);
        } else {
            callback.call();
        }
    }

    void setSession(Session session) {
        this.session = session;
    }
}
