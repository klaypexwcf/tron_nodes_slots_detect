package MyConnection.MySocket;

import MyConnection.MyChannel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.tron.p2p.discover.Node;
import org.tron.p2p.protos.Discover;

@Slf4j
public class MyP2pChannelInitializer extends ChannelInitializer<NioSocketChannel> {
    private final Discover.Endpoint localEndpoint;
    private final Discover.Endpoint remoteEndpoint;
    private final Node localNode;
    private final Node remoteNode;

    private boolean peerDiscoveryMode; //only be true when channel is activated by detect service
    private String disconnectReason="";

    public MyP2pChannelInitializer(boolean peerDiscoveryMode, Node remoteNode,
                                   Node localNode, Discover.Endpoint remoteEndpoint, Discover.Endpoint localEndpoint) {
        this.peerDiscoveryMode = peerDiscoveryMode;
        this.remoteNode = remoteNode;
        this.localNode = localNode;
        this.remoteEndpoint = remoteEndpoint;
        this.localEndpoint = localEndpoint;
    }


    @Override
    public void initChannel(NioSocketChannel ch) {
        try {
            final MyChannel channel = new MyChannel();
            channel.init(ch.pipeline(), true,localNode,remoteNode,localEndpoint,remoteEndpoint);

            // limit the size of receiving buffer to 1024
            ch.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(256 * 1024));
            ch.config().setOption(ChannelOption.SO_RCVBUF, 256 * 1024);
            ch.config().setOption(ChannelOption.SO_BACKLOG, 1024);
            // be aware of channel closing
            ch.closeFuture().addListener((ChannelFutureListener) future -> {
                channel.setDisconnect(true);
                disconnectReason= channel.getDisconnectReason();
                log.info("Close channel:{}", channel.getRemoteInetSocketAddress());
            });
        } catch (Exception e) {
            System.out.println("Unexpected initChannel error"+e);
            log.error("Unexpected initChannel error", e);
        }
    }
}
