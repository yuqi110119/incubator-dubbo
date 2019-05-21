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
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.MultiMessage;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcResult;

import java.io.IOException;

/**
 * 实现 Codec2 接口，支持【多消息】的编解码器。
 *
 * 在 Dubbo Client 和 Server 创建的过程，我们看到设置了编解码器为 "dubbo" ，从而通过 Dubbo SPI 机制，加载到 DubboCountCodec
 */
public final class DubboCountCodec implements Codec2 {

    /**
     * 编解码器
     */
    private DubboCodec codec = new DubboCodec();

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        codec.encode(channel, buffer, msg);
    }

    @Override
    /**
     * 解码
     *
     * 包含两块逻辑：1）多消息解析的支持。
     *            2）记录每条消息的长度，用于 MonitorFilter 监控。
     */
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // 记录当前读位置 用于下面计算每条消息的长度。
        int save = buffer.readerIndex();
        // 创建 MultiMessage 对象  MultiMessageHandler 支持对它的处理分发
        MultiMessage result = MultiMessage.create();
        do {  //循环解析消息，直到结束
            // 解码
            Object obj = codec.decode(channel, buffer);
            if (Codec2.DecodeResult.NEED_MORE_INPUT == obj) {
                // 输入不够，重置读进度  字节数组不够，重置读进度，结束解析
                buffer.readerIndex(save);
                break;
            } else {  // 解析到消息
                // 添加结果消息
                result.addMessage(obj);
                // 记录消息长度到隐式参数集合，用于 MonitorFilter 监控
                logMessageLength(obj, buffer.readerIndex() - save);
                // 记录当前读位置
                save = buffer.readerIndex();
            }
        } while (true);
        // 需要更多的输入
        if (result.isEmpty()) {
            return Codec2.DecodeResult.NEED_MORE_INPUT;
        }
        // 返回解析到的消息
        if (result.size() == 1) {
            return result.get(0);
        }
        return result;
    }

    private void logMessageLength(Object result, int bytes) {
        if (bytes <= 0) {
            return;
        }
        if (result instanceof Request) {
            try {
                ((RpcInvocation) ((Request) result).getData()).setAttachment(
                        Constants.INPUT_KEY, String.valueOf(bytes));
            } catch (Throwable e) {
                /* ignore */
            }
        } else if (result instanceof Response) {
            try {
                ((RpcResult) ((Response) result).getResult()).setAttachment(
                        Constants.OUTPUT_KEY, String.valueOf(bytes));
            } catch (Throwable e) {
                /* ignore */
            }
        }
    }

}
