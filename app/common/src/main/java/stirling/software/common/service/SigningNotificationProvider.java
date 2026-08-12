package stirling.software.common.service;

public interface SigningNotificationProvider {

    String channel();

    void send(SigningNotificationMessage message) throws Exception;

    record SigningNotificationMessage(
            String destination, String recipientName, String subject, String body) {}
}
