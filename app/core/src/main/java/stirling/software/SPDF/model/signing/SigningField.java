package stirling.software.SPDF.model.signing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SigningField {

    private String id;
    private String recipientId;
    private String name;
    private String label;
    private Type type = Type.SIGNATURE;
    private int pageIndex;
    private NormalizedBounds bounds = new NormalizedBounds();
    private boolean required = true;
    private boolean readOnly;
    private String placeholder;
    private String defaultValue;
    private String value;
    private Instant completedAt;
    private List<String> options = new ArrayList<>();
    private Map<String, String> metadata = new LinkedHashMap<>();

    public SigningField(SigningField source) {
        id = source.id;
        recipientId = source.recipientId;
        name = source.name;
        label = source.label;
        type = source.type;
        pageIndex = source.pageIndex;
        bounds = source.bounds == null ? null : new NormalizedBounds(source.bounds);
        required = source.required;
        readOnly = source.readOnly;
        placeholder = source.placeholder;
        defaultValue = source.defaultValue;
        value = source.value;
        completedAt = source.completedAt;
        options = source.options == null ? new ArrayList<>() : new ArrayList<>(source.options);
        metadata =
                source.metadata == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(source.metadata);
    }

    public enum Type {
        SIGNATURE("signature"),
        INITIALS("initials"),
        NAME("name"),
        EMAIL("email"),
        DATE_SIGNED("dateSigned"),
        TEXT("text"),
        CHECKBOX("checkbox"),
        RADIO("radio"),
        DROPDOWN("dropdown");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static Type fromValue(String value) {
            if (value == null || value.isBlank()) {
                return SIGNATURE;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Type type : values()) {
                if (type.value.toLowerCase(Locale.ROOT).equals(normalized)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown signing field type: " + value);
        }
    }

    @Data
    @NoArgsConstructor
    public static class NormalizedBounds {
        private static final double EDGE_EPSILON = 0.000000001d;

        private double x;
        private double y;
        private double width;
        private double height;

        public NormalizedBounds(NormalizedBounds source) {
            x = source.x;
            y = source.y;
            width = source.width;
            height = source.height;
        }

        public boolean isWithinPage() {
            return Double.isFinite(x)
                    && Double.isFinite(y)
                    && Double.isFinite(width)
                    && Double.isFinite(height)
                    && x >= 0
                    && y >= 0
                    && width > 0
                    && height > 0
                    && x + width <= 1 + EDGE_EPSILON
                    && y + height <= 1 + EDGE_EPSILON;
        }
    }
}
