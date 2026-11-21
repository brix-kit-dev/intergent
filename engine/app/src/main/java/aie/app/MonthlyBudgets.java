package aie.app;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The cell's default monthly budget (M3-6 decision 4): ENGINE_DEFAULT_MONTHLY_BUDGET_USD, the US dollars a tenant
 * without a limit of its own may spend on model calls each month. The api refuses to start without it, so that no
 * tenant is without a limit (T-4); the admin role reads it to tell where a tenant stands.
 */
final class MonthlyBudgets {

    /** The default's setting, which an operator sets as ENGINE_DEFAULT_MONTHLY_BUDGET_USD. */
    static final String DEFAULT = "engine.default.monthly.budget.usd";
    /** A number of US dollars, written plainly: digits, and a fraction after a point if any, such as 100 or 12.50. */
    static final Pattern AMOUNT = Pattern.compile("[0-9]+(\\.[0-9]+)?");

    private MonthlyBudgets() {
    }

    /** @throws IllegalArgumentException when the default is missing or not an amount */
    static BigDecimal defaultLimit(RoleSettings settings) {
        return amount(settings.required(DEFAULT), RoleSettings.variable(DEFAULT));
    }

    /**
     * An amount of US dollars as an operator writes it: 0 or more, plainly, with no sign, no exponent and no currency.
     *
     * @param what the variable or the option it came from, which a refusal names
     * @throws IllegalArgumentException for anything else
     */
    static BigDecimal amount(String value, String what) {
        String plain = value.strip();
        if (!AMOUNT.matcher(plain).matches()) {
            throw new IllegalArgumentException(what + " must be a number of US dollars such as 100 or 12.50, not " + plain);
        }
        return new BigDecimal(plain);
    }
}
