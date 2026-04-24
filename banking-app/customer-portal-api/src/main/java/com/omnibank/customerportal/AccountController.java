package com.omnibank.customerportal;

import com.omnibank.accounts.consumer.api.AccountOpening;
import com.omnibank.accounts.consumer.api.BalanceView;
import com.omnibank.accounts.consumer.api.ConsumerAccountService;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final ConsumerAccountService service;

    public AccountController(ConsumerAccountService service) {
        this.service = service;
    }

    @GetMapping("/{accountNumber}/balance")
    public BalanceView balance(@PathVariable String accountNumber) {
        return service.balance(AccountNumber.of(accountNumber));
    }

    @PostMapping
    public ResponseEntity<OpenAccountResponse> open(@RequestBody OpenAccountRequest req) {
        Optional<Money> initial = req.initialDeposit == null
                ? Optional.empty()
                : Optional.of(Money.of(req.initialDeposit, req.currency));
        AccountNumber number = service.open(new AccountOpening.Request(
                CustomerId.of(req.customerId),
                req.product,
                req.currency,
                initial
        ));
        return ResponseEntity.ok(new OpenAccountResponse(number.raw()));
    }

    public record OpenAccountRequest(
            String customerId,
            ConsumerProduct product,
            CurrencyCode currency,
            BigDecimal initialDeposit
    ) {}

    public record OpenAccountResponse(String accountNumber) {}
}
