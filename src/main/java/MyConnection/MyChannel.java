package MyConnection;

import com.google.common.base.Throwables;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import MyConnection.MyMessage.*;
import MyConnection.MySocket.MyMessageHandler;
import MyConnection.MySocket.MyP2pProtobufVariant32FrameDecoder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.tron.p2p.connection.business.upgrade.UpgradeController;
import org.tron.p2p.discover.Node;
import org.tron.p2p.exception.P2pException;
import org.tron.p2p.protos.Connect;
import org.tron.p2p.protos.Discover;
import org.tron.p2p.stats.TrafficStats;
import org.tron.p2p.utils.ByteArray;
import org.tron.protos.Protocol;
import org.tron.p2p.protos.Discover.Endpoint;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j()
public class MyChannel {
    public volatile boolean waitForPong = false;
    public volatile long pingSent = System.currentTimeMillis();
    @Getter
    private Endpoint localEndpoint;
    @Getter
    private Endpoint remoteEndpoint;
    @Getter
    private Node localNode;
    @Getter
    private Node remoteNode;
    @Getter
    private InetSocketAddress remoteInetSocketAddress;
    @Getter
    private InetAddress remoteInetAddress;

    @Getter
    private MyHelloMessage helloMessage;
    @Getter
    private ChannelHandlerContext ctx;
    @Getter
    @Setter
    private String disconnectReason="";
    @Getter
    private volatile long disconnectTime;
    @Getter
    @Setter
    private volatile boolean isDisconnect = false;
    @Getter
    @Setter
    private long lastSendTime = System.currentTimeMillis();
    @Getter
    private final long startTime = System.currentTimeMillis();
    @Getter
    protected boolean isActive = false;
    @Getter
    private boolean isTrustPeer;
    @Getter
    @Setter
    private volatile boolean finishHandshake;
    @Setter
    @Getter
    private boolean discoveryMode;
    @Getter
    private long avgLatency;
    private long count;

    public void init(ChannelPipeline pipeline,  boolean discoveryMode,
                     Node localNode, Node remoteNode, Endpoint localEndpoint, Endpoint remoteEndpoint) {
        this.localNode = localNode;
        this.remoteNode = remoteNode;
        this.localEndpoint = localEndpoint;
        this.remoteEndpoint = remoteEndpoint;
        this.discoveryMode = discoveryMode;
        this.isActive = StringUtils.isNotEmpty(remoteNode.getHexId());
        MyMessageHandler messageHandler = new MyMessageHandler(this);
        pipeline.addLast("readTimeoutHandler", new ReadTimeoutHandler(60, TimeUnit.SECONDS));
        pipeline.addLast(TrafficStats.tcp);
        pipeline.addLast("protoPrepend", new ProtobufVarint32LengthFieldPrepender());
        pipeline.addLast("protoDecode", new MyP2pProtobufVariant32FrameDecoder(this));
        pipeline.addLast("messageHandler", messageHandler);
    }

    public void processException(Throwable throwable) {
        Throwable baseThrowable = throwable;
        try {
            baseThrowable = Throwables.getRootCause(baseThrowable);
        } catch (IllegalArgumentException e) {
            baseThrowable = e.getCause();
            log.warn("Loop in causal chain detected");
        }
        SocketAddress address = ctx.channel().remoteAddress();
        if (throwable instanceof ReadTimeoutException
                || throwable instanceof IOException
                || throwable instanceof CorruptedFrameException) {
            log.warn("Close peer {}, reason: {}", address, throwable.getMessage());
        } else if (baseThrowable instanceof P2pException) {
            log.warn("Close peer {}, type: ({}), info: {}",
                    address, ((P2pException) baseThrowable).getType(), baseThrowable.getMessage());
        } else {
            log.error("Close peer {}, exception caught", address, throwable);
        }
        close(throwable.getMessage());
    }

    public void setHelloMessage(MyHelloMessage helloMessage) {
        this.helloMessage = helloMessage;
        this.remoteNode = helloMessage.getFrom();
    }

    public void setChannelHandlerContext(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.remoteInetSocketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        this.remoteInetAddress = remoteInetSocketAddress.getAddress();
        this.isTrustPeer =false;
    }

    public void close(long banTime,String reason) {
        this.isDisconnect = true;
        this.disconnectTime = System.currentTimeMillis();
        disconnectReason =reason;
        ctx.close();
    }

    public void close(String reason) {
        close(1L,reason);
    }
    public void sendStatusMsg(){
        MyStatusMessage statusMessage = new MyStatusMessage(localNode.getHexId(),
                localEndpoint,30,30,11111);
        send(statusMessage);
        log.info("sent status msg to channel {}",remoteNode.getHostV4());
    }
    public void sendP2PDisconnectMsg(Connect.DisconnectReason disconnectReason){
        send(new MyP2pDisconnectMessage(disconnectReason));
    }
    public void sendPingMsg(){
        send(new MyPingMessage());
    }
    public void sendPongMsg(){
        send(new MyPongMessage());
    }

    public void send(MyMessage message) {
        MDC.put("customFileName", remoteInetAddress.getHostAddress());
        if (message.needToLog()) {
            log.info("Send message to channel {}, {}", remoteInetSocketAddress, message);
        } else {
            log.debug("Send message to channel {}, {}", remoteInetSocketAddress, message);
        }
        //MDC.remove("customFileName");
        send(message.getSendData());
    }

    public void send(byte[] data) {
        try {
            byte type = data[0];
            if (isDisconnect) {
                log.warn("Send to {} failed as channel has closed, message-type:{} ",
                        ctx.channel().remoteAddress(), type);
                return;
            }

            if (finishHandshake) {
                data = UpgradeController.codeSendData(11111, data);
            }

            ByteBuf byteBuf = Unpooled.wrappedBuffer(data);
            ctx.writeAndFlush(byteBuf).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess() && !isDisconnect) {
                    log.warn("Send to {} failed, message-type:{}, cause:{}",
                            ctx.channel().remoteAddress(), ByteArray.byte2int(type),
                            future.cause().getMessage());
                }
            });
            setLastSendTime(System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("Send message to {} failed, {}", remoteInetSocketAddress, e.getMessage());
            ctx.channel().close();
        }
    }

    public void updateAvgLatency(long latency) {
        long total = this.avgLatency * this.count;
        this.count++;
        this.avgLatency = (total + latency) / this.count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MyChannel channel = (MyChannel) o;
        return Objects.equals(remoteInetSocketAddress, channel.remoteInetSocketAddress);
    }

    @Override
    public int hashCode() {
        return remoteInetSocketAddress.hashCode();
    }

    @Override
    public String toString() {
        return String.format("%s | %s", remoteInetSocketAddress,
                StringUtils.isEmpty(remoteNode.getHexId()) ? "<null>" : remoteNode.getHexId());
    }
}
