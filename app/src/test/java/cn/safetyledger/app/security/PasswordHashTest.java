package cn.safetyledger.app.security;

import org.junit.Test;
import static org.junit.Assert.*;

public class PasswordHashTest {
    @Test public void acceptsCorrectPasswordAndRejectsWrongPassword() throws Exception {
        String stored=PasswordHash.create("correct-horse".toCharArray());
        assertTrue(PasswordHash.verify("correct-horse".toCharArray(),stored));
        assertFalse(PasswordHash.verify("wrong-password".toCharArray(),stored));
    }
}
