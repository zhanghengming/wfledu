package io.dataease.enterprise.identity.manage;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PasswordCodecTest {
    @Test void independentSaltAndCorrectComparison() {
        var codec=new PasswordCodec();var password="SyntheticPassword-123".toCharArray();var first=codec.encode(password);var second=codec.encode(password);
        assertThat(first).isNotEqualTo(second);assertThat(codec.matches(password,first)).isTrue();assertThat(codec.matches("WrongPassword-123".toCharArray(),first)).isFalse();
    }
    @Test void malformedOrUnboundedParametersAreNeverAccepted() {
        var codec=new PasswordCodec();for(String value:new String[]{"MD5$bad","PBKDF2_SHA256$999999999$bad$bad","PBKDF2_SHA256$600000$bad$bad"})
            assertThatThrownBy(()->codec.matches("SyntheticPassword-123".toCharArray(),value)).isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidPasswordsAreRejectedBeforeDerivation() {
        var codec=new PasswordCodec();for(String value:new String[]{"short","x".repeat(129),"Password-123\n"})assertThatThrownBy(()->codec.encode(value.toCharArray())).isInstanceOf(IllegalArgumentException.class);
    }
}
