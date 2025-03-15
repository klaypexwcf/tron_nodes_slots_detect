package MyConnection.MyNodeDetect;

import MyConnection.DbUtil;
import MyConnection.MyChannel;
import MyConnection.MyMessage.MyMessage;
import MyConnection.MyMessage.MyStatusMessage;
import MyConnection.StatusInfo;
import org.tron.p2p.connection.Channel;
import org.tron.p2p.connection.message.Message;
import org.tron.p2p.connection.message.detect.StatusMessage;

public class MyNodeDetectService {
    public static void processMessage(MyChannel channel, MyMessage message){
        //do nothing for now
        MyStatusMessage statusMessage = (MyStatusMessage) message;
        String remoteIp = channel.getRemoteInetAddress().getHostAddress();
        int MaxConn = statusMessage.getMaxConnections();
        int CurrentConn = statusMessage.getCurrentConnections();
        DbUtil.addNewStatus(new StatusInfo(remoteIp, CurrentConn, MaxConn));
    }
}
