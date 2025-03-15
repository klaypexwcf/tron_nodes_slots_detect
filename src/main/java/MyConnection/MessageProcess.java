package MyConnection;

import MyConnection.MyMessage.MyMessage;

public interface MessageProcess {
    void processMessage(MyChannel channel, MyMessage message);
}
