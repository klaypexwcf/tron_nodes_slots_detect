package MyConnection.MySocket;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.tron.p2p.discover.Node;
import org.tron.p2p.protos.Discover;

@Slf4j
public class MyPeerClient {
    private EventLoopGroup workerGroup;

    public void init() {
        workerGroup = new NioEventLoopGroup(0,
                new BasicThreadFactory.Builder().namingPattern("peerClient-%d").build());
    }

    public void close() {
        workerGroup.shutdownGracefully();
        workerGroup.terminationFuture().syncUninterruptibly();
        System.out.println("peer client closed");
    }
    public ChannelFuture connectInDiscMode(Node remoteNode, Node localNode, Discover.Endpoint localEndpoint, Discover.Endpoint remoteEndpoint) {
        Bootstrap b = null;
        try {
            MyP2pChannelInitializer p2pChannelInitializer=
                    new MyP2pChannelInitializer(true,remoteNode,localNode,localEndpoint,remoteEndpoint);
            b = new Bootstrap();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
            b.remoteAddress(remoteNode.getHostV4(), remoteNode.getPort());
            b.handler(p2pChannelInitializer);
            b.bind(localNode.getPort());
        } catch (Exception e) {
            System.out.println("exception caught " + e);
            log.error("exception caught ",e);
            throw new RuntimeException(e);
        }
        return b.connect();
    }
}
