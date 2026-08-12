package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SigningAccessCodeHasherTest {

    @Test
    void hashesWithRandomSaltAndVerifiesInConstantTimeComparisonPath() {
        String first = SigningAccessCodeHasher.hash("correct horse battery staple");
        String second = SigningAccessCodeHasher.hash("correct horse battery staple");

        assertNotEquals(first, second);
        assertFalse(first.contains("correct horse battery staple"));
        assertTrue(SigningAccessCodeHasher.matches("correct horse battery staple", first));
        assertFalse(SigningAccessCodeHasher.matches("wrong", first));
        assertFalse(SigningAccessCodeHasher.matches("correct horse battery staple", "invalid"));
        assertFalse(
                SigningAccessCodeHasher.matches(
                        "correct horse battery staple", first.replace("$210000$", "$999999999$")));
    }
}
