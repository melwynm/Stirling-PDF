package stirling.software.SPDF.model.api.esign;

import java.time.Instant;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureDueReminder {
    private String requestId;
    private String recipientId;
    private String recipientName;
    private String recipientEmail;
    private Instant dueAt;
    private int reminderCount;
}
