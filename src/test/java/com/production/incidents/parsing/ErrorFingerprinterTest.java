package com.production.incidents.parsing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorFingerprinterTest {

    private final ErrorFingerprinter fingerprinter = new ErrorFingerprinter();

    @Test
    void masksVariableParts() {
        String normalized = fingerprinter.normalize(
                "Request 3f2b1c9e-8a7d-4e6f-9b0a-1c2d3e4f5a6b from 10.0.4.21:9200 for user bob@example.com "
                        + "failed after 3000 ms on SKU 'SKU-88213' at http://pay.internal/v1/charge?id=7");

        assertThat(normalized).isEqualTo(
                "Request <uuid> from <ip> for user <email> failed after <num> ms on SKU <str> at <url>");
    }

    @Test
    void similarMessagesShareAFingerprint() {
        String a = fingerprinter.normalize("Payment authorization failed for order 100231 after 3000 ms");
        String b = fingerprinter.normalize("Payment authorization failed for order 100377 after 3000 ms");

        assertThat(a).isEqualTo(b);
        assertThat(fingerprinter.fingerprint("java.net.SocketTimeoutException", a))
                .isEqualTo(fingerprinter.fingerprint("java.net.SocketTimeoutException", b));
    }

    @Test
    void differentExceptionTypesDoNotMerge() {
        String msg = fingerprinter.normalize("Checkout failed");

        assertThat(fingerprinter.fingerprint("java.io.IOException", msg))
                .isNotEqualTo(fingerprinter.fingerprint("java.lang.IllegalStateException", msg));
    }

    @Test
    void fingerprintIsFixedLengthHex() {
        assertThat(fingerprinter.fingerprint(null, "x")).matches("[0-9a-f]{32}");
    }
}
