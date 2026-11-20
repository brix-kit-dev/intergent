package aie.adapters.redactionrules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.ports.RedactionPort.Kind;
import aie.ports.RedactionPort.Redacted;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuleRedactionTest {

    private final RuleRedaction redaction = new RuleRedaction();

    /** M3-4-2: an email address, half-width or with a full-width at sign, becomes [email]. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Mail me at taro.yamada@example.co.jp please|Mail me at [email] please",
        "連絡先：hanako＠example.com|連絡先：[email]",
        "first.last+shop@mail-server.example.org.|[email].",
    })
    void anEmailAddressIsMasked(String text, String masked) {
        assertThat(redaction.redact(text)).isEqualTo(new Redacted(masked, Set.of(Kind.EMAIL)));
    }

    /** A card number that passes the Luhn check becomes [card], however its digits are grouped or written. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "My card is 4111 1111 1111 1111.|My card is [card].",
        "カード番号は4111-1111-1111-1111です|カード番号は[card]です",
        "５５５５５５５５５５５５４４４４|[card]",
        "Amex 378282246310005 here|Amex [card] here",
        "JCB 3530 1113 3330 0000|JCB [card]",
    })
    void aCardNumberIsMasked(String text, String masked) {
        assertThat(redaction.redact(text)).isEqualTo(new Redacted(masked, Set.of(Kind.CARD)));
    }

    /**
     * A telephone number becomes [phone]: national ones from 0, Japanese and Chinese, with or without hyphens, spaces, the
     * long vowel mark or brackets; Chinese mobile numbers; and international ones after +.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Call 090-1234-5678 after six|Call [phone] after six",
        "電話は03-1234-5678です|電話は[phone]です",
        "(03) 1234-5678|[phone]",
        "フリーダイヤル0120-123-456まで|フリーダイヤル[phone]まで",
        "09012345678|[phone]",
        "０９０ー１２３４ー５６７８|[phone]",
        "北京 010-12345678|北京 [phone]",
        "我的手机是13800138000|我的手机是[phone]",
        "138 0013 8000|[phone]",
        "+81 90-1234-5678|[phone]",
        "Our US office: +1 (415) 555-0100.|Our US office: [phone].",
        "＋86 138 0013 8000|[phone]",
    })
    void aPhoneNumberIsMasked(String text, String masked) {
        assertThat(redaction.redact(text)).isEqualTo(new Redacted(masked, Set.of(Kind.PHONE)));
    }

    @Test
    void eachPieceIsMaskedAndEachKindNoted() {
        Redacted redacted = redaction.redact(
                "Email a.b@example.com, phone 090-1234-5678, card 5555 5555 5555 4444, again a.b@example.com");

        assertThat(redacted.text()).isEqualTo("Email [email], phone [phone], card [card], again [email]");
        assertThat(redacted.found()).containsExactlyInAnyOrder(Kind.EMAIL, Kind.PHONE, Kind.CARD);
    }

    /**
     * The digits of an email address or a card are not read as a phone: emails go first, then cards, then phones. A card
     * whose digits fail the Luhn check is not masked at all, rather than taken for something else.
     */
    @Test
    void theDigitsOfAnAddressOrACardAreNotAPhone() {
        assertThat(redaction.redact("09012345678@example.jp")).isEqualTo(new Redacted("[email]", Set.of(Kind.EMAIL)));
        assertThat(redaction.redact("4111 1111 1111 1111")).isEqualTo(new Redacted("[card]", Set.of(Kind.CARD)));
    }

    /** What only looks a little like such data stays as it was: dates, times, prices, postal codes, versions, orders. */
    @ParameterizedTest
    @ValueSource(strings = {
        "Order 12345 arrived on 2026-11-20 at 10:30.",
        "〒100-0001 東京都千代田区千代田1-1",
        "合計は¥12,800です",
        "4111 1111 1111 1112",
        "Version 1.2.3, build 20261120",
        "ISBN 978-4-06-123456-7",
        "Room 0123, floor 3",
        "The years 2026 and 1999",
        "x@y and @someone",
        "12345678901234567890123",
        "注文番号 A0001234",
        "123090123456789",
        "1230123456789",
        "",
    })
    void textWithoutPersonalDataStaysAsItWas(String text) {
        assertThat(redaction.redact(text)).isEqualTo(new Redacted(text, Set.of()));
    }

    @Test
    void aTextIsRequired() {
        assertThatThrownBy(() -> redaction.redact(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Masking needs a text");
    }

    /** The placeholders are part of what the model is told, so they never change. */
    @Test
    void thePlaceholdersAreFixed() {
        assertThat(RuleRedaction.placeholder(Kind.PHONE)).isEqualTo("[phone]");
        assertThat(RuleRedaction.placeholder(Kind.EMAIL)).isEqualTo("[email]");
        assertThat(RuleRedaction.placeholder(Kind.CARD)).isEqualTo("[card]");
    }

    /** A long text costs no more than a pass over it, even one long run of digits or one of digits and spaces. */
    @Test
    void aLongTextIsMaskedInTime() {
        long started = System.nanoTime();

        assertThat(redaction.redact("1234567890".repeat(3200)).found()).isEmpty();
        assertThat(redaction.redact("1  2  3  4  5  6  7  8  9  0  ".repeat(1000)).found()).isEmpty();
        assertThat(redaction.redact("a".repeat(31980) + " 090-1234-5678").found()).containsExactly(Kind.PHONE);
        assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
    }
}
