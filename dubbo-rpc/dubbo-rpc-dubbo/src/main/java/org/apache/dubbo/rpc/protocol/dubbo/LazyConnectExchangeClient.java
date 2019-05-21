/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.Parameters;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.ResponseFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * dubbo protocol support class.
 *
 * 实现 ExchangeClient 接口，支持懒连接服务器的信息交换客户端实现类。
 */
@SuppressWarnings("deprecation")
final class LazyConnectExchangeClient implements ExchangeClient {

    /**
     * when this warning rises from invocation, program probably have bug.
     */
    protected static final String REQUEST_WITH_WARNING_KEY = "lazyclient_request_with_warning";
    private final static Logger logger = LoggerFactory.getLogger(LazyConnectExchangeClient.class);
    /**
     * 请求时，是否检查告警
     */
    protected final boolean requestWithWarning;
    private final URL url;
    /**
     * 通道处理器
     */
    private final ExchangeHandler requestHandler;
    /**
     * 连接锁
     */
    private final Lock connectLock = new ReentrantLock();
    private final int warning_period = 5000;
    /**
     * lazy connect, initial state for connection
     * lazy connect 如果没有初始化时的连接状态
     */
    private final boolean initialState;
    /**
     * 通信客户端
     */
    private volatile ExchangeClient client;
    /**
     * 警告计数器。每超过一定次数，打印告警日志。参见 {@link #warning(Object)}
     */
    private AtomicLong warningcount = new AtomicLong(0);

    public LazyConnectExchangeClient(URL url, ExchangeHandler requestHandler) {
        // lazy connect, need set send.reconnect = true, to avoid channel bad status.
        this.url = url.addParameter(Constants.SEND_RECONNECT_KEY, Boolean.TRUE.toString());
        this.requestHandler = requestHandler;
        this.initialState = url.getParameter(Constants.LAZY_CONNECT_INITIAL_STATE_KEY, Constants.DEFAULT_LAZY_CONNECT_INITIAL_STATE);
        this.requestWithWarning = url.getParameter(REQUEST_WITH_WARNING_KEY, false);
    }

    /**
     * 初始化客户端
     * 发送消息/请求前，都会调用该方法，保证客户端已经初始化
     * @throws RemotingException
     */
    private void initClient() throws RemotingException {
        // 已初始化，跳过
        if (client != null) {
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Lazy connect to " + url);
        }
        // 获得锁
        connectLock.lock();
        try {
            // 已初始化，跳过
            if (client != null) {
                return;
            }
            // 创建 Client ，连接服务器
            this.client = Exchangers.connect(url, requestHandler);
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public ResponseFuture request(Object request) throws RemotingException {
        warning();
        initClient();
        return client.request(request);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        if (client == null) {
            return InetSocketAddress.createUnresolved(url.getHost(), url.getPort());
        } else {
            return client.getRemoteAddress();
        }
    }

    @Override
    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        warning();
        initClient();  //发送消息/请求前，都会调用该方法，保证客户端已经初始化
        return client.request(request, timeout);
    }

    /**
     * If {@link #REQUEST_WITH_WARNING_KEY} is configured, then warn once every 5000 invocations.
     *
     * 每次发送请求时，会调用 #warning(request) 方法，根据情况，打印告警日志
     */
    private void warning() {
        if (requestWithWarning) {  // 开启
            if (warningcount.get() % warning_period == 0) {  // 5000 次
                logger.warn(new IllegalStateException("safe guard client , should not be called ,must have a bug."));
            }
            warningcount.incrementAndGet();  // 增加计数
        }
    }

    @Override
    public ChannelHandler getChannelHandler() {
        checkClient();
        return client.getChannelHandler();
    }

    @Override
    public boolean isConnected() {
        if (client == null) {
            return initialState;
        } else {
            return client.isConnected();
        }
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        if (client == null) {
            return InetSocketAddress.createUnresolved(NetUtils.getLocalHost(), 0);
        } else {
            return client.getLocalAddress();
        }
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return requestHandler;
    }

    @Override
    public void send(Object message) throws RemotingException {
        initClient();  //发送消息/请求前，都会调用该方法，保证客户端已经初始化
        client.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        initClient();
        client.send(message, sent);
    }

    @Override
    public boolean isClosed() {
        if (client != null) {
            return client.isClosed();
        } else {
            return true;
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public void close(int timeout) {
        if (client != null) {
            client.close(timeout);
        }
    }

    @Override
    public void startClose() {
        if (client != null) {
            client.startClose();
        }
    }

    @Override
    public void reset(URL url) {
        checkClient();
        client.reset(url);
    }

    @Override
    @Deprecated
    public void reset(Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    @Override
    public void reconnect() throws RemotingException {
        checkClient();
        client.reconnect();
    }

    @Override
    public Object getAttribute(String key) {
        if (client == null) {
            return null;
        } else {
            return client.getAttribute(key);
        }
    }

    @Override
    public void setAttribute(String key, Object value) {
        checkClient();
        client.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        checkClient();
        client.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        if (client == null) {
            return false;
        } else {
            return client.hasAttribute(key);
        }
    }

    private void checkClient() {
        if (client == null) {
            throw new IllegalStateException(
                    "LazyConnectExchangeClient state error. the client has not be init .url:" + url);
        }
    }
}
